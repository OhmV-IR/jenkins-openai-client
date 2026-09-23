package io.ohmvir.plugins.openaiclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import hudson.Extension;
import hudson.model.Descriptor;
import io.ohmvir.plugins.jenkinsaisynapse.api.client.ModelClient;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.*;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.*;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.*;
import io.ohmvir.plugins.jenkinsaisynapse.api.tools.ToolArgumentDescription;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.time.Duration;
import java.util.*;
import org.jspecify.annotations.NonNull;

@Extension
public class OpenAIClient extends ModelClient<OpenAIModelSettings, OpenAIClientSettings> {
    private static final class RequestState {
        int inputCount = -1;
        String previousResponseId;
        boolean hadToolCalls;
    }

    private final Map<ModelRequest, RequestState> requestStates = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    protected List<ModelOutput> takeStepImpl(
            ModelData modelData,
            OpenAIModelSettings configuration,
            OpenAIClientSettings clientConfiguration,
            ModelRequest request,
            List<ModelInput> turnInputs) {
        RequestState state = requestStates.computeIfAbsent(request, ignored -> new RequestState());
        if (state.inputCount == turnInputs.size() && !state.hadToolCalls) return List.of();
        state.inputCount = turnInputs.size();

        String apiKey = SecretsUtils.getSecretText(configuration.getApiKeyCredentialsId(), null);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key credential could not be resolved for model configuration: "
                    + configuration.getModelId());
        }

        StringBuilder instructions = new StringBuilder();
        List<Object> input = new ArrayList<>();
        List<Object> tools = new ArrayList<>();
        Long maxOutputTokens = null;
        Double temperature = null;
        Double topP = null;
        String stop = null;
        String reasoningEffort = null;

        for (ModelInput modelInput : turnInputs) {
            switch (modelInput) {
                case SystemPromptContent value -> append(instructions, value.getSystemPrompt());
                case InputSkillContent value -> append(instructions, value.getSkill().getSkillText());
                case InputTextContent value -> input.add(message("user", value.getText()));
                case InputImageContent value -> input.add(message("user", imagePart(value.getImageBase64())));
                case InputFileContent value -> input.add(message("user", filePart(value)));
                case ToolCallResponseContent value -> input.add(Map.of(
                        "type", "function_call_output",
                        "call_id", value.getToolUseId(),
                        "output", value.getResponseContent() == null ? "" : value.getResponseContent()));
                case InputToolContent value -> tools.add(tool(value));
                case InputConversationContent value -> appendConversation(input, value);
                case MaxOutputTokensContent value -> maxOutputTokens = value.getMaxOutputTokens();
                case TemperatureContent value -> temperature = value.getTemperature();
                case TopPContent value -> topP = value.getTopP();
                case StopSequencesContent value -> stop = String.join("\n", value.getStopPhrases());
                case ThinkingLevelContent value -> reasoningEffort = switch (value.getThinkingLevel()) {
                    case LOW -> "low";
                    case MEDIUM -> "medium";
                    case HIGH, EXTRA_HIGH, MAX -> "high";
                    default -> null;
                };
                default -> { }
            }
        }

        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(configuration.getModelName())
                .putAdditionalBodyProperty("input", JsonValue.from(input.isEmpty() ? List.of(message("user", "")) : input));
        if (!instructions.isEmpty()) builder.instructions(instructions.toString());
        if (state.previousResponseId != null) builder.previousResponseId(state.previousResponseId);
        long defaultMaxTokens = clientConfiguration == null
            ? OpenAIClientSettings.DEFAULT_MAX_TOKENS
            : clientConfiguration.getDefaultMaxTokens();
        builder.maxOutputTokens(maxOutputTokens != null ? maxOutputTokens : defaultMaxTokens);
        if (temperature != null) builder.temperature(validateRange("temperature", temperature, 0, 2));
        if (topP != null) builder.topP(validateRange("top_p", topP, 0, 1));
        if (stop != null && !stop.isBlank()) builder.putAdditionalBodyProperty("stop", JsonValue.from(stop));
        if (reasoningEffort != null) builder.putAdditionalBodyProperty("reasoning", JsonValue.from(Map.of("effort", reasoningEffort)));
        if (!tools.isEmpty()) builder.putAdditionalBodyProperty("tools", JsonValue.from(tools));

        long timeout = clientConfiguration == null || clientConfiguration.getTimeoutSeconds() <= 0
                ? 120L : clientConfiguration.getTimeoutSeconds();
        var client = OpenAIOkHttpClient.builder().apiKey(apiKey).baseUrl(configuration.getApiBaseUrl())
                .timeout(Duration.ofSeconds(timeout)).build();
        try {
            Response response = client.responses().create(builder.build());
            state.previousResponseId = response.id();
            List<ModelOutput> outputs = new ArrayList<>();
            boolean toolCall = false;
            for (ResponseOutputItem item : response.output()) {
                if (item.isMessage()) {
                    for (var content : item.asMessage().content()) {
                        if (content.isOutputText()) outputs.add(new OutputTextContent(content.asOutputText().text()));
                        else if (content.isRefusal()) outputs.add(new OutputTextContent(content.asRefusal().refusal()));
                    }
                } else if (item.isFunctionCall()) {
                    var call = item.asFunctionCall();
                    JsonObject arguments = JsonParser.parseString(call.arguments()).getAsJsonObject();
                    outputs.add(new ToolCallContent(call.callId(), arguments, call.name()));
                    toolCall = true;
                } else if (item.isReasoning()) {
                    item.asReasoning().summary().forEach(s -> outputs.add(new ThinkingContent(s.text())));
                }
            }
            response.usage().ifPresent(usage -> outputs.add(new TokenUtilizationContent(
                    usage.inputTokens(), usage.outputTokens(), usage.inputTokensDetails().cachedTokens())));
            response.incompleteDetails().ifPresent(ignored -> outputs.add(new FinishReasonContent(ModelFinishReason.TOKEN_CAP)));
            state.hadToolCalls = toolCall;
            return filterOutputs(request, outputs);
        } catch (OpenAIServiceException e) {
            state.hadToolCalls = false;
            throw new IllegalStateException("OpenAI API request failed with HTTP " + e.statusCode() + ": " + e.body(), e);
        } finally {
            client.close();
        }
    }

    private static void append(StringBuilder target, String text) {
        if (text != null && !text.isBlank()) {
            if (!target.isEmpty()) target.append("\n\n");
            target.append(text);
        }
    }

    private static Map<String, Object> message(String role, Object content) {
        return Map.of("role", role, "content", content);
    }

    private static Map<String, Object> imagePart(String value) {
        return Map.of("type", "input_image", "image_url", value == null ? "" : value);
    }

    private static Map<String, Object> filePart(InputFileContent value) {
        String type = value.getContentType() == null ? "text/plain" : value.getContentType();
        String data = Base64.getEncoder().encodeToString(value.getFileData());
        return Map.of("type", "input_file", "file_data", "data:" + type + ";base64," + data,
                "filename", value.getFileId() == null ? "attachment" : value.getFileId());
    }

    private static Map<String, Object> tool(InputToolContent value) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolArgumentDescription argument : value.getTool().getArguments()) {
            String type = argument.getType() == String.class ? "string" : argument.getType() == boolean.class ? "boolean"
                    : Number.class.isAssignableFrom(argument.getType()) || argument.getType().isPrimitive() ? "number" : "object";
            properties.put(argument.getName(), Map.of("type", type, "description", argument.getDescription()));
            if (argument.isRequired()) required.add(argument.getName());
        }
        return Map.of("type", "function", "name", value.getTool().getName(), "description", value.getTool().getDescription(),
                "parameters", Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false),
                "strict", true);
    }

    private static void appendConversation(List<Object> input, InputConversationContent value) {
        if (value.getConversation() == null) return;
        value.getConversation().getConversation().forEach(content -> {
            if (content instanceof InputTextContent text) input.add(message("user", text.getText()));
            else if (content instanceof OutputTextContent text) input.add(message("assistant", text.getText()));
        });
    }

    private static double validateRange(String name, double value, double min, double max) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        return value;
    }

    private static List<ModelOutput> filterOutputs(ModelRequest request, List<ModelOutput> outputs) {
        Set<Class<ModelOutput>> requested = request.getOutputClasses();
        if (requested == null || requested.isEmpty()) return outputs;
        return outputs.stream().filter(output -> output instanceof ToolCallContent || requested.stream().anyMatch(c -> c.isInstance(output))).toList();
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ModelClient<?, ?>> {
        @Override public @NonNull String getDisplayName() { return "OpenAI Client"; }
    }
}
