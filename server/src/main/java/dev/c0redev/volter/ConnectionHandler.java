package dev.c0redev.volter;

import java.io.EOFException;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.SocketTimeoutException;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import org.json.JSONObject;

final class ConnectionHandler implements Runnable {

    private static final Logger log = Log.logger(ConnectionHandler.class);
    private static final HttpClient CLUSTER_FWD = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private static final int HANDSHAKE_TIMEOUT_MS = 4_000;
    private static final int HANDSHAKE_MARK_READ_LIMIT = 2 * 1024 * 1024;
    private static final SecureRandom HELLO_RND = new SecureRandom();
    
    private static final String PROBE_HANDSHAKE_TOKEN = "probe-bad-token";

    private final Socket sock;
    private final Config cfg;
    private final UdpSessions udp;
    private final TcpReactorPool tcpPool;
  private final ExecutorService streamPool;

    ConnectionHandler(Socket sock, Config cfg, UdpSessions udp, TcpReactorPool tcpPool, ExecutorService streamPool) {
        this.sock = sock;
        this.cfg = cfg;
        this.udp = udp;
        this.tcpPool = tcpPool;
        this.streamPool = streamPool;
    }

    @Override
    public void run() {
        boolean handedOff = false;
        Socket s = sock;
        BufferedInputStream rawIn = null;
        OutputStream rawOut = null;
        try {
            var xor = new XorStream(XorStream.keyFromToken(cfg.token()));
            rawIn = new BufferedInputStream(s.getInputStream(), 128 * 1024);
            rawIn.mark(HANDSHAKE_MARK_READ_LIMIT);
            rawOut = s.getOutputStream();
            byte[] peek = new byte[8];
            int peekN = rawIn.read(peek);
            rawIn.reset();
            if (peekN > 0 && (looksLikeHTTP(peek, peekN) || looksLikeTLS(peek, peekN))) {
                s.setSoTimeout(0);
                if (tryCamouflage(s, rawIn, rawOut)) {
                    handedOff = true;
                    return;
                }
            }
            s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            InputStream in = xor.wrapInput(rawIn);

            Protocol.HandshakeResult hr = Protocol.readHandshake(in);
            Protocol.Handshake hs = hr.handshake();
            if (
                !MessageDigest.isEqual(
                    cfg.token().getBytes(StandardCharsets.UTF_8),
                    hs.token().getBytes(StandardCharsets.UTF_8)
                )
            ) {
                if (PROBE_HANDSHAKE_TOKEN.equals(hs.token())) {
                    log.info("probe handshake (caps read) from " + s.getRemoteSocketAddress());
                } else {
                    log.warning("bad token from " + s.getRemoteSocketAddress());
                }
                sendCapability(rawOut);
                throw new IOException("bad token");
            }
            OutputStream out = xor.wrapOutput(rawOut);
            s.setSoTimeout(0);
            var session = new SessionHandler(cfg, udp, String.valueOf(s.getRemoteSocketAddress()), () -> {
                try {
                    s.close();
                } catch (IOException ignored) {}
            }, s);
            SessionHandler.TcpHandler tcp =
                (connect, rest, copts) -> handleTcp(connect, rest, s, xor, copts);
            if (hs.role() == Protocol.ROLE_UDP) {
                session.handle(hs, hr, in, out, tcp, streamPool);
                handedOff = true;
                return;
            }
            handedOff = true;
            streamPool.submit(() -> {
                try {
                    session.handle(hs, hr, in, out, tcp, streamPool);
                } catch (IOException e) {
                    log.fine("session ended: " + e.getMessage());
                    try {
                        s.close();
                    } catch (IOException ignored) {}
                }
            });
            return;
        } catch (EOFException ignored) {
            if (tryCamouflage(s, rawIn, rawOut)) {
                handedOff = true;
                return;
            }
        } catch (SocketTimeoutException e) {
            log.warning("handshake timeout from " + s.getRemoteSocketAddress() + " after " + HANDSHAKE_TIMEOUT_MS + "ms");
            if (tryCamouflage(s, rawIn, rawOut)) {
                handedOff = true;
                return;
            }
        } catch (IOException e) {
            log.fine("conn closed: " + e.getMessage());
            if (tryCamouflage(s, rawIn, rawOut)) {
                handedOff = true;
                return;
            }
        } finally {
            if (!handedOff) {
                try {
                    s.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private boolean tryCamouflage(Socket client, BufferedInputStream rawIn, OutputStream rawOut) {
        if (rawIn == null || rawOut == null) {
            return false;
        }
        try {
            rawIn.reset();
            rawIn.mark(HANDSHAKE_MARK_READ_LIMIT);
        } catch (IOException e) {
            log.warning("camouflage reset failed (mark overrun?): " + e.getMessage());
            return false;
        }
        byte[] sig = new byte[8];
        int n;
        try {
            n = rawIn.read(sig);
            rawIn.reset();
        } catch (IOException e) {
            return false;
        }
        if (n <= 0) {
            return false;
        }
        boolean looksHTTP = looksLikeHTTP(sig, n);
        boolean looksTLS = looksLikeTLS(sig, n);
        if (!looksHTTP && !looksTLS) {
            return false;
        }
        if (looksHTTP) {
            try {
                if (tryOpsHintsHttp(rawIn, rawOut)) {
                    return true;
                }
            } catch (IOException e) {
                log.fine("ops hints http: " + e.getMessage());
            }
            try {
                if (DhtFindHttp.tryServe(cfg, rawIn, rawOut)) {
                    return true;
                }
            } catch (IOException e) {
                log.fine("dht find http: " + e.getMessage());
            }
            try {
                if (tryRelayIndexHttp(rawIn, rawOut)) {
                    return true;
                }
            } catch (IOException e) {
                log.fine("relay index http: " + e.getMessage());
            }
            try {
                if (tryGossipNodesHttp(rawIn, rawOut)) {
                    return true;
                }
            } catch (IOException e) {
                log.fine("gossip nodes http: " + e.getMessage());
            }
            try {
                if (tryClusterMapHttp(rawIn, rawOut)) {
                    return true;
                }
            } catch (IOException e) {
                log.fine("cluster map http: " + e.getMessage());
            }
            try {
                if (tryClusterSessionsHttp(rawIn, rawOut)) {
                    return true;
                }
            } catch (IOException e) {
                log.fine("cluster sessions http: " + e.getMessage());
            }
            try {
                if (tryClusterClientsHttp(rawIn, rawOut)) {
                    return true;
                }
            } catch (IOException e) {
                log.fine("cluster clients http: " + e.getMessage());
            }
            try {
                if (tryClusterInviteHttp(rawIn, rawOut)) {
                    return true;
                }
            } catch (IOException e) {
                log.fine("cluster invite http: " + e.getMessage());
            }
            try {
                if (tryClusterPeerHandshakeHttp(rawIn, rawOut)) {
                    return true;
                }
            } catch (IOException e) {
                log.fine("cluster peer handshake http: " + e.getMessage());
            }
        }
        if (!cfg.camouflageTcpEnabled()) {
            return false;
        }
        String host = cfg.camouflageTcpProxyHost();
        int port = cfg.camouflageTcpProxyPort();
        if (host != null && !host.isBlank() && port > 0) {
            return proxyRaw(client, rawIn, rawOut, host, port);
        }
        if (looksHTTP) {
            return writeFakeHttp(rawOut);
        }
        return false;
    }

    private static final long RELAY_INDEX_MAX_BYTES = 2L * 1024 * 1024;

    private boolean tryOpsHintsHttp(BufferedInputStream rawIn, OutputStream rawOut) throws IOException {
        String want = cfg.opsHintsPath();
        if (want == null || want.isBlank()) {
            return false;
        }
        rawIn.mark(65536);
        String line = readHttpLine(rawIn, 8192);
        String path = httpRequestPath(line);
        if (!want.equals(path)) {
            rawIn.reset();
            return false;
        }
        drainHttpHeaders(rawIn);
        String body = "{\"v\":1,\"quicIngressBackpressure\":" + QuicSignals.ingressBackpressureEvents() + "}\n";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(rawOut, StandardCharsets.UTF_8));
        w.write("HTTP/1.1 200 OK\r\n");
        w.write("Server: " + cfg.camouflageHttpServerName() + "\r\n");
        w.write("Content-Type: application/json; charset=utf-8\r\n");
        w.write("Content-Length: " + bytes.length + "\r\n");
        w.write("Connection: close\r\n");
        w.write("\r\n");
        w.flush();
        rawOut.write(bytes);
        rawOut.flush();
        log.fine("served ops hints");
        return true;
    }

    private boolean tryRelayIndexHttp(BufferedInputStream rawIn, OutputStream rawOut) throws IOException {
        String file = cfg.relayIndexFile();
        if (file == null || file.isBlank()) {
            return false;
        }
        rawIn.mark(65536);
        String line = readHttpLine(rawIn, 8192);
        String path = httpRequestPath(line);
        String want = cfg.relayIndexPath();
        if (!want.equals(path)) {
            rawIn.reset();
            return false;
        }
        Path fp = Path.of(file);
        if (!Files.isRegularFile(fp)) {
            rawIn.reset();
            return false;
        }
        long sz = Files.size(fp);
        if (sz <= 0 || sz > RELAY_INDEX_MAX_BYTES) {
            rawIn.reset();
            return false;
        }
        drainHttpHeaders(rawIn);
        byte[] body = Files.readAllBytes(fp);
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(rawOut, StandardCharsets.UTF_8));
        w.write("HTTP/1.1 200 OK\r\n");
        w.write("Server: " + cfg.camouflageHttpServerName() + "\r\n");
        w.write("Content-Type: application/json; charset=utf-8\r\n");
        w.write("Content-Length: " + body.length + "\r\n");
        w.write("Connection: close\r\n");
        w.write("\r\n");
        w.flush();
        rawOut.write(body);
        rawOut.flush();
        log.fine("served relay index " + body.length + " bytes");
        return true;
    }

    private boolean tryGossipNodesHttp(BufferedInputStream rawIn, OutputStream rawOut) throws IOException {
        String file = cfg.gossipIndexFile();
        if (file == null || file.isBlank()) {
            file = cfg.relayIndexFile();
        }
        if (file == null || file.isBlank()) {
            return false;
        }
        rawIn.mark(65536);
        String line = readHttpLine(rawIn, 8192);
        String path = httpRequestPath(line);
        if (!cfg.gossipIndexPath().equals(path)) {
            rawIn.reset();
            return false;
        }
        Path fp = Path.of(file);
        if (!Files.isRegularFile(fp)) {
            rawIn.reset();
            return false;
        }
        long sz = Files.size(fp);
        if (sz <= 0 || sz > RELAY_INDEX_MAX_BYTES) {
            rawIn.reset();
            return false;
        }
        drainHttpHeaders(rawIn);
        byte[] body = Files.readAllBytes(fp);
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(rawOut, StandardCharsets.UTF_8));
        w.write("HTTP/1.1 200 OK\r\n");
        w.write("Server: " + cfg.camouflageHttpServerName() + "\r\n");
        w.write("Content-Type: application/json; charset=utf-8\r\n");
        w.write("Content-Length: " + body.length + "\r\n");
        w.write("Connection: close\r\n");
        w.write("\r\n");
        w.flush();
        rawOut.write(body);
        rawOut.flush();
        log.fine("served gossip nodes " + body.length + " bytes");
        return true;
    }

    private boolean tryClusterMapHttp(BufferedInputStream rawIn, OutputStream rawOut) throws IOException {
        String want = cfg.clusterMapPath();
        if (want == null || want.isBlank()) {
            return false;
        }
        rawIn.mark(65536);
        String line = readHttpLine(rawIn, 8192);
        String path = httpRequestPath(line);
        if (!want.equals(path)) {
            rawIn.reset();
            return false;
        }
        if (!clusterHttpAuthorize(rawIn, rawOut)) {
            return true;
        }
        byte[] body = (ClusterRuntime.get().clusterMapJson() + "\n").getBytes(StandardCharsets.UTF_8);
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(rawOut, StandardCharsets.UTF_8));
        w.write("HTTP/1.1 200 OK\r\n");
        w.write("Server: " + cfg.camouflageHttpServerName() + "\r\n");
        w.write("Content-Type: application/json; charset=utf-8\r\n");
        w.write("Content-Length: " + body.length + "\r\n");
        w.write("Connection: close\r\n");
        w.write("\r\n");
        w.flush();
        rawOut.write(body);
        rawOut.flush();
        log.fine("served cluster map " + body.length + " bytes");
        return true;
    }

