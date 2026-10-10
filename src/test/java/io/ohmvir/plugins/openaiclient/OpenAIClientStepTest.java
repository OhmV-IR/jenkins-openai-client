package io.ohmvir.plugins.openaiclient;

import static io.ohmvir.plugins.openaiclient.MockOpenAIServer.functionCall;
import static io.ohmvir.plugins.openaiclient.MockOpenAIServer.reasoning;
import static io.ohmvir.plugins.openaiclient.MockOpenAIServer.refusalMessage;
import static io.ohmvir.plugins.openaiclient.MockOpenAIServer.response;
import static io.ohmvir.plugins.openaiclient.MockOpenAIServer.textMessage;
import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import hudson.util.Secret;
import io.ohmvir.plugins.jenkinsaisynapse.api.ModelConversation;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.*;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelFinishReason;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelThinkingLevel;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.*;
import io.ohmvir.plugins.jenkinsaisynapse.api.skills.SkillData;
import io.ohmvir.plugins.jenkinsaisynapse.api.tools.Tool;
import io.ohmvir.plugins.jenkinsaisynapse.api.tools.ToolArgumentDescription;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Runs {@link OpenAIClient} steps against a mock Responses API to check the request bodies and output mapping.
 */
@WithJenkins
class OpenAIClientStepTest {
    static final String CREDENTIALS_ID = "openai-key";

    private MockOpenAIServer server;
    private OpenAIModelSettings settings;
    private OpenAIClientSettings clientSettings;
    private OpenAIClient client;

    public static class WeatherTool extends Tool {
        public WeatherTool() throws NoSuchMethodException {}

        @Override
        public String getName() {
            return "weather";
        }

        @Override
        public String getDescription() {
            return "Gets the weather";
        }

        @Override
        public List<ToolArgumentDescription> getArguments() {
            return List.of(
                    new ToolArgumentDescription("city", String.class, "City name", true),
                    new ToolArgumentDescription("days", int.class, "Days to forecast", false),
                    new ToolArgumentDescription("metric", boolean.class, "Use metric units", true),
                    new ToolArgumentDescription("threshold", double.class, null, true));
        }

        public String weather(String city, int days, boolean metric, double threshold) {
            return "sunny";
        }
    }

    public static class EchoTool extends Tool {
        public EchoTool() throws NoSuchMethodException {}

        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public String getDescription() {
            return null;
        }

        @Override
        public List<ToolArgumentDescription> getArguments() {
            return List.of(new ToolArgumentDescription("text", String.class, "Text to echo", true));
        }

        public String echo(String text) {
            return text;
        }
    }

