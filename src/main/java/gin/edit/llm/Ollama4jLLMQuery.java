package gin.edit.llm;

import java.io.IOException;

import io.github.ollama4j.Ollama;
import io.github.ollama4j.utils.OptionsBuilder;
import io.github.ollama4j.models.response.OllamaResult;
import io.github.ollama4j.exceptions.OllamaException;
import io.github.ollama4j.models.generate.OllamaGenerateRequest;
import io.github.ollama4j.models.request.ThinkMode;

//import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
//import io.github.amithkoujalgi.ollama4j.core.models.OllamaResult;
//import io.github.amithkoujalgi.ollama4j.core.types.OllamaModelType;
//import io.github.amithkoujalgi.ollama4j.core.utils.PromptBuilder;
//import io.github.amithkoujalgi.ollama4j.core.utils.OptionsBuilder;
//import io.github.amithkoujalgi.ollama4j.core.exceptions.OllamaBaseException;

import gin.edit.llm.LLMQuery;

public class Ollama4jLLMQuery implements LLMQuery {
    private final Ollama ollamaAPI;
    private final String modelType;

    private static final String OLLAMA_SERVER = System.getenv("OLLAMA_SERVER");
    private static final String OLLAMA_API_KEY = System.getenv("OLLAMA_API_KEY");
    private static final String SYSTEM_TEMPLATE =
        "You are a top expert Java performance engineer. " +
        "Your task is to rewrite code to be faster while preserving correctness. " +
        "You have a strict time budget of %d seconds per request. " +
        "Only output code in ```java section, no explanations.";

    // c'tor
    public Ollama4jLLMQuery(String ollamaServerHost, String modelType) {
        this.modelType = modelType;

        if (OLLAMA_SERVER == null || OLLAMA_SERVER.isBlank() || OLLAMA_API_KEY == null || OLLAMA_API_KEY.isBlank()) {
        	this.ollamaAPI = new Ollama(ollamaServerHost);
	} else {
		this.ollamaAPI = new Ollama(OLLAMA_SERVER);
		this.ollamaAPI.setBearerAuth(OLLAMA_API_KEY);
	}
        ollamaAPI.setRequestTimeoutSeconds(LLMConfig.timeoutInSeconds);
        //ollamaAPI.setVerbose(true);
    }

    private String buildSystemPrompt() {
        return String.format(SYSTEM_TEMPLATE, LLMConfig.timeoutInSeconds);
    }

    @Override
    public boolean testServerReachable() {
        boolean ret = false;
        try {
		ret = ollamaAPI.ping();
	 } catch (OllamaException e) {
                // handle the exception
                e.printStackTrace();
	}
        return ret;
    }

    @Override
    public String chatLLM(String prompt) {
        try {

                System.out.println(
                    "[INFO] Ollama request: model=" + this.modelType
                    + ", timeout=" + LLMConfig.timeoutInSeconds + "s"
                    + ", promptLength=" + prompt.length()
                );
                System.out.println("[INFO] ollamaAPI=" + ollamaAPI);
                System.out.println(
                    "[INFO] ollamaAPI class=" + ollamaAPI.getClass().getName()
                    + ", instance=" + System.identityHashCode(ollamaAPI)
                );
                // code that might throw OllamaBaseException
                OllamaGenerateRequest req = OllamaGenerateRequest.builder()
					.withModel(this.modelType)
                                        .withSystem(this.buildSystemPrompt()) // Will pull the current timeout setup
                                        .withPrompt(prompt)
//					.withContext("You are a Java Optimization Engine that creates a valid faster code")
                                        .withKeepAlive("10m") // For performance, but not permenatly. Eventually will be released
                                        .withRaw(false)
                                        .withThink(ThinkMode.DISABLED)
                                        .withStreaming(false)
					.build();
                OllamaResult result = ollamaAPI.generate(req, null);
                    //ollamaAPI.ask(modelType, prompt, new OptionsBuilder().build());
                return result.getResponse();
        } catch (OllamaException e) {
                // handle the exception
                e.printStackTrace();
        //} catch (IOException | InterruptedException e) {
                // handle the IOException
        //        e.printStackTrace();
        }
        return "";
    }
}


