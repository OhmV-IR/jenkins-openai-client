package io.ohmvir.plugins.openaiclient;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import hudson.Extension;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.*;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Extension
public class OpenAIModelDataRetriever extends ModelDataRetriever<OpenAIModelSettings> {
    public OpenAIModelDataRetriever() {
        super(OpenAIModelSettings.class);
    }

    @Override
    public ModelData retrieveFromConfiguration(OpenAIModelSettings configuration)
            throws IOException, InterruptedException {
        String apiKey = SecretsUtils.getSecretText(configuration.getApiKeyCredentialsId(), null);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("OpenAI API key credential could not be resolved for model configuration: "
                    + configuration.getModelId());
        }
        String modelName = configuration.getModelName();
        if (modelName == null || modelName.isBlank()) {
            throw new IOException("Model name is required for model configuration: " + configuration.getModelId());
        }

        var client = OpenAIOkHttpClient.builder().apiKey(apiKey).baseUrl(configuration.getApiBaseUrl())
                .timeout(Duration.ofSeconds(15)).build();
        try {
            var model = client.models().retrieve(modelName);
            ModelData data = new ModelData(configuration);
            List<ModelCapability> capabilities = new ArrayList<>(List.of(
                    ModelCapability.TOOLS,
                    ModelCapability.SKILLS,
                    ModelCapability.ADJUSTABLE_SYSTEM_PROMPT,
                    ModelCapability.CONVERSATIONS,
                    ModelCapability.OUTPUT_TOKEN_LIMITING,
                    ModelCapability.PREMATURE_STOP,
                    ModelCapability.CUSTOM_STOP_SEQUENCES,
                    ModelCapability.CUSTOM_TEMPERATURE,
                    ModelCapability.CUSTOM_TOP_P,
                    ModelCapability.THINKING,
                    ModelCapability.TOKEN_USAGE_METRICS));
            data.setCapabilities(capabilities);
            data.setSupportedThinkingLevels(List.of(
                    ModelThinkingLevel.OFF,
                    ModelThinkingLevel.LOW,
                    ModelThinkingLevel.MEDIUM,
                    ModelThinkingLevel.HIGH,
                    ModelThinkingLevel.EXTRA_HIGH,
                    ModelThinkingLevel.MAX));
            data.setInputs(List.of(ModelInputType.TEXT, ModelInputType.IMAGE, ModelInputType.FILE));
            data.setOutputs(List.of(ModelOutputType.UNSTRUCTURED_TEXT, ModelOutputType.STRUCTURED_OUTPUT));
            data.setContextWindow(0L);
            data.setMaxOutputTokens(null);
            data.setMaxInputTokens(null);
            data.setMaxTemperature(2.0d);
            return data;
        } catch (Exception e) {
            throw new IOException("Failed to retrieve model info for '" + modelName + "' from OpenAI API: "
                    + e.getMessage(), e);
        } finally {
            client.close();
        }
    }
}