    static void addApiKeyCredential() throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL, CREDENTIALS_ID, "OpenAI", Secret.fromString("sk-test")));
        SystemCredentialsProvider.getInstance().save();
    }

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        addApiKeyCredential();
        server = new MockOpenAIServer();
        settings = new OpenAIModelSettings("gpt-4o-mini", CREDENTIALS_ID, server.baseUrl());
        clientSettings = new OpenAIClientSettings(30L, 1024L);
        client = new OpenAIClient();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private List<ModelOutput> step(ModelRequest request) {
        return client.takeStep(null, settings, clientSettings, request, request.getModelInputs());
    }

    private static ModelRequest request(ModelInput... inputs) {
        ModelRequest request = new ModelRequest();
        for (ModelInput input : inputs) request.addInput(input);
        return request;
    }

    private static <T> T only(List<ModelOutput> outputs, Class<T> type) {
        List<T> matches =
                outputs.stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, matches.size(), "Expected exactly one " + type.getSimpleName() + " in " + outputs);
        return matches.get(0);
    }

    @Test
    void textRequestBuildsResponsesApiBody() {
        server.enqueue(response("resp_1", textMessage("Hello there")));
        SkillData skill = new SkillData("skill", "A skill", null, null, null, null, "Skill text", Map.of());
        ModelRequest request = request(
                new SystemPromptContent("Be helpful"),
                new InputSkillContent(skill),
                new InputTextContent("Hi"),
                new StopSequencesContent(List.of("END", "STOP")));

        List<ModelOutput> outputs = step(request);

        MockOpenAIServer.RecordedRequest sent = server.lastRequest();
        assertEquals("POST", sent.method());
        assertEquals("/v1/responses", sent.path());
        assertEquals("Bearer sk-test", sent.authorization());
        JsonObject body = sent.json();
        assertEquals("gpt-4o-mini", body.get("model").getAsString());
        assertEquals("Be helpful\n\nSkill text", body.get("instructions").getAsString());
        assertEquals(1024, body.get("max_output_tokens").getAsLong());
        JsonArray input = body.getAsJsonArray("input");
        assertEquals(1, input.size());
        assertEquals("user", input.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("Hi", input.get(0).getAsJsonObject().get("content").getAsString());
        assertFalse(body.has("stop"), "The Responses API rejects a stop parameter");
        assertFalse(body.has("previous_response_id"));
        assertFalse(body.has("temperature"));
        assertFalse(body.has("reasoning"));
        assertFalse(body.has("tools"));

        assertEquals("Hello there", only(outputs, OutputTextContent.class).getText());
        TokenUtilizationContent usage = only(outputs, TokenUtilizationContent.class);
        assertEquals(10, usage.getInputTokensUsed());
        assertEquals(5, usage.getOutputTokensUsed());
        assertEquals(2, usage.getCachedTokensUsed());
        assertEquals(
                ModelFinishReason.STOP, only(outputs, FinishReasonContent.class).getReason());
    }

    @Test
    void stepAfterFinalAnswerReturnsNothingWithoutCallingApi() {
        server.enqueue(response("resp_1", textMessage("Done")));
        ModelRequest request = request(new InputTextContent("Hi"));

        assertFalse(step(request).isEmpty());
        assertTrue(step(request).isEmpty());
        assertEquals(1, server.requests().size());
    }

    @Test
    void toolCallRoundTripSendsOnlyNewInputs() throws Exception {
        server.enqueue(response("resp_1", functionCall("call_1", "weather", "{\"city\":\"Paris\",\"metric\":true}")));
        server.enqueue(response("resp_2", textMessage("It is sunny")));
        WeatherTool tool = new WeatherTool();
        ModelRequest request =
                request(new SystemPromptContent("Be helpful"), new InputToolContent(tool), new InputTextContent("Hi"));

        List<ModelOutput> first = step(request);
        ToolCallContent call = only(first, ToolCallContent.class);
        assertEquals("call_1", call.getToolUseId());
        assertEquals("weather", call.getName());
        assertEquals("Paris", call.getToolArguments().get("city").getAsString());
        assertTrue(call.getToolArguments().get("metric").getAsBoolean());
        assertEquals(
                ModelFinishReason.TOOL_CALLS,
                only(first, FinishReasonContent.class).getReason());

        request.addInput(new ToolCallResponseContent("call_1", true, "sunny", tool));
        List<ModelOutput> second = step(request);
        assertEquals("It is sunny", only(second, OutputTextContent.class).getText());

        JsonObject body = server.lastRequest().json();
        assertEquals("resp_1", body.get("previous_response_id").getAsString());
        assertEquals("Be helpful", body.get("instructions").getAsString(), "Instructions are not carried over");
        assertEquals(1, body.getAsJsonArray("tools").size(), "Tools are not carried over");
        JsonArray input = body.getAsJsonArray("input");
        assertEquals(1, input.size(), "Only the tool output is new, earlier input is replayed by the API: " + input);
        JsonObject output = input.get(0).getAsJsonObject();
        assertEquals("function_call_output", output.get("type").getAsString());
        assertEquals("call_1", output.get("call_id").getAsString());
        assertEquals("sunny", output.get("output").getAsString());

        assertTrue(step(request).isEmpty());
        assertEquals(2, server.requests().size());
    }

    @Test
    void failedToolCallIsReportedToModel() throws Exception {
        server.enqueue(response("resp_1", functionCall("call_1", "echo", "{\"text\":\"a\"}")));
        server.enqueue(response("resp_2", textMessage("Sorry")));
        ModelRequest request = request(new InputToolContent(new EchoTool()), new InputTextContent("Hi"));

        step(request);
        request.addInput(new ToolCallResponseContent("call_1", false, null, null));
        step(request);

        JsonObject output =
                server.lastRequest().json().getAsJsonArray("input").get(0).getAsJsonObject();
        assertEquals(OpenAIClient.FAILED_TOOL_CALL_OUTPUT, output.get("output").getAsString());
    }

    @Test
    void malformedToolArgumentsBecomeEmptyObject() {
        server.enqueue(response("resp_1", functionCall("call_1", "echo", "not json")));
        server.enqueue(response("resp_2", functionCall("call_2", "echo", "")));
        ModelRequest request = request(new InputTextContent("Hi"));

        assertEquals(
                0, only(step(request), ToolCallContent.class).getToolArguments().size());
        request.addInput(new ToolCallResponseContent("call_1", false, null, null));
        assertEquals(
                0, only(step(request), ToolCallContent.class).getToolArguments().size());
    }

    @Test
    void toolSchemaMatchesStrictModeRules() throws Exception {
        server.enqueue(response("resp_1", textMessage("ok")));
        ModelRequest request = request(
                new InputToolContent(new WeatherTool()),
                new InputToolContent(new EchoTool()),
                new InputTextContent("Hi"));

        step(request);

        JsonArray tools = server.lastRequest().json().getAsJsonArray("tools");
        JsonObject weather = tools.get(0).getAsJsonObject();
        assertEquals("function", weather.get("type").getAsString());
        assertEquals("weather", weather.get("name").getAsString());
        assertEquals("Gets the weather", weather.get("description").getAsString());
        assertFalse(
                weather.get("strict").getAsBoolean(),
                "Strict mode requires every property to be required, which isn't true for 'days'");
        JsonObject parameters = weather.getAsJsonObject("parameters");
        assertEquals("object", parameters.get("type").getAsString());
        assertFalse(parameters.get("additionalProperties").getAsBoolean());
        JsonObject properties = parameters.getAsJsonObject("properties");
        assertEquals("string", properties.getAsJsonObject("city").get("type").getAsString());
        assertEquals(
                "City name",
                properties.getAsJsonObject("city").get("description").getAsString());
        assertEquals("integer", properties.getAsJsonObject("days").get("type").getAsString());
        assertEquals("boolean", properties.getAsJsonObject("metric").get("type").getAsString());
        assertEquals(
                "number", properties.getAsJsonObject("threshold").get("type").getAsString());
        assertFalse(properties.getAsJsonObject("threshold").has("description"));
        JsonArray required = parameters.getAsJsonArray("required");
        assertEquals(3, required.size());
        assertFalse(required.toString().contains("days"));

        JsonObject echo = tools.get(1).getAsJsonObject();
        assertTrue(echo.get("strict").getAsBoolean());
        assertFalse(echo.has("description"));
    }

    @Test
    void imageAndFileContentUseContentPartArrays() {
        server.enqueue(response("resp_1", textMessage("ok")));
        ModelRequest request = request(
                new InputImageContent(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)),
                new InputFileContent("report.pdf", "%PDF".getBytes(StandardCharsets.UTF_8), "application/pdf"),
                new InputFileContent("empty.txt", null, null),
                new InputImageContent(null));

        step(request);

        JsonArray input = server.lastRequest().json().getAsJsonArray("input");
        assertEquals(2, input.size(), "Null images and files are skipped: " + input);
        JsonArray imageContent = input.get(0).getAsJsonObject().getAsJsonArray("content");
        JsonObject image = imageContent.get(0).getAsJsonObject();
        assertEquals("input_image", image.get("type").getAsString());
        assertTrue(image.get("image_url").getAsString().startsWith("data:image/png;base64,"));
        assertEquals("auto", image.get("detail").getAsString());
        JsonObject file =
                input.get(1).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("input_file", file.get("type").getAsString());
        assertEquals("report.pdf", file.get("filename").getAsString());
        assertEquals(
                "data:application/pdf;base64,JVBERg==", file.get("file_data").getAsString());
    }

    @Test
    void conversationHistoryBecomesUserAndAssistantMessages() throws Exception {
        server.enqueue(response("resp_1", textMessage("ok")));
        ModelConversation conversation = new ModelConversation();
        conversation.addAllContent(List.of(
                new InputTextContent("What is 2+2?"),
                new OutputTextContent("4"),
                new TokenUtilizationContent(1, 1, 0),
                new InputTextContent(null)));
        ModelRequest request = request(new InputConversationContent(conversation), new InputTextContent("And 3+3?"));

        step(request);

        JsonArray input = server.lastRequest().json().getAsJsonArray("input");
        assertEquals(3, input.size(), input.toString());
        assertEquals("user", input.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("assistant", input.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("4", input.get(1).getAsJsonObject().get("content").getAsString());
        assertEquals("And 3+3?", input.get(2).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void samplingSettingsAreSent() {
        server.enqueue(response("resp_1", textMessage("ok")));
        ModelRequest request = request(
                new InputTextContent("Hi"),
                new TemperatureContent(0.5),
                new TopPContent(0.9),
                new MaxOutputTokensContent(256));

        step(request);

        JsonObject body = server.lastRequest().json();
        assertEquals(0.5, body.get("temperature").getAsDouble());
        assertEquals(0.9, body.get("top_p").getAsDouble());
        assertEquals(256, body.get("max_output_tokens").getAsLong());
    }

    @Test
    void outOfRangeSamplingSettingsFailBeforeCallingApi() {
        assertThrows(
                IllegalArgumentException.class,
                () -> step(request(new InputTextContent("Hi"), new TemperatureContent(2.5))));
        assertThrows(
                IllegalArgumentException.class, () -> step(request(new InputTextContent("Hi"), new TopPContent(-0.1))));
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void thinkingLevelSetsReasoningEffort() {
        server.enqueue(response("resp_1", textMessage("ok")));
        step(request(new InputTextContent("Hi"), new ThinkingLevelContent(ModelThinkingLevel.MEDIUM)));

        JsonObject reasoning = server.lastRequest().json().getAsJsonObject("reasoning");
        assertEquals("medium", reasoning.get("effort").getAsString());
        assertFalse(reasoning.has("summary"), "Summaries are only requested when ThinkingContent is wanted");
    }

    @Test
    void thinkingContentRequestsReasoningSummaries() {
        server.enqueue(response("resp_1", reasoning("Adding numbers") + "," + textMessage("4")));
        ModelRequest request = request(new InputTextContent("2+2?"), new ThinkingLevelContent(ModelThinkingLevel.HIGH));
        request.requestOutputType(ThinkingContent.class);
        request.requestOutputType(OutputTextContent.class);

        List<ModelOutput> outputs = step(request);

        JsonObject reasoning = server.lastRequest().json().getAsJsonObject("reasoning");
        assertEquals("high", reasoning.get("effort").getAsString());
        assertEquals("auto", reasoning.get("summary").getAsString());
        assertEquals("Adding numbers", only(outputs, ThinkingContent.class).getThinking());
        assertEquals("4", only(outputs, OutputTextContent.class).getText());
    }

    @Test
    void outputsAreFilteredToRequestedTypesButToolCallsAreKept() {
        server.enqueue(response("resp_1", textMessage("Checking") + "," + functionCall("call_1", "echo", "{}")));
        ModelRequest request = request(new InputTextContent("Hi"));
        request.requestOutputType(OutputTextContent.class);

        List<ModelOutput> outputs = step(request);

        assertEquals(2, outputs.size(), outputs.toString());
        only(outputs, OutputTextContent.class);
        only(outputs, ToolCallContent.class);
    }

    @Test
    void incompleteResponsesMapToFinishReasons() {
        server.enqueue(response("resp_1", textMessage("Cut"), "{\"reason\":\"max_output_tokens\"}"));
        server.enqueue(response("resp_2", textMessage("Cut"), "{\"reason\":\"content_filter\"}"));

        assertEquals(
                ModelFinishReason.TOKEN_CAP,
                only(step(request(new InputTextContent("a"))), FinishReasonContent.class)
                        .getReason());
        assertEquals(
                ModelFinishReason.SAFEGUARD,
                only(step(request(new InputTextContent("b"))), FinishReasonContent.class)
                        .getReason());
    }

    @Test
    void refusalIsReturnedAsTextWithSafeguardFinishReason() {
        server.enqueue(response("resp_1", refusalMessage("I can't help with that")));

        List<ModelOutput> outputs = step(request(new InputTextContent("Hi")));

        assertEquals(
                "I can't help with that", only(outputs, OutputTextContent.class).getText());
        assertEquals(
                ModelFinishReason.SAFEGUARD,
                only(outputs, FinishReasonContent.class).getReason());
    }

    @Test
    void apiErrorsAreWrappedAndTheStepCanBeRetried() {
        server.enqueue(400, "{\"error\":{\"message\":\"Bad input\",\"type\":\"invalid_request_error\"}}");
        server.enqueue(response("resp_1", textMessage("ok")));
        ModelRequest request = request(new InputTextContent("Hi"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> step(request));
        assertTrue(e.getMessage().contains("HTTP 400"), e.getMessage());
        assertTrue(e.getMessage().contains("Bad input"), e.getMessage());

        List<ModelOutput> retry = step(request);
        assertEquals("ok", only(retry, OutputTextContent.class).getText());
        assertFalse(server.lastRequest().json().has("previous_response_id"));
    }

    @Test
    void failedResponseStatusIsReportedAsError() {
        server.enqueue(response("resp_1", "")
                .replace("\"error\":null", "\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}"));

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> step(request(new InputTextContent("Hi"))));
        assertTrue(e.getMessage().contains("boom"), e.getMessage());
    }

    @Test
    void zeroTimeoutMeansNoTimeout() throws Exception {
        clientSettings = new OpenAIClientSettings(0L, 1024L);
        server.enqueue(response("resp_1", textMessage("ok")));

        assertEquals(
                "ok",
                only(step(request(new InputTextContent("Hi"))), OutputTextContent.class)
                        .getText());
    }

    @Test
    void missingCredentialFailsBeforeCallingApi() throws Exception {
        OpenAIModelSettings unresolvable = new OpenAIModelSettings();

        assertThrows(
                IllegalStateException.class,
                () -> client.takeStep(
                        null,
                        unresolvable,
                        clientSettings,
                        request(new InputTextContent("Hi")),
                        List.of(new InputTextContent("Hi"))));
        assertTrue(server.requests().isEmpty());
    }
}
