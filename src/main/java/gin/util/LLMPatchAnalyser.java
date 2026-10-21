package gin.util;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.sampullara.cli.Args;
import com.sampullara.cli.Argument;
import org.apache.commons.io.FileUtils;
import org.pmw.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gin.SourceFileTree;
import gin.SourceFileLine;
import gin.Patch;

/**
 * Temporary PatchAnalyser for LocalSearchRuntime CSVs where the Patch column
 * contains the classic diff emitted for one LLMReplaceStatement rather than a
 * serialised Gin Edit.
 *
 * The diff is relative to destinationNode.toString(), so we find the target
 * JavaParser BlockStmt by comparing the '<' side of the diff against each
 * candidate block's toString(), apply the diff to that pretty-printed block,
 * then splice the resulting block back into the original source using the
 * JavaParser source range.
 */
public class LLMPatchAnalyser {

    @Argument(alias = "f", description = "Required: source filename", required = true)
    protected File source;

    @Argument(alias = "p", description = "Required: LLMReplaceStatement diff", required = true)
    protected String patchText;

    @Argument(alias = "m", description = "Target simple method name (or full CSV MethodName value)")
    protected String methodName;

    @Argument(alias = "od", description = "Save output to the given directory")
    protected String outputDir = null;

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^(\\d+)(?:,(\\d+))?([acd])(\\d+)(?:,(\\d+))?$");

    LLMPatchAnalyser(String[] args) {
        Args.parseOrExit(this, args);
    }

    public static void main(String[] args) {
        LLMPatchAnalyser analyser = new LLMPatchAnalyser(args);
        analyser.analyse();
    }

