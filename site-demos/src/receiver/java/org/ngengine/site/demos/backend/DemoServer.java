/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.ngengine.site.demos.backend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.jvm.JVMAsyncPlatform;
import org.ngengine.site.demos.rtc.PingProtocol;

/** Same-origin static preview and bounded demo-session broker. Run behind HTTPS for public hosting. */
public final class DemoServer implements AutoCloseable {

    private static final long TTL = Duration.ofSeconds(90).toNanos();
    private static final long GAME_TTL = Duration.ofMinutes(3).toNanos();
    private static final List<String> GAME_RELAYS = List.of(
        "wss://relay.ngengine.org",
        "wss://relay2.ngengine.org",
        "wss://nostr.rblb.it"
    );
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, PrivateGame> privateGames = new ConcurrentHashMap<>();
    private final Map<String, Long> requests = new LinkedHashMap<>();
    private final Map<String, Long> gameRequests = new LinkedHashMap<>();
    private final List<DemoPeer> bots = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final java.util.concurrent.ExecutorService workers = new java.util.concurrent.ThreadPoolExecutor(
        8,
        8,
        0,
        TimeUnit.SECONDS,
        new java.util.concurrent.ArrayBlockingQueue<>(32),
        new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
    );
    private final Set<String> hosts;
    private final Path root;
    private final List<String> relays;
    private final String turnUri;
    private final HttpServer server;
    private volatile boolean gameEnabled;
    private volatile boolean botsStarting;
    private volatile long gameLastSeen;

    private record Session(DemoPeer peer, long created) {}

    private record PrivateGame(List<DemoPeer> peers, String address, AtomicLong touched) {}

