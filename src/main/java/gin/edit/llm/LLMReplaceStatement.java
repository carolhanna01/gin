package gin.edit.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pmw.tinylog.Logger;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.utils.Log;

//import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;

import gin.SourceFile;
import gin.SourceFileTree;
import gin.edit.Edit;
import gin.edit.llm.PromptTemplate.PromptTag;
import gin.edit.statement.StatementEdit;

import gin.edit.llm.LLMQuery;
import gin.edit.llm.OpenAILLMQuery;
import gin.edit.llm.Ollama4jLLMQuery;

public class LLMReplaceStatement extends StatementEdit {

    private static final long serialVersionUID = 1112502387236768006L;
    public String destinationFilename;
    public int destinationStatement;
	public Node destinationNode;

    private PromptTemplate promptTemplate;
    //private String modelType="OpenAI"; // Should be param from c'tor
    //private String modelType = "magicoder";
    // All strings are here: https://github.com/amithkoujalgi/ollama4j/blob/main/src/main/java/io/github/amithkoujalgi/ollama4j/core/types/OllamaModelType.java

    /**fairly rubbish approach to having something meaningful for the toString*/
    private String lastReplacement;
    private String lastPrompt;
    
    /**
     * true if this instance was constructed by a call to fromString()
     * In this case, we won't run the LLM when calling apply, but will use the value of lastReplacement instead
     */
    private boolean recreatedFromString;

    /**
     * create a random llmreplacestatement for the given sourcefile, using the provided RNG
     *
     * all this does is pick a location
     *
     * @param sourceFile to create an edit for
     * @param rng        random number generator, used to choose the target statements
     */
    public LLMReplaceStatement(SourceFile sourceFile, Random rng, PromptTemplate promptTemplate) {
        SourceFileTree sf = (SourceFileTree) sourceFile;

        destinationFilename = sourceFile.getRelativePathToWorkingDir();

        // target is in target method only
        destinationStatement = sf.getRandomBlockID(true, rng);

        this.promptTemplate = promptTemplate;

        lastReplacement = "NOT YET APPLIED";
        lastPrompt = "NOT YET APPLIED";
        recreatedFromString = false;
    }

    public LLMReplaceStatement(SourceFile sourceFile, Random rng) {
    	this(sourceFile, rng, LLMConfig.getDefaultPromptTemplate());
    }

    public LLMReplaceStatement(String destinationFilename, int destinationStatement) {
        this.destinationFilename = destinationFilename;
        this.destinationStatement = destinationStatement;

        this.lastReplacement = "NOT YET APPLIED";
        this.recreatedFromString = false;
    }

