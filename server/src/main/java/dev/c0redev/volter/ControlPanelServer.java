package dev.c0redev.volter;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

final class ControlPanelServer implements Closeable {
  private static final Logger log = Log.logger(ControlPanelServer.class);
  private static final String COOKIE = "volter_control";
  private static final SecureRandom RND = new SecureRandom();

  private final Config cfg;
  private final ControlStore store;
  private final ControlAuth auth;
  private final HttpServer http;

  private ControlPanelServer(Config cfg, ControlStore store, ControlAuth auth, HttpServer http) {
    this.cfg = cfg;
    this.store = store;
    this.auth = auth;
    this.http = http;
  }

  static ControlPanelServer start(Config cfg, Path base) throws IOException {
    Path dbPath = resolve(base, cfg.controlDb());
    Path keyPath = resolve(base, cfg.controlDoxhKeyFile());
    ControlStore store = ControlStore.open(dbPath);
    ControlRuntime.install(store);
    ControlAuth auth = cfg.controlDoxhEnabled() ? ControlAuth.load(keyPath) : null;
    HttpServer http = HttpServer.create(new InetSocketAddress(cfg.controlListen(), cfg.controlPort()), 64);
    ControlPanelServer srv = new ControlPanelServer(cfg, store, auth, http);
    http.createContext("/", srv::handle);
    http.setExecutor(Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "volter-control-http");
      t.setDaemon(true);
      return t;
    }));
    http.start();
    log.info("Control panel listening http://" + cfg.controlListen() + ":" + cfg.controlPort());
    return srv;
  }

  private void handle(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    try {
      if (path.equals("/api/v1/auth/doxh") && ex.getRequestMethod().equals("POST")) {
        handleLogin(ex);
        return;
      }
      if (path.equals("/api/v1/auth/logout") && ex.getRequestMethod().equals("POST")) {
        String sid = sessionId(ex);
        if (auth != null) auth.removeSession(sid);
        addExpiredCookie(ex.getResponseHeaders());
        json(ex, 200, new JSONObject().put("ok", true));
        return;
      }
      boolean logged = isLogged(ex);
      if (!logged) {
        if (path.equals("/") || path.equals("/login")) {
          html(ex, 200, loginHtml());
        } else {
          text(ex, path.startsWith("/assets/") ? 404 : 401, "unauthorized");
        }
        return;
      }
      if (path.equals("/") || path.equals("/index.html")) {
        html(ex, 200, panelHtml());
        return;
      }
      if (path.startsWith("/assets/")) {
        serveAsset(ex, path);
        return;
      }
      if (path.equals("/api/v1/status") && ex.getRequestMethod().equals("GET")) {
        json(ex, 200, statusJson());
        return;
      }
      if (path.equals("/api/v1/qr") && ex.getRequestMethod().equals("GET")) {
        handleQr(ex);
        return;
      }
      if (path.equals("/api/v1/clients") && ex.getRequestMethod().equals("GET")) {
        boolean includeRevoked = "true".equalsIgnoreCase(parseQuery(ex).getOrDefault("includeRevoked", "false"));
        json(ex, 200, new JSONObject().put("clients", store.listClientsJson(includeRevoked)));
        return;
      }
      if (path.equals("/api/v1/clients") && ex.getRequestMethod().equals("POST")) {
        handleCreateClient(ex);
        return;
      }
      if (path.startsWith("/api/v1/clients/") && path.endsWith("/revoke") && !path.contains("/devices/") && ex.getRequestMethod().equals("POST")) {
        String id = path.substring("/api/v1/clients/".length(), path.length() - "/revoke".length());
        boolean ok = store.revokeClient(id);
        if (ok) store.audit("client.revoke", id, remote(ex));
        json(ex, ok ? 200 : 404, new JSONObject().put("ok", ok));
        return;
      }
      if (path.startsWith("/api/v1/clients/") && path.endsWith("/rotate") && ex.getRequestMethod().equals("POST")) {
        String id = path.substring("/api/v1/clients/".length(), path.length() - "/rotate".length());
        handleRotateClient(ex, id);
        return;
      }
      if (path.startsWith("/api/v1/clients/") && path.endsWith("/effective-policy") && ex.getRequestMethod().equals("GET")) {
        String id = path.substring("/api/v1/clients/".length(), path.length() - "/effective-policy".length());
        JSONObject policy = store.effectivePolicyJson(id);
        if (policy == null) json(ex, 404, new JSONObject().put("ok", false).put("error", "client not found"));
        else json(ex, 200, new JSONObject().put("policy", policy));
        return;
      }
      if (path.startsWith("/api/v1/clients/") && path.endsWith("/policy") && ex.getRequestMethod().equals("POST")) {
        String id = path.substring("/api/v1/clients/".length(), path.length() - "/policy".length());
        JSONObject req = readJson(ex);
        boolean ok = store.updateClientPolicy(id, req);
        if (ok) store.audit("client.policy", id, req.toString());
        json(ex, ok ? 200 : 404, new JSONObject().put("ok", ok));
        return;
      }
      if (path.startsWith("/api/v1/clients/") && path.endsWith("/devices") && ex.getRequestMethod().equals("GET")) {
        String id = path.substring("/api/v1/clients/".length(), path.length() - "/devices".length());
        json(ex, 200, new JSONObject().put("devices", store.listDevicesJson(id)));
        return;
      }
      if (path.startsWith("/api/v1/clients/") && path.endsWith("/devices/policy") && ex.getRequestMethod().equals("POST")) {
        String id = path.substring("/api/v1/clients/".length(), path.length() - "/devices/policy".length());
        JSONObject req = readJson(ex);
        boolean ok = store.updateDevicePolicy(id, req.optString("mode", "multi"), req.optInt("limit", 3));
        if (ok) store.audit("device.policy", id, req.toString());
        json(ex, ok ? 200 : 404, new JSONObject().put("ok", ok));
        return;
      }
      if (path.startsWith("/api/v1/clients/") && path.endsWith("/revoke") && path.contains("/devices/") && ex.getRequestMethod().equals("POST")) {
        String rest = path.substring("/api/v1/clients/".length(), path.length() - "/revoke".length());
        int split = rest.indexOf("/devices/");
        if (split > 0) {
          String id = rest.substring(0, split);
          String deviceId = url(rest.substring(split + "/devices/".length()));
          boolean ok = store.revokeDevice(id, deviceId);
          if (ok) store.audit("device.revoke", id, deviceId);
          json(ex, ok ? 200 : 404, new JSONObject().put("ok", ok));
          return;
        }
      }
      if (path.equals("/api/v1/groups") && ex.getRequestMethod().equals("GET")) {
        json(ex, 200, new JSONObject().put("groups", store.listGroupsJson()));
        return;
      }
      if (path.startsWith("/api/v1/groups/") && path.endsWith("/policy") && ex.getRequestMethod().equals("POST")) {
        String id = path.substring("/api/v1/groups/".length(), path.length() - "/policy".length());
        JSONObject req = readJson(ex);
        boolean ok = store.updateGroupPolicy(id, req);
        if (ok) store.audit("group.policy", id, req.toString());
        json(ex, ok ? 200 : 404, new JSONObject().put("ok", ok));
        return;
      }
      if (path.equals("/api/v1/traffic") && ex.getRequestMethod().equals("GET")) {
        json(ex, 200, new JSONObject().put("traffic", store.trafficSummaryJson()));
        return;
      }
      if (path.equals("/api/v1/sessions") && ex.getRequestMethod().equals("GET")) {
        json(ex, 200, new JSONObject().put("sessions", store.activeSessionsJson()));
        return;
      }
      if (path.equals("/api/v1/cluster/registry") && ex.getRequestMethod().equals("GET")) {
        JSONObject reg = store.registryJson();
        if (reg == null) {
          reg = buildRegistry();
          store.saveRegistry(reg.getLong("version"), reg.getJSONObject("payload"), reg.getString("sig"), reg.optString("pub", ""));
        }
        json(ex, 200, new JSONObject().put("registry", reg));
        return;
      }
      if (path.equals("/api/v1/cluster/registry/rebuild") && ex.getRequestMethod().equals("POST")) {
        JSONObject reg = buildRegistry();
        store.saveRegistry(reg.getLong("version"), reg.getJSONObject("payload"), reg.getString("sig"), reg.optString("pub", ""));
        store.audit("registry.rebuild", cfg.clusterNodeId(), remote(ex));
        json(ex, 200, new JSONObject().put("ok", true).put("registry", reg));
        return;
      }
      if (path.equals("/api/v1/dns/logs") && ex.getRequestMethod().equals("GET")) {
        int limit = parseQueryInt(ex, "limit", 200);
        json(ex, 200, new JSONObject().put("logs", store.dnsLogsJson(limit)));
        return;
      }
      if (path.equals("/api/v1/dns/logs") && ex.getRequestMethod().equals("POST")) {
        JSONObject req = readJson(ex);
        store.logDns(req.optString("clientId", ""), req.optString("deviceId", ""), req.optString("domain", ""), req.optString("action", "allow"), req.optString("resolver", ""), req.optInt("ttl", 0));
        json(ex, 200, new JSONObject().put("ok", true));
        return;
      }
      if (path.equals("/api/v1/dns/logs/cleanup") && ex.getRequestMethod().equals("POST")) {
        JSONObject req = readJson(ex);
        int deleted = store.cleanupDnsLogs(req.optInt("retentionDays", 30));
        json(ex, 200, new JSONObject().put("ok", true).put("deleted", deleted));
        return;
      }
      if (path.equals("/api/v1/events/ws")) {
        if ("websocket".equalsIgnoreCase(ex.getRequestHeaders().getFirst("Upgrade"))) {
          handleEventsWs(ex);
        } else {
          json(ex, 200, new JSONObject()
              .put("type", "sessions.snapshot")
              .put("sessions", store.activeSessionsJson()));
        }
        return;
      }
      text(ex, 404, "not found");
    } catch (Exception e) {
      log.warning("control request failed path=" + path + " err=" + e.getMessage());
      text(ex, 500, "internal error");
    }
  }

  private void handleCreateClient(HttpExchange ex) throws Exception {
    JSONObject req = readJson(ex);
    String name = req.optString("name", "").trim();
    String groupId = req.optString("groupId", "user").trim();
    String serverHost = req.optString("serverHost", "").trim();
    long expiresAt = req.optLong("expiresAt", 0L);
    String note = req.optString("note", "");
    String secret = randomSecret(32);
    String salt = randomSecret(16);
    String secretHash = sha256(salt + ":" + secret);
    ControlStore.CreatedClient c = store.createClient(name, groupId, expiresAt, note, secret, salt, secretHash);
    String uri = voultKey(c, serverHost, ex);
    store.audit("client.create", c.id(), remote(ex));
    json(ex, 200, new JSONObject()
        .put("ok", true)
        .put("client", new JSONObject()
            .put("id", c.id())
            .put("userId", c.userId())
            .put("name", c.name())
            .put("groupId", c.groupId())
            .put("expiresAt", c.expiresAt()))
        .put("voultkey", uri));
  }

  private void handleRotateClient(HttpExchange ex, String id) throws Exception {
    JSONObject req = readJson(ex);
    long graceUntil = req.optLong("graceUntil", 0L);
    String serverHost = req.optString("serverHost", "").trim();
    String secret = randomSecret(32);
    String salt = randomSecret(16);
    String secretHash = sha256(salt + ":" + secret);
    ControlStore.CreatedClient c = store.rotateClient(id, secret, salt, secretHash, graceUntil);
    if (c == null) {
      json(ex, 404, new JSONObject().put("ok", false).put("error", "client not found"));
      return;
    }
    String uri = voultKey(c, serverHost, ex);
    store.audit("client.rotate", id, remote(ex));
    json(ex, 200, new JSONObject().put("ok", true).put("voultkey", uri).put("graceUntil", graceUntil));
  }

  private void handleLogin(HttpExchange ex) throws IOException {
    if (auth == null) {
      json(ex, 403, new JSONObject().put("ok", false).put("error", "doxh disabled"));
      return;
    }
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, String> form = parseForm(body);
    String key = form.getOrDefault("key", "");
    if (!auth.verifyKey(key)) {
      store.audit("auth.failed", remote(ex), "bad doxh");
      json(ex, 401, new JSONObject().put("ok", false).put("error", "bad key"));
      return;
    }
    String sid = auth.createSession();
    ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + sid + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=21600");
    store.audit("auth.login", remote(ex), "ok");
    json(ex, 200, new JSONObject().put("ok", true));
  }

  private void handleQr(HttpExchange ex) throws IOException {
    String data = parseQuery(ex).getOrDefault("data", "").trim();
    if (data.isBlank() || data.length() > 8192) {
      text(ex, 400, "bad qr data");
      return;
    }
    try {
      byte[] b = qrSvg(data).getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().set("Content-Type", "image/svg+xml; charset=utf-8");
      ex.getResponseHeaders().set("Cache-Control", "no-store");
      ex.sendResponseHeaders(200, b.length);
      try (OutputStream out = ex.getResponseBody()) { out.write(b); }
    } catch (Exception e) {
      text(ex, 500, "qr failed");
    }
  }

  private static String qrSvg(String data) throws Exception {
    Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
    hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
    hints.put(EncodeHintType.MARGIN, 2);
    BitMatrix m = new MultiFormatWriter().encode(data, BarcodeFormat.QR_CODE, 256, 256, hints);
    int w = m.getWidth();
    int h = m.getHeight();
    StringBuilder path = new StringBuilder(w * h / 2);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        if (m.get(x, y)) path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
      }
    }
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + w + " " + h + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\"" + path + "\"/></svg>";
  }

  private JSONObject statusJson() {
    return new JSONObject()
        .put("ok", true)
        .put("time", Instant.now().getEpochSecond())
        .put("nodeId", cfg.clusterNodeId())
        .put("control", new JSONObject()
            .put("panel", cfg.controlPanel())
            .put("listen", cfg.controlListen())
            .put("port", cfg.controlPort())
            .put("public", cfg.controlPublic())
            .put("allowRemote", cfg.controlAllowRemote())
            .put("httpOnly", true))
        .put("genericToken", new JSONObject()
            .put("enabled", cfg.genericTokenEnabled())
            .put("deprecated", cfg.genericTokenDeprecated())
            .put("disableAfter", cfg.genericTokenDisableAfter()));
  }

  private void handleEventsWs(HttpExchange ex) throws Exception {
    String key = ex.getRequestHeaders().getFirst("Sec-WebSocket-Key");
    if (key == null || key.isBlank()) {
      text(ex, 400, "missing websocket key");
      return;
    }
    Headers h = ex.getResponseHeaders();
    h.set("Upgrade", "websocket");
    h.set("Connection", "Upgrade");
    h.set("Sec-WebSocket-Accept", websocketAccept(key));
    ex.sendResponseHeaders(101, -1);
    try (OutputStream out = ex.getResponseBody()) {
      for (int i = 0; i < 180; i++) {
        JSONObject event = new JSONObject()
            .put("type", "sessions.snapshot")
            .put("ts", Instant.now().getEpochSecond())
            .put("sessions", store.activeSessionsJson());
        writeWsText(out, event.toString());
        out.flush();
        try {
          Thread.sleep(2_000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      out.write(new byte[]{(byte) 0x88, 0});
      out.flush();
    }
  }

  private JSONObject buildRegistry() {
    long version = Instant.now().getEpochSecond();
    JSONArray nodes = new JSONArray();
    String host = cfg.publicHost();
    if (host == null || host.isBlank()) host = cfg.controlListen();
    JSONArray endpoints = new JSONArray();
    for (int port : cfg.listenPorts()) endpoints.put(hostPort(host, port));
    if (cfg.quicEnabled()) endpoints.put(hostPort(host, cfg.quicListenPort()));
    nodes.put(new JSONObject()
        .put("id", cfg.clusterNodeId())
        .put("endpoints", endpoints)
        .put("controlPublic", cfg.controlPublic())
        .put("updatedAt", version));
    JSONObject payload = new JSONObject()
        .put("v", 1)
        .put("clusterId", cfg.clusterNodeId())
        .put("version", version)
        .put("nodes", nodes);
    return new JSONObject()
        .put("version", version)
        .put("payload", payload)
        .put("sig", hmac(cfg.token(), payload.toString()))
        .put("pub", "hmac-sha256:server-token");
  }

  private boolean isLogged(HttpExchange ex) {
    if (auth == null) return true;
    return auth.validSession(sessionId(ex));
  }

  private String sessionId(HttpExchange ex) {
    String c = ex.getRequestHeaders().getFirst("Cookie");
    if (c == null) return "";
    for (String part : c.split(";")) {
      String[] kv = part.trim().split("=", 2);
      if (kv.length == 2 && kv[0].equals(COOKIE)) return kv[1];
    }
    return "";
  }

  private static Map<String, String> parseForm(String body) {
    Map<String, String> out = new HashMap<>();
    for (String part : body.split("&")) {
      if (part.isBlank()) continue;
      String[] kv = part.split("=", 2);
      String k = url(kv[0]);
      String v = kv.length > 1 ? url(kv[1]) : "";
      out.put(k, v);
    }
    return out;
  }

  private static int parseQueryInt(HttpExchange ex, String key, int def) {
    try { return Integer.parseInt(parseQuery(ex).getOrDefault(key, String.valueOf(def))); } catch (Exception ignored) { return def; }
  }

  private static Map<String, String> parseQuery(HttpExchange ex) {
    Map<String, String> out = new HashMap<>();
    String q = ex.getRequestURI().getRawQuery();
    if (q == null || q.isBlank()) return out;
    for (String part : q.split("&")) {
      String[] kv = part.split("=", 2);
      if (kv.length == 2) out.put(url(kv[0]), url(kv[1]));
    }
    return out;
  }

  private static JSONObject readJson(HttpExchange ex) throws IOException {
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
    if (body.isEmpty()) return new JSONObject();
    if (body.startsWith("{")) return new JSONObject(body);
    Map<String, String> form = parseForm(body);
    JSONObject out = new JSONObject();
    for (var e : form.entrySet()) out.put(e.getKey(), e.getValue());
    return out;
  }

  private String voultKey(ControlStore.CreatedClient c, String requestedHost, HttpExchange ex) {
    JSONArray servers = new JSONArray();
    String host = vpnHostForKey(requestedHost, ex);
    for (int port : cfg.listenPorts()) servers.put(hostPort(host, port));
    String controlUrl = controlUrlForKey(host, ex);
    JSONObject payload = new JSONObject()
        .put("v", 2)
        .put("type", "voultkey")
        .put("clusterId", cfg.clusterNodeId())
        .put("userId", c.userId())
        .put("clientId", c.id())
        .put("secret", c.secret())
        .put("salt", c.salt())
        .put("transportToken", cfg.token())
        .put("controlUrl", controlUrl)
        .put("servers", servers)
        .put("deviceMode", "multi")
        .put("deviceLimit", 3)
        .put("created", c.createdAt())
        .put("expires", c.expiresAt());
    String b = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
    return "volter://" + b;
  }

  private String vpnHostForKey(String requestedHost, HttpExchange ex) {
    if (cfg.publicHost() != null && !cfg.publicHost().isBlank()) return cfg.publicHost().trim();
    if (requestedHost != null && !requestedHost.isBlank() && !isLocalWebHost(requestedHost)) return requestedHost.trim();
    String h = ex.getRequestHeaders().getFirst("Host");
    h = hostOnly(h);
    if (h != null && !h.isBlank() && !isLocalWebHost(h)) return h;
    return cfg.controlListen();
  }

  private String controlUrlForKey(String host, HttpExchange ex) {
    String h = host != null && !host.isBlank() ? host : hostOnly(ex.getRequestHeaders().getFirst("Host"));
    if (h == null || h.isBlank()) h = cfg.controlListen();
    return "http://" + hostPort(h, cfg.controlPort());
  }

  private static String hostOnly(String hostHeader) {
    if (hostHeader == null) return "";
    String h = hostHeader.trim();
    if (h.startsWith("[")) {
      int end = h.indexOf(']');
      return end > 0 ? h.substring(1, end) : h;
    }
    int idx = h.lastIndexOf(':');
    return idx > 0 ? h.substring(0, idx) : h;
  }

  private static boolean isLocalWebHost(String h) {
    String v = h == null ? "" : h.trim().toLowerCase();
    return v.equals("127.0.0.1") || v.equals("localhost") || v.equals("::1") || v.equals("0.0.0.0");
  }

  private static String hostPort(String host, int port) {
    String h = host != null ? host.trim() : "";
    if (h.contains(":")) h = "[" + h.replace("[", "").replace("]", "") + "]";
    return h + ":" + port;
  }

  private static String sha256(String s) {
    try {
      byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(b.length * 2);
      for (byte x : b) sb.append(String.format("%02x", x & 0xff));
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hmac(String key, String msg) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec((key != null ? key : "").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String websocketAccept(String key) {
    try {
      String src = key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
      byte[] sha = MessageDigest.getInstance("SHA-1").digest(src.getBytes(StandardCharsets.US_ASCII));
      return Base64.getEncoder().encodeToString(sha);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void writeWsText(OutputStream out, String text) throws IOException {
    byte[] b = text.getBytes(StandardCharsets.UTF_8);
    out.write(0x81);
    if (b.length <= 125) {
      out.write(b.length);
    } else if (b.length <= 65_535) {
      out.write(126);
      out.write((b.length >>> 8) & 0xff);
      out.write(b.length & 0xff);
    } else {
      out.write(127);
      long n = b.length;
      for (int i = 7; i >= 0; i--) out.write((int) ((n >>> (8 * i)) & 0xff));
    }
    out.write(b);
  }

  private static String randomSecret(int bytes) {
    byte[] b = new byte[bytes];
    RND.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static String url(String s) {
    return URLDecoder.decode(s, StandardCharsets.UTF_8);
  }

  private static Path resolve(Path base, String raw) {
    Path p = Path.of(raw);
    return p.isAbsolute() ? p : base.resolve(p).normalize();
  }

  private static String remote(HttpExchange ex) {
    return ex.getRemoteAddress() != null ? ex.getRemoteAddress().toString() : "";
  }

  private static void addExpiredCookie(Headers h) {
    h.add("Set-Cookie", COOKIE + "=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
  }

  private static void json(HttpExchange ex, int code, JSONObject obj) throws IOException {
    byte[] b = obj.toString().getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    ex.sendResponseHeaders(code, b.length);
    try (OutputStream out = ex.getResponseBody()) { out.write(b); }
  }

  private static void html(HttpExchange ex, int code, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    ex.sendResponseHeaders(code, b.length);
    try (OutputStream out = ex.getResponseBody()) { out.write(b); }
  }

  private static void text(HttpExchange ex, int code, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    ex.sendResponseHeaders(code, b.length);
    try (OutputStream out = ex.getResponseBody()) { out.write(b); }
  }

  private static void serveAsset(HttpExchange ex, String path) throws IOException {
    if (!ex.getRequestMethod().equals("GET")) {
      text(ex, 405, "method not allowed");
      return;
    }
    String name = path.substring("/assets/".length());
    if (name.contains("..") || name.contains("/") || name.isBlank()) {
      text(ex, 404, "not found");
      return;
    }
    try (InputStream in = ControlPanelServer.class.getResourceAsStream("/control/assets/" + name)) {
      if (in == null) {
        text(ex, 404, "not found");
        return;
      }
      byte[] b = in.readAllBytes();
      String ct = name.endsWith(".css") ? "text/css; charset=utf-8" : name.endsWith(".js") ? "application/javascript; charset=utf-8" : "application/octet-stream";
      ex.getResponseHeaders().set("Content-Type", ct);
      ex.getResponseHeaders().set("Cache-Control", "no-store");
      ex.sendResponseHeaders(200, b.length);
      try (OutputStream out = ex.getResponseBody()) { out.write(b); }
    }
  }

  private static String loginHtml() {
    return """
<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>Volter Control Login</title><style>body{font-family:system-ui;background:#0d1117;color:#e6edf3;display:grid;place-items:center;min-height:100vh;margin:0}.card{width:min(420px,92vw);background:#161b22;border:1px solid #30363d;border-radius:22px;padding:24px}input,button{width:100%;box-sizing:border-box;border-radius:14px;padding:12px;margin-top:12px}input{background:#0d1117;color:#e6edf3;border:1px solid #30363d}button{background:#2f81f7;color:white;border:0;font-weight:700}.err{color:#ff7b72}</style></head>
<body><form class='card' method='post' action='/api/v1/auth/doxh'><h1>Volter Control</h1><p>Doxh key required. Static assets are locked until auth.</p><input name='key' type='password' minlength='32' autocomplete='current-password' placeholder='Doxh key'><button>Enter</button></form></body></html>
""";
  }

  private static String panelHtml() {
    return """
<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>Volter VPN Control</title><link rel='stylesheet' href='/assets/control.css'></head>
<body><div id='app'></div><script src='/assets/control.js'></script></body></html>
""";
  }

  @Override
  public void close() throws IOException {
    http.stop(1);
    ControlRuntime.clear(store);
    store.close();
  }
}
