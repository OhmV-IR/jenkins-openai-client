package io.ohmvir.plugins.openaiclient;

import static java.util.Map.entry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIException;
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
import io.ohmvir.plugins.jenkinsaisynapse.api.tools.Tool;
import io.ohmvir.plugins.jenkinsaisynapse.api.tools.ToolArgumentDescription;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.time.Duration;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.NonNull;

@Extension
public class OpenAIClient extends ModelClient<OpenAIModelSettings, OpenAIClientSettings> {
    private static final Logger LOGGER = Logger.getLogger(OpenAIClient.class.getName());
    static final long DEFAULT_TIMEOUT_SECONDS = 120L;
    static final String FAILED_TOOL_CALL_OUTPUT = "Error: the tool call failed and produced no output.";

    private static final Map<Class<?>, String> JSON_SCHEMA_TYPES = Map.ofEntries(
            entry(String.class, "string"),
            entry(char.class, "string"),
            entry(Character.class, "string"),
            entry(int.class, "integer"),
            entry(Integer.class, "integer"),
            entry(long.class, "integer"),
            entry(Long.class, "integer"),
            entry(short.class, "integer"),
            entry(Short.class, "integer"),
            entry(byte.class, "integer"),
            entry(Byte.class, "integer"),
            entry(float.class, "number"),
            entry(Float.class, "number"),
            entry(double.class, "number"),
            entry(Double.class, "number"),
            entry(boolean.class, "boolean"),
            entry(Boolean.class, "boolean"));

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
        // previous_response_id makes the API replay every earlier input item, so only the new ones are sent.
        // Instructions, tools and sampling settings are not carried over and are always sent.
        int firstNewInput = state.previousResponseId == null ? 0 : state.inputCount;

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
        String reasoningEffort = null;

        for (int i = 0; i < turnInputs.size(); i++) {
            boolean isNew = i >= firstNewInput;
            switch (turnInputs.get(i)) {
                case SystemPromptContent value -> append(instructions, value.getSystemPrompt());
                case InputSkillContent value ->
                    append(instructions, value.getSkill().getSkillText());
                case InputTextContent value when isNew -> addMessage(input, "user", value.getText());
                case InputImageContent value
                when isNew -> {
                    String image = value.getImageBase64();
                    if (image != null) input.add(message("user", List.of(imagePart(image))));
                }
                case InputFileContent value
                when isNew -> {
                    if (value.getFileData() != null) input.add(message("user", List.of(filePart(value))));
                }
                case ToolCallResponseContent value
                when isNew ->
                    input.add(Map.of(
                            "type",
                            "function_call_output",
                            "call_id",
                            value.getToolUseId(),
                            "output",
                            toolOutput(value)));
                case InputConversationContent value when isNew -> appendConversation(input, value);
                case InputToolContent value -> tools.add(tool(value));
                case MaxOutputTokensContent value -> maxOutputTokens = value.getMaxOutputTokens();
                case TemperatureContent value -> temperature = value.getTemperature();
                case TopPContent value -> topP = value.getTopP();
                case ThinkingLevelContent value -> reasoningEffort = reasoningEffort(value.getThinkingLevel());
                // StopSequencesContent is not handled: the Responses API has no stop parameter
                default -> {}
            }
        }

        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(configuration.getModelName())
                .putAdditionalBodyProperty(
                        "input", JsonValue.from(input.isEmpty() ? List.of(message("user", "")) : input));
        if (!instructions.isEmpty()) builder.instructions(instructions.toString());
        if (state.previousResponseId != null) builder.previousResponseId(state.previousResponseId);
        long defaultMaxTokens = clientConfiguration == null
                ? OpenAIClientSettings.DEFAULT_MAX_TOKENS
                : clientConfiguration.getDefaultMaxTokens();
        builder.maxOutputTokens(maxOutputTokens != null ? maxOutputTokens : defaultMaxTokens);
        if (temperature != null) builder.temperature(validateRange("temperature", temperature, 0, 2));
        if (topP != null) builder.topP(validateRange("top_p", topP, 0, 1));
        Map<String, Object> reasoning = new LinkedHashMap<>();
        if (reasoningEffort != null) reasoning.put("effort", reasoningEffort);
        // Reasoning summaries are opt-in, so they are only requested when the caller wants ThinkingContent back
        if (request.getOutputClasses().contains(ThinkingContent.class)) reasoning.put("summary", "auto");
        if (!reasoning.isEmpty()) builder.putAdditionalBodyProperty("reasoning", JsonValue.from(reasoning));
        if (!tools.isEmpty()) builder.putAdditionalBodyProperty("tools", JsonValue.from(tools));