    private boolean tryClusterSessionsHttp(BufferedInputStream rawIn, OutputStream rawOut) throws IOException {
        String want = cfg.clusterSessionsPath();
        if (want == null || want.isBlank()) {
            return false;
        }
        rawIn.mark(65536);
        String line = readHttpLine(rawIn, 8192);
        String path = httpRequestPath(line);
        if (!want.equals(path)) {
            rawIn.reset();
            return false;
        }
        if (!clusterHttpAuthorize(rawIn, rawOut)) {
            return true;
        }
        byte[] body = (SessionResumeRegistry.get().exportJson(cfg.clusterNodeId()) + "\n").getBytes(StandardCharsets.UTF_8);
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(rawOut, StandardCharsets.UTF_8));
        w.write("HTTP/1.1 200 OK\r\n");
        w.write("Server: " + cfg.camouflageHttpServerName() + "\r\n");
        w.write("Content-Type: application/json; charset=utf-8\r\n");
        w.write("Content-Length: " + body.length + "\r\n");
        w.write("Connection: close\r\n");
        w.write("\r\n");
        w.flush();
        rawOut.write(body);
        rawOut.flush();
        log.fine("served cluster sessions " + body.length + " bytes");
        return true;
    }

    private boolean tryClusterClientsHttp(BufferedInputStream rawIn, OutputStream rawOut) throws IOException {
        String want = cfg.clusterClientsPath();
        if (want == null || want.isBlank()) {
            return false;
        }
        rawIn.mark(65536);
        String line = readHttpLine(rawIn, 8192);
        String path = httpRequestPath(line);
        if (!want.equals(path)) {
            rawIn.reset();
            return false;
        }
        if (!clusterHttpAuthorize(rawIn, rawOut)) {
            return true;
        }
        byte[] body = (ClusterClientRegistry.get().exportJson(cfg.clusterNodeId()) + "\n").getBytes(StandardCharsets.UTF_8);
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(rawOut, StandardCharsets.UTF_8));
        w.write("HTTP/1.1 200 OK\r\n");
        w.write("Server: " + cfg.camouflageHttpServerName() + "\r\n");
        w.write("Content-Type: application/json; charset=utf-8\r\n");
        w.write("Content-Length: " + body.length + "\r\n");
        w.write("Connection: close\r\n");
        w.write("\r\n");
        w.flush();
        rawOut.write(body);
        rawOut.flush();
        log.fine("served cluster clients " + body.length + " bytes");
        return true;
    }

    private boolean tryClusterInviteHttp(BufferedInputStream rawIn, OutputStream rawOut) throws IOException {
        String want = cfg.clusterInvitePath();
        if (want == null || want.isBlank()) {
            return false;
        }
        rawIn.mark(96 * 1024);
        String line = readHttpLine(rawIn, 8192);
        String path = httpRequestPathAny(line);
        if (!want.equals(path) || !isHttpMethod(line, "POST")) {
            rawIn.reset();
            return false;
        }
        Map<String, String> headers = readHttpHeadersToMap(rawIn);
        if (!clusterPostKeyOk(headers, rawOut)) {
            return true;
        }
        int cl = contentLengthFromHeaders(headers);
        if (cl <= 0 || cl > 64 * 1024) {
            writeJsonStatus(rawOut, 400, "bad_request", "content-length");
            return true;
        }
        byte[] body = readBodyExact(rawIn, cl);
        JSONObject o;
        try {
            o = new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            writeJsonStatus(rawOut, 400, "bad_request", "json");
            return true;
        }
        String target = o.optString("targetNodeId", "").trim();
        if (target.isEmpty()) {
            writeJsonStatus(rawOut, 400, "bad_request", "targetNodeId");
            return true;
        }
        if (!target.equals(cfg.clusterNodeId())) {
            var hp = ClusterRuntime.get().resolveVolterHttpHostPort(target);
            if (hp.isEmpty()) {
                writeJsonNodeResponse(rawOut, 200, "unknown_target", "peer_not_in_map", null);
                return true;
            }
            String ck = headers.getOrDefault("x-volter-cluster-key", "");
            return clusterForwardPost(cfg.clusterInvitePath(), body, ck, hp.get(), rawOut);
        }
        long deadline = o.optLong("deadlineMs", 0L);
        if (deadline > 0 && System.currentTimeMillis() > deadline) {
            writeJsonNodeResponse(rawOut, 200, "rejected", "deadline", null);
            return true;
        }
        String corr = o.optString("correlationId", "").trim();
        ClusterRoutingRegistry reg = ClusterRoutingRegistry.get();
        if (!corr.isEmpty() && reg.isCorrelationFresh(corr)) {
            writeJsonNodeResponse(rawOut, 200, "accepted", "", redirectHostPortHint());
            return true;
        }
        long ttlMs = o.optLong("ttlMs", 5_000L);
        if (!corr.isEmpty()) {
            reg.touchCorrelation(corr, ttlMs);
        }
        writeJsonNodeResponse(rawOut, 200, "accepted", "", redirectHostPortHint());
        log.fine("cluster invite accepted correlationId=" + corr);
        return true;
    }

    private boolean tryClusterPeerHandshakeHttp(BufferedInputStream rawIn, OutputStream rawOut) throws IOException {
        String want = cfg.clusterPeerHandshakePath();
        if (want == null || want.isBlank()) {
            return false;
        }
        rawIn.mark(96 * 1024);
        String line = readHttpLine(rawIn, 8192);
        String path = httpRequestPathAny(line);
        if (!want.equals(path) || !isHttpMethod(line, "POST")) {
            rawIn.reset();
            return false;
        }
        Map<String, String> headers = readHttpHeadersToMap(rawIn);
        if (!clusterPostKeyOk(headers, rawOut)) {
            return true;
        }
        int cl = contentLengthFromHeaders(headers);
        if (cl <= 0 || cl > 64 * 1024) {
            writeJsonStatus(rawOut, 400, "bad_request", "content-length");
            return true;
        }
        byte[] body = readBodyExact(rawIn, cl);
        JSONObject o;
        try {
            o = new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            writeJsonStatus(rawOut, 400, "bad_request", "json");
            return true;
        }
        String target = o.optString("targetNodeId", "").trim();
        if (target.isEmpty()) {
            writeJsonStatus(rawOut, 400, "bad_request", "targetNodeId");
            return true;
        }
        if (!target.equals(cfg.clusterNodeId())) {
            var hp = ClusterRuntime.get().resolveVolterHttpHostPort(target);
            if (hp.isEmpty()) {
                writeJsonNodeResponse(rawOut, 200, "unknown_target", "peer_not_in_map", null);
                return true;
            }
            String ck = headers.getOrDefault("x-volter-cluster-key", "");
            return clusterForwardPost(cfg.clusterPeerHandshakePath(), body, ck, hp.get(), rawOut);
        }
        long deadline = o.optLong("deadlineMs", 0L);
        if (deadline > 0 && System.currentTimeMillis() > deadline) {
            writeJsonNodeResponse(rawOut, 200, "reject", "deadline", null);
            return true;
        }
        String corr = o.optString("correlationId", "").trim();
        long ttlMs = o.optLong("ttlMs", 5_000L);
        if (!corr.isEmpty()) {
            ClusterRoutingRegistry.get().touchCorrelation(corr, ttlMs);
        }
        writeJsonNodeResponse(rawOut, 200, "accepted", "", redirectHostPortHint());
        log.fine("cluster peer handshake accepted correlationId=" + corr);
        return true;
    }

    private boolean clusterForwardPost(String path, byte[] body, String clusterKey, String hostPort, OutputStream rawOut)
        throws IOException {
        try {
            String url = "http://" + hostPort + path;
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            rb.header("Content-Type", "application/json; charset=utf-8");
            if (clusterKey != null && !clusterKey.isBlank()) {
                rb.header("X-Volter-Cluster-Key", clusterKey);
            }
            HttpResponse<byte[]> resp = CLUSTER_FWD.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            String ct = resp.headers().firstValue("Content-Type").orElse("application/json; charset=utf-8");
            writeRawForwardResponse(rawOut, resp.statusCode(), resp.body(), ct);
            return true;
        } catch (Exception e) {
            log.warning("cluster forward: " + e.getMessage());
            writeJsonStatus(rawOut, 502, "forward_failed", "upstream");
            return true;
        }
    }

    private void writeRawForwardResponse(OutputStream rawOut, int status, byte[] body, String contentType) throws IOException {
        String hr = switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 502 -> "Bad Gateway";
            default -> "Error";
        };
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(rawOut, StandardCharsets.UTF_8));
        w.write("HTTP/1.1 " + status + " " + hr + "\r\n");
        w.write("Server: " + cfg.camouflageHttpServerName() + "\r\n");
        w.write("Content-Type: " + (contentType != null && !contentType.isBlank() ? contentType : "application/json; charset=utf-8") + "\r\n");
        w.write("Content-Length: " + body.length + "\r\n");
        w.write("Connection: close\r\n");
        w.write("\r\n");
        w.flush();
        rawOut.write(body);
        rawOut.flush();
    }

    private String redirectHostPortHint() {
        String host = cfg.publicHost().trim();
        if (host.isEmpty()) {
            host = "127.0.0.1";
        }
        int p = cfg.listenPorts().isEmpty() ? 443 : cfg.listenPorts().get(0);
        return host + ":" + p;
    }

    private static boolean isHttpMethod(String requestLine, String method) {
        if (requestLine == null) {
            return false;
        }
        String u = requestLine.trim().toUpperCase(Locale.ROOT);
        return u.startsWith(method.toUpperCase(Locale.ROOT) + " ");
    }

    private static Map<String, String> readHttpHeadersToMap(BufferedInputStream rawIn) throws IOException {
        Map<String, String> m = new HashMap<>();
        while (true) {
            String line = readHttpLine(rawIn, 8192);
            if (line.isEmpty()) {
                break;
            }
            int c = line.indexOf(':');
            if (c <= 0) {
                continue;
            }
            String k = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
            m.put(k, line.substring(c + 1).trim());
        }
        return m;
    }

    private static int contentLengthFromHeaders(Map<String, String> headers) {
        String cl = headers.get("content-length");
        if (cl == null) {
            return -1;
        }
        try {
            return Integer.parseInt(cl.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static byte[] readBodyExact(BufferedInputStream rawIn, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = rawIn.read(b, off, n - off);
            if (r < 0) {
                throw new EOFException();
            }
            off += r;
        }
        return b;
    }

    private boolean clusterPostKeyOk(Map<String, String> headers, OutputStream rawOut) throws IOException {
        if (!cfg.clusterHttpAuth()) {
            return true;
        }
        String want = cfg.clusterHttpExpectedKey();
        if (want.isEmpty()) {
            writeJsonStatus(rawOut, 403, "forbidden", "no_cluster_key_config");
            return false;
        }
        String got = headers.getOrDefault("x-volter-cluster-key", "");
        byte[] a = want.getBytes(StandardCharsets.UTF_8);
        byte[] b = got.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(a, b)) {
            writeJsonStatus(rawOut, 403, "forbidden", "header");
            return false;
        }
        return true;
    }

    private void writeJsonStatus(OutputStream rawOut, int httpCode, String status, String reason) throws IOException {
        JSONObject o = new JSONObject();
        o.put("status", status);
        if (!reason.isEmpty()) {
            o.put("reason", reason);
        }
        byte[] body = (o.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        String hs = httpCode == 200 ? "200 OK" : httpCode == 403 ? "403 Forbidden" : httpCode == 400 ? "400 Bad Request" : "500 Internal Server Error";
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(rawOut, StandardCharsets.UTF_8));
        w.write("HTTP/1.1 " + hs + "\r\n");
        w.write("Server: " + cfg.camouflageHttpServerName() + "\r\n");
        w.write("Content-Type: application/json; charset=utf-8\r\n");
        w.write("Content-Length: " + body.length + "\r\n");
        w.write("Connection: close\r\n");
        w.write("\r\n");
        w.flush();
        rawOut.write(body);
        rawOut.flush();
    }

    private void writeJsonNodeResponse(OutputStream rawOut, int httpCode, String status, String reason, String redirectHostPort)
        throws IOException {
        JSONObject o = new JSONObject();
        o.put("status", status);
        if (!reason.isEmpty()) {
            o.put("reason", reason);
        }
        if (redirectHostPort != null && !redirectHostPort.isBlank()) {
            o.put("redirectHostPort", redirectHostPort.trim());
        }
        byte[] body = (o.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        String hs = httpCode == 200 ? "200 OK" : "403 Forbidden";
        BufferedWriter wr = new BufferedWriter(new OutputStreamWriter(rawOut, StandardCharsets.UTF_8));
        wr.write("HTTP/1.1 " + hs + "\r\n");
        wr.write("Server: " + cfg.camouflageHttpServerName() + "\r\n");
        wr.write("Content-Type: application/json; charset=utf-8\r\n");
        wr.write("Content-Length: " + body.length + "\r\n");
        wr.write("Connection: close\r\n");
        wr.write("\r\n");
        wr.flush();
        rawOut.write(body);
        rawOut.flush();
    }

    private static String httpRequestPathAny(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        int sp1 = line.indexOf(' ');
        if (sp1 < 0) {
            return "";
        }
        int sp2 = line.indexOf(' ', sp1 + 1);
        if (sp2 < 0) {
            return "";
        }
        String path = line.substring(sp1 + 1, sp2);
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        return path;
    }

    private static String readHttpLine(BufferedInputStream in, int maxLen) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLen; i++) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                sb.append((char) b);
            }
        }
        return sb.toString();
    }

    private static void drainHttpHeaders(BufferedInputStream in) throws IOException {
        while (true) {
            String line = readHttpLine(in, 8192);
            if (line.isEmpty()) {
                return;
            }
        }
    }

    private boolean clusterHttpAuthorize(BufferedInputStream rawIn, OutputStream rawOut) throws IOException {
        if (!cfg.clusterHttpAuth()) {
            drainHttpHeaders(rawIn);
            return true;
        }
        String want = cfg.clusterHttpExpectedKey();
        if (want.isEmpty()) {
            drainHttpHeaders(rawIn);
            writeClusterHttpForbidden(rawOut);
            return false;
        }
        String got = null;
        while (true) {
            String line = readHttpLine(rawIn, 8192);
            if (line.isEmpty()) {
                break;
            }
            int c = line.indexOf(':');
            if (c <= 0) {
                continue;
            }
            if (line.substring(0, c).trim().equalsIgnoreCase("X-Volter-Cluster-Key")) {
                got = line.substring(c + 1).trim();
            }
        }
        byte[] a = want.getBytes(StandardCharsets.UTF_8);
        byte[] b = (got != null ? got : "").getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(a, b)) {
            writeClusterHttpForbidden(rawOut);
            return false;
        }
        return true;
    }

    private void writeClusterHttpForbidden(OutputStream rawOut) throws IOException {
        byte[] body = "forbidden\n".getBytes(StandardCharsets.UTF_8);
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(rawOut, StandardCharsets.UTF_8));
        w.write("HTTP/1.1 403 Forbidden\r\n");
        w.write("Server: " + cfg.camouflageHttpServerName() + "\r\n");
        w.write("Content-Type: text/plain; charset=utf-8\r\n");
        w.write("Content-Length: " + body.length + "\r\n");
        w.write("Connection: close\r\n");
        w.write("\r\n");
        w.flush();
        rawOut.write(body);
        rawOut.flush();
    }

    private static String httpRequestPath(String line) {
        if (line == null || !line.startsWith("GET ")) {
            return "";
        }
        int sp = line.indexOf(' ', 4);
        if (sp < 0) {
            return "";
        }
        String path = line.substring(4, sp);
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        return path;
    }

    private boolean proxyRaw(Socket client, InputStream cin, OutputStream cout, String host, int port) {
        Socket upstream = new Socket();
        try {
            upstream.connect(new InetSocketAddress(host, port), 8_000);
            upstream.setTcpNoDelay(true);
            upstream.setSoTimeout(0);
            InputStream uin = upstream.getInputStream();
            OutputStream uout = upstream.getOutputStream();
            Future<?> up = streamPool.submit(() -> {
                byte[] b = new byte[32 * 1024];
                try {
                    int r;
                    while ((r = cin.read(b)) >= 0) {
                        if (r == 0) continue;
                        uout.write(b, 0, r);
                        uout.flush();
                    }
                } catch (IOException ignored) {
                } finally {
                    try { upstream.shutdownOutput(); } catch (Exception ignored) {}
                }
            });
            byte[] b = new byte[32 * 1024];
            int r;
            while ((r = uin.read(b)) >= 0) {
                if (r == 0) continue;
                cout.write(b, 0, r);
                cout.flush();
            }
            try {
                up.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                up.cancel(true);
            } catch (ExecutionException ignored) {
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try { upstream.close(); } catch (Exception ignored) {}
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private boolean writeFakeHttp(OutputStream out) {
        try {
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            w.write("HTTP/1.1 200 OK\r\n");
            w.write("Server: " + cfg.camouflageHttpServerName() + "\r\n");
            w.write("Content-Type: text/html; charset=utf-8\r\n");
            w.write("Connection: close\r\n");
            w.write("\r\n");
            w.write("<!doctype html><html><head><title>Welcome</title></head><body><h1>It works</h1></body></html>");
            w.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean looksLikeTLS(byte[] b, int n) {
        return n >= 3 && (b[0] & 0xff) == 0x16 && (b[1] & 0xff) == 0x03 && (b[2] & 0xff) <= 0x04;
    }

    private static boolean looksLikeHTTP(byte[] b, int n) {
        if (n < 3) return false;
        String s = new String(b, 0, Math.min(n, 8), StandardCharsets.US_ASCII).toUpperCase();
        return s.startsWith("GET ") || s.startsWith("POST ") || s.startsWith("HEAD ") ||
            s.startsWith("PUT ") || s.startsWith("OPTI") || s.startsWith("CONN");
    }

    private void sendCapability(OutputStream rawOut) {
        if (rawOut == null) return;
        try {
            int legacyIpv6 = Ipv6Detect.hasIPv6() ? 1 : 0;
            int transportMask = 0;
            if (cfg.tcpEnabled()) transportMask |= Protocol.TRANSPORT_TCP;
            if (cfg.quicEnabled()) transportMask |= Protocol.TRANSPORT_QUIC;
            int featureBits = legacyIpv6 == 1 ? Protocol.FEAT_IPV6 : 0;
            featureBits |= Protocol.FEAT_POLY_HANDSHAKE;
            featureBits |= Protocol.FEAT_ROUTE_HOP_ACK;
            int obfsProfileId = Protocol.pickObfsProfileId();
            int tcpPortHint = cfg.listenPorts().isEmpty() ? 0 : cfg.listenPorts().get(0);
            byte[] nonce = new byte[8];
            HELLO_RND.nextBytes(nonce);
            Protocol.writeServerHelloCaps(rawOut, new Protocol.ServerHelloCaps(
                Protocol.CAPS_VERSION,
                legacyIpv6,
                transportMask,
                featureBits,
                cfg.quicEnabled() ? cfg.quicListenPort() : 0,
                tcpPortHint,
                obfsProfileId,
                nonce,
                QuicServer.getAdvertisedQuicLeafPin(),
                cfg.peerRelayEnabled() ? 2 : 1,
                2,
                cfg.peerRelayEnabled() ? 1 : 0
            ));
        } catch (Throwable ignored) {}
    }

    private void handleTcp(
            Protocol.TcpConnect c,
            InputStream in,
            Socket s,
            XorStream xor,
            Optional<Protocol.ClientOptions> copts)
            throws IOException {
        log.info("TCP connect to " + c.ip().getHostAddress() + ":" + c.port());
        if (tcpPool == null) {
            throw new IOException("tcp reactor unavailable");
        }
        s.setSoTimeout(0);
        OutputStream clientXorOut = xor.wrapOutput(s.getOutputStream());
        if (ClusterTcpExitBridge.maybeBridge(cfg, c, in, clientXorOut, copts)) {
            try {
                s.close();
            } catch (IOException ignored) {}
            return;
        }
        byte[] initialClientData = drainAvailableWithoutBlocking(in);
        tcpPool.register(s, c, xor, initialClientData, true);
    }

    private static byte[] drainAvailableWithoutBlocking(InputStream in) throws IOException {
        if (in.available() <= 0) return null;
        var acc = new ByteArrayOutputStream();
        byte[] scratch = new byte[8192];
        while (in.available() > 0) {
            int want = Math.min(scratch.length, in.available());
            int r = in.read(scratch, 0, want);
            if (r <= 0) break;
            acc.write(scratch, 0, r);
        }
        return acc.size() == 0 ? null : acc.toByteArray();
    }
}