    public static Edit fromString(String description) {
    	
    	// the following will give us 5 tokens:
    	// gin.edit.llm.LLMReplaceStatement src/main/java/org/apache/commons/net/smtp/SimpleSMTPHeader.java:331\nPrompt:
    	// (prompt)
    	// --->
    	// (replacement)
    	// ""
    	String[] tokens1 = description.split("!!!", -1); 
    	
    	// split the first of these to get the filename and statement ID
    	String[] tokens2 = tokens1[0].split("\\s+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
    	String[] destTokens = tokens2[1].split(":");
        String destFilename = destTokens[0].replace("\"", "");
        int destination = Integer.parseInt(destTokens[1]);
        
        LLMReplaceStatement rval = new LLMReplaceStatement(destFilename, destination);
        rval.lastReplacement = tokens1[3];
        rval.lastPrompt = tokens1[1];
        rval.recreatedFromString = true;
        return rval;
    }

    @Override
    public SourceFile apply(SourceFile sourceFile, Object tagReplacements) {
    	List<SourceFile> l = applyMultiple(sourceFile, 5, (Map<PromptTemplate.PromptTag,String>)tagReplacements);

    	if (l.size() > 0) {
    		return l.get(0); // TODO for now, just pick the first variant provided. Later, call applyMultiple from LocalSearch instead
    	} else {
    		return null;
    	}
    }

    public List<SourceFile> applyMultiple(SourceFile sourceFile, int count, Map<PromptTemplate.PromptTag,String> tagReplacements) {

    	SourceFileTree sf = (SourceFileTree) sourceFile;

    	Node destination = sf.getNode(destinationStatement);
		this.destinationNode = destination;

    	if (destination == null) {
			Logger.info("Couldn't apply as destination is null");
    		return Collections.singletonList(sf); // targeting a deleted location just does nothing.
    	}

    	List<String> replacementStrings = new ArrayList<>();
    	List<Statement> replacementStatements = new ArrayList<>();
    	
    	if (!recreatedFromString) {
	    	// here is where the magic happens...
	    	LLMQuery llmQuery;
	
	    	// Check which model to use.
	    	if ("OpenAI".equalsIgnoreCase(LLMConfig.modelType)) {
	    		llmQuery = new OpenAILLMQuery();
	    	} else {
			String host = "http://localhost:11434";
	    		llmQuery = new Ollama4jLLMQuery(host, LLMConfig.modelType);
	    	}
	
	    	// TODO here, could call sourceFile.getSource() to provide whole class for context...
	
	    	Logger.info("Seeking replacements for:");
	    	Logger.info(destination);
	
	    	if(tagReplacements == null) {
	    		tagReplacements = new HashMap<>();
	    	}
	    	tagReplacements.put(PromptTag.COUNT, Integer.toString(count));
	    	tagReplacements.put(PromptTag.DESTINATION, destination.toString());
	    	tagReplacements.put(PromptTag.PROJECT, LLMConfig.projectName);
	
	    	String prompt = promptTemplate.replaceTags(tagReplacements);
	
	    	Logger.info("============");
	    	Logger.info("prompt:");
	    	Logger.info(prompt);
	    	lastPrompt = prompt;
	    	Logger.info("============");

			String answer = "";
			
	    	// LLM for ChatGPT
			try{
	    		answer = llmQuery.chatLLM(prompt);
			} catch (Exception e) {
				Logger.error("Error calling LLM: " + e.getMessage());
				this.lastReplacement = "LLM CALL THREW EXCEPTION " + e.getMessage();
				return Collections.emptyList();
			}
	    	// END of LLM code

			if (answer == null) {
				Logger.error("LLM returned null response");
				this.lastReplacement = "LLM RETURNED NULL RESPONSE";
				return Collections.emptyList();
			}

			Logger.info("============");
			Logger.info("Raw LLM response:");
			Logger.info(answer);
			Logger.info("============");
	
	    	// answer includes code enclosed in ```java   ....``` or ```....``` blocks
	    	// use regex to find all of these then parse into javaparser objects for return
			Pattern pattern = Pattern.compile(
					"```(?:\\s*java)?\\s*(.*?)```",
					Pattern.CASE_INSENSITIVE | Pattern.DOTALL
			);
	    	Matcher matcher = pattern.matcher(answer);
	
	    	// now parse the strings return by LLM into JavaParser Statements
			int candidatesFound = 0;

			while (matcher.find()) {
				candidatesFound++;

				String str = cleanCandidate(matcher.group(1));

				List<Statement> parsed = parseCandidate(str);

				if (parsed.isEmpty()) {
					Logger.info("Failed to parse LLM suggestion:");
					Logger.info(str);
				} else {
					for (Statement stmt : parsed) {
						replacementStrings.add(stmt.toString());
						replacementStatements.add(stmt);
					}
				}
			}

			// no code fences? try assuming that only code is returned...
			if (candidatesFound == 0 && answer != null && !answer.isBlank()) {
				Logger.info("No code fences found; trying whole LLM response as Java.");

				String str = cleanCandidate(answer);
				List<Statement> parsed = parseCandidate(str);

				for (Statement stmt : parsed) {
					replacementStrings.add(stmt.toString());
					replacementStatements.add(stmt);
				}
			}

			Logger.info("Extracted " + candidatesFound
					+ " code fence(s); "
					+ replacementStatements.size()
					+ " replacement block(s) parsed successfully.");
	
	    	int i = 1;
	    	for (String s : replacementStrings) {
	    		Logger.info("============");
	    		Logger.info("suggestion " + i++);
	    		Logger.info(s);
	    		Logger.info("============");
	    	}
	
	    	if (replacementStrings.isEmpty()) {
	    		Logger.info("============");
				String logtag = "TS" + System.nanoTime();
				Logger.info(logtag);
	    		Logger.info("No parseable replacements found. Response was:");
	    		Logger.info(answer);
	    		Logger.info("============");
	    		this.lastReplacement = "LLM GAVE NO PARSEABLE SUGGESTIONS CHECK LOG FOR " + logtag;
	    	} else {
	    		this.lastReplacement = replacementStrings.get(0);
	    	}
	    } else {
	    	try {
    			Statement stmt;
    			stmt = StaticJavaParser.parseBlock(lastReplacement);
    			replacementStrings.add(lastReplacement);
    			replacementStatements.add(stmt);
    		}
    		catch (ParseProblemException e) {
    			Logger.error("Problem parsing cached edit: " + lastReplacement);
    		}
	    }

    	List<SourceFile> variantSourceFiles = new ArrayList<>();
    	
    	// replace the original statements with the suggested ones
    	for (Statement s : replacementStatements) {
    		try {
    			variantSourceFiles.add(sf.replaceNode(destinationStatement, s));
    		} catch (ClassCastException e) { // JavaParser sometimes throws this if the statements don't match
				Logger.info("Parsed replacement could not replace destination node: "
						+ e.getMessage());
    		}
    	}

    	return variantSourceFiles;
    }

    @Override
    public String toString() {
        return this.getClass().getCanonicalName() + " \"" + destinationFilename + "\":" + destinationStatement + "\nPrompt: !!!\n" + lastPrompt +  "\n!!! --> !!!\n" + lastReplacement + "\n!!!";
    }

    public String getLastReplacement() {
        return this.lastReplacement;
    }

    public String getLastDestination() {
        //return this.destinationNode.toString();
        return (this.destinationNode == null) ? "" : this.destinationNode.toString();
    }



	private static List<Statement> parseCandidate(String str) {

		String code = cleanCandidate(str);

		// Normal/common case
		try {
			return Collections.singletonList(
					StaticJavaParser.parseBlock(code)
			);
		} catch (ParseProblemException ignored) {
		}

		// Several alternatives in one code fence
		return parseMultipleBlocks(code);
	}

	private static String cleanCandidate(String str) {
		String cleaned = str.trim();

		// Some LLMs redundantly put "java" on the first line
		// inside an already-labelled ```java code fence.
		cleaned = cleaned.replaceFirst("(?i)^java\\s*\\R", "");

		return cleaned.trim();
	}

	private static List<Statement> parseMultipleBlocks(String str) {
		List<Statement> statements = new ArrayList<>();

		int depth = 0;
		int blockStart = -1;

		boolean inString = false;
		boolean inChar = false;
		boolean inLineComment = false;
		boolean inBlockComment = false;
		boolean escaped = false;

		for (int i = 0; i < str.length(); i++) {
			char c = str.charAt(i);
			char next = (i + 1 < str.length()) ? str.charAt(i + 1) : '\0';

			// Comments
			if (inLineComment) {
				if (c == '\n') {
					inLineComment = false;
				}
				continue;
			}

			if (inBlockComment) {
				if (c == '*' && next == '/') {
					inBlockComment = false;
					i++;
				}
				continue;
			}

			// Strings / chars
			if (inString) {
				if (escaped) {
					escaped = false;
				} else if (c == '\\') {
					escaped = true;
				} else if (c == '"') {
					inString = false;
				}
				continue;
			}

			if (inChar) {
				if (escaped) {
					escaped = false;
				} else if (c == '\\') {
					escaped = true;
				} else if (c == '\'') {
					inChar = false;
				}
				continue;
			}

			if (c == '/' && next == '/') {
				inLineComment = true;
				i++;
				continue;
			}

			if (c == '/' && next == '*') {
				inBlockComment = true;
				i++;
				continue;
			}

			if (c == '"') {
				inString = true;
				continue;
			}

			if (c == '\'') {
				inChar = true;
				continue;
			}

			// Braces
			if (c == '{') {
				if (depth == 0) {
					blockStart = i;
				}
				depth++;
			} else if (c == '}') {
				if (depth > 0) {
					depth--;

					if (depth == 0 && blockStart >= 0) {
						String candidate =
								str.substring(blockStart, i + 1).trim();

						try {
							statements.add(
									StaticJavaParser.parseBlock(candidate)
							);
						} catch (ParseProblemException e) {
							Logger.info(
									"Ignoring malformed block within multi-block response:"
							);
							Logger.info(candidate);
						}

						blockStart = -1;
					}
				}
			}
		}

		return statements;
	}

}