        var client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(configuration.getApiBaseUrl())
                .timeout(timeout(clientConfiguration))
                .build();
        try {
            Response response = client.responses().create(builder.build());
            response.error().ifPresent(error -> {
                throw new IllegalStateException(
                        "OpenAI API response failed with " + error.code() + ": " + error.message());
            });
            List<ModelOutput> outputs = new ArrayList<>();
            boolean toolCall = false;
            boolean refusal = false;
            for (ResponseOutputItem item : response.output()) {
                if (item.isMessage()) {
                    for (var content : item.asMessage().content()) {
                        if (content.isOutputText())
                            outputs.add(
                                    new OutputTextContent(content.asOutputText().text()));
                        else if (content.isRefusal()) {
                            outputs.add(
                                    new OutputTextContent(content.asRefusal().refusal()));
                            refusal = true;
                        }
                    }
                } else if (item.isFunctionCall()) {
                    var call = item.asFunctionCall();
                    outputs.add(new ToolCallContent(call.callId(), parseArguments(call.arguments()), call.name()));
                    toolCall = true;
                } else if (item.isReasoning()) {
                    item.asReasoning().summary().forEach(s -> outputs.add(new ThinkingContent(s.text())));
                }
            }
            response.usage()
                    .ifPresent(usage -> outputs.add(new TokenUtilizationContent(
                            usage.inputTokens(),
                            usage.outputTokens(),
                            usage.inputTokensDetails().cachedTokens())));
            outputs.add(new FinishReasonContent(finishReason(response, toolCall, refusal)));
            state.inputCount = turnInputs.size();
            state.previousResponseId = response.id();
            state.hadToolCalls = toolCall;
            return filterOutputs(request, outputs);
        } catch (OpenAIServiceException e) {
            throw new IllegalStateException(
                    "OpenAI API request failed with HTTP " + e.statusCode() + ": " + e.body(), e);
        } catch (OpenAIException e) {
            throw new IllegalStateException("OpenAI API request failed: " + e.getMessage(), e);
        } finally {
            client.close();
        }
    }

    static Duration timeout(OpenAIClientSettings clientConfiguration) {
        Long seconds = clientConfiguration == null ? null : clientConfiguration.getTimeoutSeconds();
        // 0 disables the timeout, as documented on the client settings form
        return Duration.ofSeconds(seconds == null ? DEFAULT_TIMEOUT_SECONDS : seconds);
    }

    static String reasoningEffort(ModelThinkingLevel level) {
        if (level == null) return null;
        return switch (level) {
            // Reasoning can't be fully disabled on every OpenAI reasoning model; low is the lowest effort all accept
            case OFF, LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH, EXTRA_HIGH, MAX -> "high";
            case ON -> null;
        };
    }

    static ModelFinishReason finishReason(Response response, boolean toolCall, boolean refusal) {
        Optional<Response.IncompleteDetails> incomplete = response.incompleteDetails();
        if (incomplete.isPresent()) {
            return incomplete
                            .flatMap(Response.IncompleteDetails::reason)
                            .filter(Response.IncompleteDetails.Reason.CONTENT_FILTER::equals)
                            .isPresent()
                    ? ModelFinishReason.SAFEGUARD
                    : ModelFinishReason.TOKEN_CAP;
        }
        if (toolCall) return ModelFinishReason.TOOL_CALLS;
        if (refusal) return ModelFinishReason.SAFEGUARD;
        return ModelFinishReason.STOP;
    }

    private static JsonObject parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(arguments);
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            // Fall through, the failed tool call is reported back to the model so it can retry
        }
        LOGGER.log(Level.WARNING, "OpenAI returned tool call arguments that are not a JSON object: {0}", arguments);
        return new JsonObject();
    }

    private static String toolOutput(ToolCallResponseContent value) {
        if (value.getResponseContent() != null) return value.getResponseContent();
        return value.isSuccessful() ? "" : FAILED_TOOL_CALL_OUTPUT;
    }

    private static void append(StringBuilder target, String text) {
        if (text != null && !text.isBlank()) {
            if (!target.isEmpty()) target.append("\n\n");
            target.append(text);
        }
    }

    private static void addMessage(List<Object> input, String role, String text) {
        if (text != null) input.add(message(role, text));
    }

    private static Map<String, Object> message(String role, Object content) {
        return Map.of("role", role, "content", content);
    }

    private static Map<String, Object> imagePart(String value) {
        return Map.of("type", "input_image", "image_url", value, "detail", "auto");
    }

    private static Map<String, Object> filePart(InputFileContent value) {
        String type = value.getContentType() == null ? "text/plain" : value.getContentType();
        String data = Base64.getEncoder().encodeToString(value.getFileData());
        return Map.of(
                "type",
                "input_file",
                "file_data",
                "data:" + type + ";base64," + data,
                "filename",
                value.getFileId() == null ? "attachment" : value.getFileId());
    }

    private static Map<String, Object> tool(InputToolContent value) {
        Tool tool = value.getTool();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolArgumentDescription argument : tool.getArguments()) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", JSON_SCHEMA_TYPES.getOrDefault(argument.getType(), "string"));
            if (argument.getDescription() != null) property.put("description", argument.getDescription());
            properties.put(argument.getName(), property);
            if (argument.isRequired()) required.add(argument.getName());
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("type", "function");
        function.put("name", tool.getName());
        if (tool.getDescription() != null) function.put("description", tool.getDescription());
        function.put("parameters", parameters);
        // Strict mode rejects schemas where any property is missing from required
        function.put("strict", required.size() == properties.size());
        return function;
    }

    private static void appendConversation(List<Object> input, InputConversationContent value) {
        if (value.getConversation() == null) return;
        value.getConversation().getConversation().forEach(content -> {
            if (content instanceof InputTextContent text) addMessage(input, "user", text.getText());
            else if (content instanceof OutputTextContent text) addMessage(input, "assistant", text.getText());
        });
    }

    private static double validateRange(String name, double value, double min, double max) {
        if (value < min || value > max)
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        return value;
    }

    private static List<ModelOutput> filterOutputs(ModelRequest request, List<ModelOutput> outputs) {
        Set<Class<? extends ModelOutput>> requested = request.getOutputClasses();
        if (requested == null || requested.isEmpty()) return outputs;
        return outputs.stream()
                .filter(output ->
                        output instanceof ToolCallContent || requested.stream().anyMatch(c -> c.isInstance(output)))
                .toList();
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ModelClient<?, ?>> {
        @Override
        public @NonNull String getDisplayName() {
            return "OpenAI Client";
        }
    }
}
