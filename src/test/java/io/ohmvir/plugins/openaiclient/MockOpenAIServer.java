package io.ohmvir.plugins.openaiclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Minimal stand-in for the OpenAI API that records requests and replays queued responses.
 */
final class MockOpenAIServer implements AutoCloseable {
    record RecordedRequest(String method, String path, String authorization, String body) {
        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    private record QueuedResponse(int status, String body) {}

    private final HttpServer server;
    private final Deque<QueuedResponse> responses = new ConcurrentLinkedDeque<>();
    private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());

    MockOpenAIServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    MockOpenAIServer enqueue(String body) {
        return enqueue(200, body);
    }

    MockOpenAIServer enqueue(int status, String body) {
        responses.add(new QueuedResponse(status, body));
        return this;
    }

    List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    RecordedRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body));
        QueuedResponse response = responses.poll();
        if (response == null) {
            response = new QueuedResponse(500, "{\"error\":{\"message\":\"no response queued\"}}");
        }
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static String response(String id, String outputJson) {
        return response(id, outputJson, "null");
    }

    static String response(String id, String outputJson, String incompleteDetailsJson) {
        return """
                {"id":"%s","object":"response","created_at":1700000000,"status":"%s","model":"gpt-4o-mini",
                 "error":null,"incomplete_details":%s,"instructions":null,"metadata":{},
                 "parallel_tool_calls":true,"temperature":1.0,"top_p":1.0,"tool_choice":"auto","tools":[],
                 "output":[%s],
                 "usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":2},"output_tokens":5,
                          "output_tokens_details":{"reasoning_tokens":0},"total_tokens":15}}
                """.formatted(
                        id,
                        "null".equals(incompleteDetailsJson) ? "completed" : "incomplete",
                        incompleteDetailsJson,
                        outputJson);
    }

    static String textMessage(String text) {
        return """
                {"type":"message","id":"msg_1","status":"completed","role":"assistant",
                 "content":[{"type":"output_text","text":"%s","annotations":[]}]}
                """.formatted(text);
    }

    static String refusalMessage(String refusal) {
        return """
                {"type":"message","id":"msg_1","status":"completed","role":"assistant",
                 "content":[{"type":"refusal","refusal":"%s"}]}
                """.formatted(refusal);
    }

    static String functionCall(String callId, String name, String argumentsJson) {
        JsonObject call = new JsonObject();
        call.addProperty("type", "function_call");
        call.addProperty("id", "fc_" + callId);
        call.addProperty("call_id", callId);
        call.addProperty("name", name);
        call.addProperty("arguments", argumentsJson);
        call.addProperty("status", "completed");
        return call.toString();
    }

    static String reasoning(String summary) {
        return """
                {"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"%s"}]}
                """.formatted(summary);
    }

    static String model(String id) {
        return """
                {"id":"%s","object":"model","created":1700000000,"owned_by":"openai"}
                """.formatted(id);
    }
}
