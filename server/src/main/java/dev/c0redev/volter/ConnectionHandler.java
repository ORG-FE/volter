package dev.c0redev.volter;

import java.io.EOFException;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.SocketTimeoutException;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

final class ConnectionHandler implements Runnable {

    private static final Logger log = Log.logger(ConnectionHandler.class);
    private static final int HANDSHAKE_TIMEOUT_MS = 5_000;
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
            s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            rawIn = new BufferedInputStream(s.getInputStream(), 64 * 1024);
            rawIn.mark(64 * 1024);
            rawOut = s.getOutputStream();
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
            });
            session.handle(hs, hr, in, out, (connect, rest) -> handleTcp(connect, rest, s, xor), streamPool);
            handedOff = true;
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
        if (!cfg.camouflageTcpEnabled() || rawIn == null || rawOut == null) {
            return false;
        }
        try {
            rawIn.reset();
            rawIn.mark(64 * 1024);
        } catch (IOException ignored) {
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
            Thread up = new Thread(() -> {
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
            }, "camouflage-up");
            up.setDaemon(true);
            up.start();
            byte[] b = new byte[32 * 1024];
            int r;
            while ((r = uin.read(b)) >= 0) {
                if (r == 0) continue;
                cout.write(b, 0, r);
                cout.flush();
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
                2,
                2,
                1
            ));
        } catch (Throwable ignored) {}
    }

    private void handleTcp(Protocol.TcpConnect c, InputStream in, Socket s, XorStream xor) throws IOException {
        log.info("TCP connect to " + c.ip().getHostAddress() + ":" + c.port());
        if (tcpPool == null) {
            throw new IOException("tcp reactor unavailable");
        }
        s.setSoTimeout(0);
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