    protected void analyse() {
        try {
            String original = Files.readString(source.toPath(), Charset.defaultCharset());
            String patched;

            if (patchText == null || patchText.trim().isEmpty() || patchText.trim().equals("|")) {
                Logger.info("Empty patch: writing original source unchanged.");
                patched = original;
            } else {
                List<DiffHunk> hunks = parseDiff(patchText);
                if (hunks.isEmpty()) {
                    throw new IllegalArgumentException("No classic diff hunks found in Patch field.");
                }

                CompilationUnit cu = StaticJavaParser.parse(original);
                BlockMatch match = findDestinationBlock(cu, hunks);
                List<String> replacementBlock = applyDiffToBlock(match.blockLines, hunks);
                patched = replaceBlockInSource(original, match.block, replacementBlock);

                Logger.info("Matched LLM diff to block beginning at source line "
                        + match.block.getRange().get().begin.line);
            }

            writeOutputs(original, patched);

        } catch (Exception e) {
            Logger.error("Could not analyse LLM diff: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private BlockMatch findDestinationBlock(CompilationUnit cu, List<DiffHunk> hunks) {
        List<BlockStmt> candidates = candidateBlocks(cu);
        List<BlockMatch> matches = new ArrayList<>();

        boolean hasOldText = hunks.stream().anyMatch(h -> !h.oldLines.isEmpty());

        for (BlockStmt block : candidates) {
            if (block.getRange().isEmpty()) {
                continue;
            }

            List<String> blockLines = splitLines(block.toString());

            if (hasOldText) {
                if (diffOldSideMatches(blockLines, hunks)) {
                    matches.add(new BlockMatch(block, blockLines));
                }
            } else {
                // A pure addition has no '<' side to identify the block.  It is
                // only safe to accept when the target method has one candidate.
                matches.add(new BlockMatch(block, blockLines));
            }
        }

        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "Could not match the '<' side of the diff to any block in the target method.");
        }

        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "Diff is ambiguous: it matches " + matches.size()
                            + " candidate blocks in the target method.");
        }

        return matches.get(0);
    }

    private List<BlockStmt> candidateBlocks(CompilationUnit cu) {
        if (methodName == null || methodName.isBlank()) {
            Logger.warn("No MethodName supplied; searching all blocks in the compilation unit.");
            return cu.findAll(BlockStmt.class);
        }

        String simpleName = simpleMethodName(methodName);
        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(simpleName))
                .toList();

        if (methods.isEmpty()) {
            throw new IllegalArgumentException("Could not find method " + simpleName + " in " + source);
        }

        List<BlockStmt> blocks = new ArrayList<>();
        for (MethodDeclaration method : methods) {
            blocks.addAll(method.findAll(BlockStmt.class));
        }

        if (methods.size() > 1) {
            Logger.warn("Found " + methods.size() + " overloads named " + simpleName
                    + "; using diff contents to disambiguate the target block.");
        }

        return blocks;
    }

    private static String simpleMethodName(String description) {
        String beforeParen = description;
        int paren = beforeParen.indexOf('(');
        if (paren >= 0) {
            beforeParen = beforeParen.substring(0, paren);
        }
        return beforeParen.substring(beforeParen.lastIndexOf('.') + 1);
    }

    private static boolean diffOldSideMatches(List<String> blockLines, List<DiffHunk> hunks) {
        for (DiffHunk h : hunks) {
            if (h.oldLines.isEmpty()) {
                continue;
            }

            int start = h.oldStart - 1;
            if (start < 0 || start + h.oldLines.size() > blockLines.size()) {
                return false;
            }

            for (int i = 0; i < h.oldLines.size(); i++) {
                if (!samePrettyPrintedLine(blockLines.get(start + i), h.oldLines.get(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean samePrettyPrintedLine(String a, String b) {
        // Normally these are byte-for-byte identical because both came from
        // JavaParser's toString(). trim() makes this tolerant of a harmless
        // indentation discrepancy without ignoring Java token differences.
        return a.equals(b) || a.trim().equals(b.trim());
    }

    private static List<String> applyDiffToBlock(List<String> originalBlock,
                                                  List<DiffHunk> hunks) {
        List<String> result = new ArrayList<>(originalBlock);
        List<DiffHunk> reverse = new ArrayList<>(hunks);
        reverse.sort(Comparator.comparingInt((DiffHunk h) -> h.oldStart).reversed());

        for (DiffHunk h : reverse) {
            if (h.action == 'a') {
                // N a ... means insert after old line N.  List insertion index
                // N therefore gives the required position.
                int insertAt = h.oldStart;
                if (insertAt < 0 || insertAt > result.size()) {
                    throw new IllegalArgumentException("Addition hunk is outside target block.");
                }
                result.addAll(insertAt, h.newLines);
                continue;
            }

            int start = h.oldStart - 1;
            int oldCount = h.oldEnd - h.oldStart + 1;

            if (h.oldLines.size() != oldCount) {
                throw new IllegalArgumentException(
                        "Diff old-line count disagrees with hunk header at line " + h.oldStart);
            }
            if (start < 0 || start + oldCount > result.size()) {
                throw new IllegalArgumentException("Diff hunk is outside target block.");
            }

            for (int i = 0; i < oldCount; i++) {
                if (!samePrettyPrintedLine(result.get(start + i), h.oldLines.get(i))) {
                    throw new IllegalArgumentException(
                            "Target block no longer matches diff at block line " + (h.oldStart + i));
                }
            }

            for (int i = 0; i < oldCount; i++) {
                result.remove(start);
            }

            if (h.action == 'c') {
                result.addAll(start, h.newLines);
            }
        }

        return result;
    }

    private static String replaceBlockInSource(String sourceText, BlockStmt block,
                                                List<String> replacementLines) {
        if (block.getRange().isEmpty()) {
            throw new IllegalArgumentException("Matched block has no source range.");
        }

        com.github.javaparser.Range range = block.getRange().get();
        int startOffset = offsetFor(sourceText, range.begin.line, range.begin.column);
        int endOffsetExclusive = offsetFor(sourceText, range.end.line, range.end.column) + 1;

        String newline = sourceText.contains("\r\n") ? "\r\n" : "\n";
        String outerIndent = leadingIndentOfLine(sourceText, startOffset);

        StringBuilder replacement = new StringBuilder();
        for (int i = 0; i < replacementLines.size(); i++) {
            if (i > 0) {
                replacement.append(newline).append(outerIndent);
            }
            replacement.append(replacementLines.get(i));
        }

        return sourceText.substring(0, startOffset)
                + replacement
                + sourceText.substring(endOffsetExclusive);
    }

    /** Convert JavaParser's 1-based line/column pair to a String offset. */
    private static int offsetFor(String text, int line, int column) {
        int currentLine = 1;
        int lineStart = 0;

        while (currentLine < line) {
            int nl = text.indexOf('\n', lineStart);
            if (nl < 0) {
                throw new IllegalArgumentException("AST range points beyond end of source.");
            }
            lineStart = nl + 1;
            currentLine++;
        }

        return lineStart + column - 1;
    }

    private static String leadingIndentOfLine(String text, int offset) {
        int lineStart = text.lastIndexOf('\n', Math.max(0, offset - 1));
        lineStart = lineStart < 0 ? 0 : lineStart + 1;

        int p = lineStart;
        while (p < text.length()) {
            char c = text.charAt(p);
            if (c != ' ' && c != '\t') {
                break;
            }
            p++;
        }
        return text.substring(lineStart, p);
    }

    private static List<DiffHunk> parseDiff(String diff) {
        String normalised = diff.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalised.split("\n", -1);
        List<DiffHunk> hunks = new ArrayList<>();

        int i = 0;
        while (i < lines.length) {
            Matcher header = HUNK_HEADER.matcher(lines[i]);
            if (!header.matches()) {
                i++;
                continue;
            }

            int oldStart = Integer.parseInt(header.group(1));
            int oldEnd = header.group(2) == null ? oldStart : Integer.parseInt(header.group(2));
            char action = header.group(3).charAt(0);
            int newStart = Integer.parseInt(header.group(4));
            int newEnd = header.group(5) == null ? newStart : Integer.parseInt(header.group(5));
            i++;

            List<String> oldLines = new ArrayList<>();
            List<String> newLines = new ArrayList<>();

            if (action == 'c' || action == 'd') {
                while (i < lines.length && lines[i].startsWith("< ")) {
                    oldLines.add(lines[i].substring(2));
                    i++;
                }
            }

            if (action == 'c') {
                if (i >= lines.length || !lines[i].equals("---")) {
                    throw new IllegalArgumentException("Malformed change hunk near old line " + oldStart);
                }
                i++;
            }

            if (action == 'c' || action == 'a') {
                while (i < lines.length && lines[i].startsWith("> ")) {
                    newLines.add(lines[i].substring(2));
                    i++;
                }
            }

            while (i < lines.length && lines[i].equals("\\ No newline at end of file")) {
                i++;
            }

            hunks.add(new DiffHunk(oldStart, oldEnd, newStart, newEnd,
                    action, oldLines, newLines));
        }

        return hunks;
    }

    private static List<String> splitLines(String text) {
        String normalised = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = normalised.split("\n", -1);
        List<String> lines = new ArrayList<>();
        for (String part : parts) {
            lines.add(part);
        }
        return lines;
    }

    private void writeOutputs(String original, String patched) throws IOException {
        File outDir = outputDir == null ? null : new File(outputDir);
        if (outDir != null) {
            FileUtils.forceMkdir(outDir);
        }

        String baseName = source.getName();
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) {
            baseName = baseName.substring(0, dot);
        }

        File originalFile = outDir == null
                ? new File(source.getPath() + ".original")
                : new File(outDir, baseName + ".original");
        File patchedFile = outDir == null
                ? new File(source.getPath() + ".patched")
                : new File(outDir, baseName + ".patched");

        Files.writeString(originalFile.toPath(), original, Charset.defaultCharset());
        Files.writeString(patchedFile.toPath(), patched, Charset.defaultCharset());

        // Keep the numbering files produced by the normal PatchAnalyser; they
        // are handy when inspecting any failures/ambiguities.
        SourceFileTree sourceFileTree = new SourceFileTree(source.getAbsolutePath(), null);
        File statementsFile = outDir == null
                ? new File(source.getPath() + ".statements")
                : new File(outDir, baseName + ".statements");
        File blocksFile = outDir == null
                ? new File(source.getPath() + ".blocks")
                : new File(outDir, baseName + ".blocks");

        FileUtils.writeStringToFile(statementsFile,
                sourceFileTree.statementListWithIDs(), Charset.defaultCharset());
        FileUtils.writeStringToFile(blocksFile,
                sourceFileTree.blockListWithIDs(), Charset.defaultCharset());

        Logger.info("Original source written to: " + originalFile);
        Logger.info("Patched source written to: " + patchedFile);
    }

    private static class DiffHunk {
        final int oldStart;
        final int oldEnd;
        final int newStart;
        final int newEnd;
        final char action;
        final List<String> oldLines;
        final List<String> newLines;

        DiffHunk(int oldStart, int oldEnd, int newStart, int newEnd,
                 char action, List<String> oldLines, List<String> newLines) {
            this.oldStart = oldStart;
            this.oldEnd = oldEnd;
            this.newStart = newStart;
            this.newEnd = newEnd;
            this.action = action;
            this.oldLines = oldLines;
            this.newLines = newLines;
        }
    }

    private static class BlockMatch {
        final BlockStmt block;
        final List<String> blockLines;

        BlockMatch(BlockStmt block, List<String> blockLines) {
            this.block = block;
            this.blockLines = blockLines;
        }
    }
}
