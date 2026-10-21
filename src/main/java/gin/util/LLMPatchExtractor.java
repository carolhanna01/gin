package gin.util;

import com.opencsv.CSVReaderHeaderAware;
import com.opencsv.CSVReaderHeaderAwareBuilder;
import com.opencsv.RFC4180Parser;
import com.opencsv.RFC4180ParserBuilder;
import com.opencsv.exceptions.CsvValidationException;
import com.sampullara.cli.Args;
import com.sampullara.cli.Argument;
import org.pmw.tinylog.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Temporary PatchExtractor for LocalSearchRuntime CSVs whose Patch field is the
 * classic diff emitted for an LLMReplaceStatement.
 *
 * The CSV MethodName field used by LocalSearchRuntime contains the fully qualified
 * class name twice followed by the method signature, for example:
 *
 *   a.b.C.a.b.C.method(int)
 *
 * We use that to infer both a/b/C.java and method, relative to the Java source
 * root supplied with -d.
 */
public class LLMPatchExtractor {

    @Argument(alias = "pl", description = "LocalSearchRuntime results CSV", required = true)
    protected File patchFile;

    @Argument(alias = "d", description = "Java source root (for example src/main/java)", required = true)
    protected File sourceRoot;

    /**
     * Unique patches in CSV order.  Value holds metadata from the first row in
     * which that patch occurred.
     */
    private final Map<String, PatchRecord> patches = new LinkedHashMap<>();

    LLMPatchExtractor(String[] args) {
        Args.parseOrExit(this, args);
        processSamplerResults();
        analysePatches();
    }

    public static void main(String[] args) {
        new LLMPatchExtractor(args);
    }

    private void processSamplerResults() {
        try {
            RFC4180Parser parser = new RFC4180ParserBuilder().build();
            CSVReaderHeaderAware reader = (CSVReaderHeaderAware)
                    new CSVReaderHeaderAwareBuilder(new FileReader(patchFile))
                            .withCSVParser(parser)
                            .build();

            Map<String, String> data = reader.readMap();
            if (data != null && !data.containsKey("Patch")) {
                throw new ParseException("\"Patch\" field is required in the input file.", 0);
            }

            int uniqueIndex = 0;
            while (data != null) {
                String patch = data.getOrDefault("Patch", "");

                if (!patches.containsKey(patch)) {
                    String id = data.getOrDefault("PatchIndex", Integer.toString(uniqueIndex));
                    String method = data.getOrDefault("MethodName", "");
                    if (method == null || method.isBlank()) {
                        throw new ParseException("\"MethodName\" field is required for LLM diff reconstruction.", 0);
                    }
                    MethodTarget target = targetFromMethodName(method);
                    patches.put(patch, new PatchRecord(id, method, target));
                    uniqueIndex++;
                } else {
                    Logger.info("Skipping duplicate patch; first seen as #"
                            + patches.get(patch).id);
                }

                data = reader.readMap();
            }
            reader.close();

        } catch (IOException | ParseException | CsvValidationException e) {
            throw new RuntimeException("Could not read patch CSV", e);
        }
    }

    private void analysePatches() {
        for (Map.Entry<String, PatchRecord> entry : patches.entrySet()) {
            String patch = entry.getKey();
            PatchRecord record = entry.getValue();

            Logger.info("Processing patch #" + record.id + "...");
            if (!patch.isBlank()) {
                Logger.info(patch);
            }

            try {
                String outputDir = patchFile.getName() + "_patch_" + record.id;
                Logger.info("Writing to dir " + outputDir);

                File source = new File(sourceRoot, record.target.relativeSourcePath);
                if (!source.isFile()) {
                    throw new IllegalArgumentException(
                            "Inferred source file does not exist: " + source
                                    + " (from MethodName: " + record.methodName + ")");
                }

                Logger.info("Inferred source: " + source);
                Logger.info("Inferred method: " + record.target.simpleMethodName);

                String[] args = new String[]{
                        "-f", source.getPath(),
                        "-m", record.target.simpleMethodName,
                        "-od", outputDir,
                        "-p", patch.isBlank() ? "|" : patch
                };

                LLMPatchAnalyser analyser = new LLMPatchAnalyser(args);
                analyser.analyse();

            } catch (Exception e) {
                Logger.error("Failed to process patch #" + record.id + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Convert Gin's LocalSearchRuntime MethodName format
     *
     *   package.Class.package.Class.method(args)
     *
     * into package/Class.java plus the simple method name.
     */
    private static MethodTarget targetFromMethodName(String methodName) {
        String beforeArgs = methodName;
        int paren = beforeArgs.indexOf('(');
        if (paren >= 0) {
            beforeArgs = beforeArgs.substring(0, paren);
        }

        String[] parts = beforeArgs.split("\\.");

        // Expected token layout is C.C.method, where C is the sequence of
        // package + class-name tokens.  Therefore the token count is 2n + 1.
        if (parts.length < 3 || parts.length % 2 == 0) {
            throw new IllegalArgumentException(
                    "Unexpected MethodName format: " + methodName);
        }

        int classTokenCount = (parts.length - 1) / 2;
        for (int i = 0; i < classTokenCount; i++) {
            if (!parts[i].equals(parts[classTokenCount + i])) {
                throw new IllegalArgumentException(
                        "Could not identify repeated class name in MethodName: " + methodName);
            }
        }

        StringBuilder relativePath = new StringBuilder();
        for (int i = 0; i < classTokenCount; i++) {
            if (i > 0) {
                relativePath.append(File.separatorChar);
            }
            relativePath.append(parts[i]);
        }
        relativePath.append(".java");

        String simpleMethodName = parts[parts.length - 1];
        return new MethodTarget(relativePath.toString(), simpleMethodName);
    }

    private record MethodTarget(String relativeSourcePath, String simpleMethodName) {
    }

    private record PatchRecord(String id, String methodName, MethodTarget target) {
    }
}
