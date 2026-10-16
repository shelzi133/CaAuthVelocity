package z.caAuthVelocity.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import z.caAuthVelocity.config.VelocityConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class BridgeClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final URI base;
    private final String secret;

    public BridgeClient(VelocityConfig.Api api) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, api.connectTimeoutSeconds)))
                .build();
        this.base = URI.create("http://" + api.host + ":" + api.port);
        this.secret = api.secret;
    }

    public CompletableFuture<JsonObject> check(UUID uuid, String name, String ip) {
        JsonObject body = player(uuid, name);
        body.addProperty("ip", ip);
        return post("/check", body, DEFAULT_TIMEOUT);
    }

    public CompletableFuture<JsonObject> bindCode(UUID uuid, String name) {
        return post("/bind/code", player(uuid, name), DEFAULT_TIMEOUT);
    }

    public CompletableFuture<JsonObject> bindStatus(UUID uuid, String name, String ip) {
        JsonObject body = player(uuid, name);
        body.addProperty("ip", ip);
        return post("/bind/status", body, DEFAULT_TIMEOUT);
    }

    public CompletableFuture<JsonObject> confirm(UUID uuid, String name, String ip, Duration timeout) {
        JsonObject body = player(uuid, name);
        body.addProperty("ip", ip);
        return post("/confirm", body, timeout);
    }

    public CompletableFuture<JsonObject> twinkRedeem(UUID uuid, String name, String ip, String code) {
        JsonObject body = player(uuid, name);
        body.addProperty("ip", ip);
        body.addProperty("code", code);
        return post("/twink/redeem", body, DEFAULT_TIMEOUT);
    }

    public CompletableFuture<JsonObject> cancel(UUID uuid) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid.toString());
        return post("/cancel", body, DEFAULT_TIMEOUT);
    }

    private JsonObject player(UUID uuid, String name) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid.toString());
        body.addProperty("name", name);
        return body;
    }

    private CompletableFuture<JsonObject> post(String path, JsonObject body, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Auth-Secret", secret)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new CompletionException(new IOException("HTTP " + response.statusCode() + " " + path));
                    }
                    return JsonParser.parseString(response.body()).getAsJsonObject();
                });
    }
}