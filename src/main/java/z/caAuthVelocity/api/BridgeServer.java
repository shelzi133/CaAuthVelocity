package z.caAuthVelocity.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import ru.matveylegenda.tiauth.database.model.AuthUser;
import z.caAuthVelocity.config.VelocityConfig;
import z.caAuthVelocity.tiauth.PasswordChanger;
import z.caAuthVelocity.tiauth.PlayerLookup;
import z.caAuthVelocity.util.Text;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BridgeServer {

    private static final int MAX_BODY_BYTES = 4096;
    private static final long PASSWORD_TIMEOUT_SECONDS = 15;
    private static final String SECRET_HEADER = "X-Auth-Secret";

    @FunctionalInterface
    private interface Endpoint {
        JsonObject handle(JsonObject request) throws Exception;
    }

    private final ProxyServer proxy;
    private final Logger logger;
    private final VelocityConfig.Inbound settings;
    private final byte[] secret;
    private final PasswordChanger passwordChanger = new PasswordChanger();
    private final PlayerLookup playerLookup = new PlayerLookup();

    private HttpServer http;
    private ExecutorService executor;

    public BridgeServer(ProxyServer proxy, Logger logger, VelocityConfig.Inbound settings, String secret) {
        this.proxy = proxy;
        this.logger = logger;
        this.settings = settings;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public void start() throws IOException {
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "CaAuthVelocity-API");
            thread.setDaemon(true);
            return thread;
        });
        http = HttpServer.create(new InetSocketAddress(settings.bindHost, settings.port), 0);
        http.createContext("/kick", endpoint(this::kick));
        http.createContext("/password/change", endpoint(this::changePassword));
        http.createContext("/player/exists", endpoint(this::playerExists));
        http.setExecutor(executor);
        http.start();
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private JsonObject kick(JsonObject request) {
        String reason = string(request, "reason");
        Optional<Player> player = proxy.getPlayer(uuid(request));
        if (player.isEmpty()) {
            player = proxy.getPlayer(string(request, "name"));
        }

        JsonObject response = new JsonObject();
        response.addProperty("kicked", player.isPresent());
        player.ifPresent(target -> target.disconnect(Text.section(reason)));
        return response;
    }

    private JsonObject playerExists(JsonObject request) throws Exception {
        AuthUser user = playerLookup.find(string(request, "name")).get(PASSWORD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        JsonObject response = new JsonObject();
        response.addProperty("exists", user != null);
        if (user != null) {
            response.addProperty("realName", user.getRealName());
        }
        return response;
    }

    private JsonObject changePassword(JsonObject request) throws InterruptedException {
        String name = string(request, "name");
        String password = string(request, "password");

        JsonObject response = new JsonObject();
        try {
            PasswordChanger.Result result = passwordChanger.change(name, password)
                    .get(PASSWORD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            response.addProperty("result", result.name());
        } catch (ExecutionException | TimeoutException e) {
            logger.warn("Не удалось сменить пароль игроку {}: {}", name, e.getCause() != null ? e.getCause() : e);
            response.addProperty("result", "ERROR");
        }
        return response;
    }

    private HttpHandler endpoint(Endpoint endpoint) {
        return exchange -> {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    respond(exchange, 405, error("method not allowed"));
                    return;
                }
                if (!authorized(exchange)) {
                    respond(exchange, 401, error("unauthorized"));
                    return;
                }

                byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                if (body.length > MAX_BODY_BYTES) {
                    respond(exchange, 413, error("payload too large"));
                    return;
                }

                JsonObject request;
                try {
                    request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (RuntimeException e) {
                    respond(exchange, 400, error("invalid json"));
                    return;
                }

                try {
                    respond(exchange, 200, endpoint.handle(request));
                } catch (IllegalArgumentException e) {
                    respond(exchange, 400, error(e.getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    respond(exchange, 503, error("interrupted"));
                } catch (Exception e) {
                    logger.warn("Ошибка во входящем API ({}): {}", exchange.getRequestURI().getPath(), e.toString());
                    respond(exchange, 500, error("internal error"));
                }
            } catch (IOException e) {
                logger.debug("Ошибка ответа входящего API: {}", e.getMessage());
            } finally {
                exchange.close();
            }
        };
    }

    private boolean authorized(HttpExchange exchange) {
        String provided = exchange.getRequestHeaders().getFirst(SECRET_HEADER);
        return provided != null && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), secret);
    }

    private void respond(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private JsonObject error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return body;
    }

    private String string(JsonObject request, String key) {
        if (!request.has(key) || !request.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing field: " + key);
        }
        String value = request.get(key).getAsString();
        if (value.isBlank()) {
            throw new IllegalArgumentException("empty field: " + key);
        }
        return value;
    }

    private UUID uuid(JsonObject request) {
        try {
            return UUID.fromString(string(request, "uuid"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid uuid");
        }
    }
}