    DemoServer(Path root, int port, Set<String> hosts, List<String> relays, String turnUri) throws IOException {
        this.root = root.toRealPath();
        this.hosts = Set.copyOf(hosts);
        this.relays = List.copyOf(relays);
        this.turnUri = turnUri;
        for (String relay : relays) requireWss(relay);
        requireWss(turnUri);
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 32);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        scheduler.scheduleWithFixedDelay(this::expire, 5, 5, TimeUnit.SECONDS);
    }

    private static void requireWss(String value) {
        URI uri = URI.create(value);
        if (
            !"wss".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
        ) throw new IllegalArgumentException("Operator endpoints must be wss URLs without credentials");
    }

    void start(boolean gameBots) {
        gameEnabled = gameBots;
        server.start();
    }

    private void activateGameBots() {
        gameLastSeen = System.nanoTime();
        synchronized (bots) {
            if (botsStarting || !bots.isEmpty()) return;
            botsStarting = true;
        }
        workers.submit(() -> {
            for (int i = 0; i < 3; i++) {
                DemoPeer peer = null;
                try {
                    peer = new DemoPeer("sats-seas-lobby", GAME_RELAYS, turnUri, i, false);
                    synchronized (bots) {
                        bots.add(peer);
                    }
                    peer.start(scheduler);
                    System.out.println("Game bot ready: " + (i == 0 ? "Cita" : i));
                } catch (Exception error) {
                    if (peer != null) {
                        synchronized (bots) {
                            bots.remove(peer);
                        }
                        peer.close();
                    }
                    System.err.println("Game bot startup failed: " + error.getClass().getSimpleName());
                }
            }
            botsStarting = false;
        });
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (host == null || !hosts.contains(host.toLowerCase(java.util.Locale.ROOT))) {
                json(exchange, 403, Map.of("error", "Unrecognized host"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith("/api/")) {
                staticFile(exchange, path);
                return;
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/api/config".equals(path)) {
                int count;
                synchronized (bots) {
                    count = bots.size();
                }
                json(
                    exchange,
                    200,
                    Map.of(
                        "relays",
                        relays,
                        "turnUri",
                        turnUri,
                        "gameBots",
                        count,
                        "privateGameRooms",
                        privateGames.size(),
                        "lobby",
                        "sats-seas-lobby"
                    )
                );
                return;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (!("http://" + host).equals(origin) && !("https://" + host).equals(origin)) {
                json(exchange, 403, Map.of("error", "Same-origin request required"));
                return;
            }
            if ("POST".equals(method) && "/api/ping".equals(path)) {
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType == null || !contentType.startsWith("application/json")) {
                    json(exchange, 415, Map.of("error", "JSON required"));
                    return;
                }
                byte[] body = exchange.getRequestBody().readNBytes(1025);
                if (body.length > 1024) {
                    json(exchange, 413, Map.of("error", "Request too large"));
                    return;
                }
                // Only a fixed transport choice is accepted; visitors cannot choose server destinations.
                String request = new String(body, StandardCharsets.UTF_8).trim();
                if (!Set.of("{}", "{\"mode\":\"auto\"}", "{\"mode\":\"direct\"}", "{\"mode\":\"turn\"}").contains(request)) {
                    json(exchange, 400, Map.of("error", "Expected a valid transport mode"));
                    return;
                }
                allocate(exchange, "{\"mode\":\"turn\"}".equals(request));
            } else if ("POST".equals(method) && "/api/game".equals(path)) {
                if (!gameEnabled) {
                    json(exchange, 503, Map.of("error", "Game peers disabled"));
                    return;
                }
                if (!"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                    json(exchange, 415, Map.of("error", "JSON required"));
                    return;
                }
                byte[] body = exchange.getRequestBody().readNBytes(33);
                String request = new String(body, StandardCharsets.UTF_8);
                if (!Set.of("{\"mode\":\"shared\"}", "{\"mode\":\"private\"}").contains(request)) {
                    json(exchange, 400, Map.of("error", "Expected shared or private mode"));
                    return;
                }
                if ("{\"mode\":\"private\"}".equals(request)) {
                    allocatePrivateGame(exchange);
                } else {
                    activateGameBots();
                    json(
                        exchange,
                        200,
                        Map.of(
                            "roomKeySeed",
                            "sats-seas-lobby",
                            "relays",
                            GAME_RELAYS,
                            "turnUri",
                            turnUri,
                            "peersStarting",
                            botsStarting
                        )
                    );
                }
            } else if (path.matches("/api/game/[a-f0-9]{64}") && Set.of("POST", "DELETE").contains(method)) {
                String token = path.substring("/api/game/".length());
                PrivateGame game = privateGames.get(token);
                if (game == null) {
                    json(exchange, 404, Map.of("error", "Game room not found"));
                    return;
                }
                String address = exchange.getRemoteAddress().getAddress().getHostAddress();
                if (!game.address.equals(address)) {
                    json(exchange, 403, Map.of("error", "Room owner mismatch"));
                    return;
                }
                if ("POST".equals(method)) {
                    if (
                        !"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type")) ||
                        !"{}".equals(new String(exchange.getRequestBody().readNBytes(3), StandardCharsets.UTF_8))
                    ) {
                        json(exchange, 400, Map.of("error", "Expected empty JSON object"));
                        return;
                    }
                    game.touched.set(System.nanoTime());
                    json(exchange, 200, Map.of("expiresIn", 180));
                } else {
                    if (privateGames.remove(token, game)) game.peers.forEach(DemoPeer::close);
                    json(exchange, 200, Map.of("closed", true));
                }
            } else if ("DELETE".equals(method) && path.matches("/api/ping/[a-f0-9]{64}")) {
                Session session = sessions.remove(path.substring("/api/ping/".length()));
                if (session != null) session.peer.close();
                json(exchange, 200, Map.of("closed", true));
            } else {
                json(exchange, 404, Map.of("error", "Not found"));
            }
        } catch (Exception error) {
            System.err.println("Demo request failed: " + error.getClass().getSimpleName());
            json(exchange, 503, Map.of("error", "Demo backend unavailable; try again shortly"));
        } finally {
            exchange.close();
        }
    }

    private synchronized void allocate(HttpExchange exchange, boolean forceTurn) throws Exception {
        String address = exchange.getRemoteAddress().getAddress().getHostAddress();
        long now = System.nanoTime();
        requests.entrySet().removeIf(entry -> now - entry.getValue() > Duration.ofMinutes(2).toNanos());
        Long previous = requests.get(address);
        if (
            sessions.size() >= 8 ||
            (previous != null && now - previous < TimeUnit.SECONDS.toNanos(8)) ||
            requests.size() >= 1024
        ) {
            exchange.getResponseHeaders().set("Retry-After", "8");
            json(exchange, 429, Map.of("error", "Demo busy; retry in a few seconds"));
            return;
        }
        requests.put(address, now);
        String token = token();
        String seed = token();
        DemoPeer peer = new DemoPeer(seed, relays, turnUri, -1, forceTurn);
        Session session = new Session(peer, now);
        sessions.put(token, session);
        try {
            peer.start(scheduler);
            json(
                exchange,
                201,
                Map.of(
                    "token",
                    token,
                    "roomKeySeed",
                    seed,
                    "receiverPubkey",
                    peer.publicKey(),
                    "relays",
                    relays,
                    "turnUri",
                    turnUri,
                    "expiresIn",
                    90
                )
            );
        } catch (Exception error) {
            sessions.remove(token);
            peer.close();
            throw error;
        }
    }

    private synchronized void allocatePrivateGame(HttpExchange exchange) throws Exception {
        String address = exchange.getRemoteAddress().getAddress().getHostAddress();
        long now = System.nanoTime();
        gameRequests.entrySet().removeIf(entry -> now - entry.getValue() > Duration.ofMinutes(2).toNanos());
        Long previous = gameRequests.get(address);
        if (
            privateGames.size() >= 4 ||
            (previous != null && now - previous < TimeUnit.SECONDS.toNanos(8)) ||
            gameRequests.size() >= 1024
        ) {
            exchange.getResponseHeaders().set("Retry-After", "8");
            json(exchange, 429, Map.of("error", "Practice rooms busy; retry shortly"));
            return;
        }
        gameRequests.put(address, now);
        String token = token();
        String seed = token();
        List<DemoPeer> peers = new ArrayList<>();
        PrivateGame game = new PrivateGame(peers, address, new AtomicLong(now));
        privateGames.put(token, game);
        try {
            for (int bot = 3; bot <= 4; bot++) {
                DemoPeer peer = new DemoPeer(seed, GAME_RELAYS, turnUri, bot, false);
                peers.add(peer);
                peer.start(scheduler);
            }
            json(
                exchange,
                201,
                Map.of(
                    "token",
                    token,
                    "roomKeySeed",
                    seed,
                    "botPubkeys",
                    peers.stream().map(DemoPeer::publicKey).toList(),
                    "relays",
                    GAME_RELAYS,
                    "turnUri",
                    turnUri,
                    "expiresIn",
                    180
                )
            );
        } catch (Exception error) {
            privateGames.remove(token, game);
            peers.forEach(DemoPeer::close);
            throw error;
        }
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private void expire() {
        long now = System.nanoTime();
        if (gameLastSeen != 0 && now - gameLastSeen > Duration.ofMinutes(3).toNanos()) {
            gameLastSeen = 0;
            List<DemoPeer> stale;
            synchronized (bots) {
                stale = new ArrayList<>(bots);
                bots.clear();
            }
            stale.forEach(DemoPeer::close);
        }
        sessions.forEach((token, session) -> {
            if (now - session.created > TTL && sessions.remove(token, session)) {
                try {
                    session.peer.close();
                } catch (RuntimeException ignored) {}
            }
        });
        privateGames.forEach((token, game) -> {
            if (now - game.touched.get() > GAME_TTL && privateGames.remove(token, game)) {
                try {
                    game.peers.forEach(DemoPeer::close);
                } catch (RuntimeException ignored) {}
            }
        });
    }

    private void staticFile(HttpExchange exchange, String requested) throws IOException {
        if (!Set.of("GET", "HEAD").contains(exchange.getRequestMethod())) {
            json(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        if (requested.indexOf('\0') >= 0) {
            json(exchange, 404, Map.of("error", "Not found"));
            return;
        }
        Path file = root.resolve(requested.substring(1)).normalize();
        if (!file.startsWith(root)) {
            json(exchange, 404, Map.of("error", "Not found"));
            return;
        }
        if (Files.isDirectory(file)) file = file.resolve("index.html");
        if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(root)) {
            json(exchange, 404, Map.of("error", "Not found"));
            return;
        }
        String name = file.getFileName().toString();
        String mime = name.endsWith(".js")
            ? "text/javascript"
            : name.endsWith(".css")
                ? "text/css"
                : name.endsWith(".html")
                    ? "text/html"
                    : name.endsWith(".svg")
                        ? "image/svg+xml"
                        : name.endsWith(".wasm") ? "application/wasm" : "application/octet-stream";
        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, Files.size(file));
        Files.copy(file, exchange.getResponseBody());
    }

    private static void json(HttpExchange exchange, int status, Map<String, ?> value) throws IOException {
        byte[] bytes = NGEPlatform.get().toJSON(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(1);
        scheduler.shutdownNow();
        sessions.values().forEach(session -> session.peer.close());
        privateGames.values().forEach(game -> game.peers.forEach(DemoPeer::close));
        synchronized (bots) {
            bots.forEach(DemoPeer::close);
        }
        workers.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.httpserver.maxReqTime", "15");
        System.setProperty("sun.net.httpserver.maxRspTime", "30");
        System.setProperty("jdk.httpserver.maxConnections", "64");
        System.setProperty("sun.net.httpserver.maxIdleConnections", "16");
        NGEPlatform.set(new JVMAsyncPlatform());
        int port = Integer.parseInt(System.getenv().getOrDefault("DEMO_PORT", "8000"));
        Set<String> hosts = Set.copyOf(
            Arrays.asList(System.getenv().getOrDefault("DEMO_HOSTS", "localhost:" + port + ",127.0.0.1:" + port).split(","))
        );
        DemoServer server = new DemoServer(
            Path.of(System.getenv().getOrDefault("DEMO_ROOT", "_lan-preview")),
            port,
            hosts,
            List.of("wss://relay.ngengine.org", "wss://relay2.ngengine.org"),
            System.getenv().getOrDefault("TURN_URI", PingProtocol.DEFAULT_TURN_URI)
        );
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start(!"false".equals(System.getenv("DEMO_GAME_BOTS")));
        System.out.println("Demo server listening on port " + port);
    }
}
