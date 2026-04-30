package dev.c0redev.volter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

final class ClusterRuntime {
  private static final Logger log = Log.logger(ClusterRuntime.class);
  private static final ClusterRuntime INSTANCE = new ClusterRuntime();

  static ClusterRuntime get() {
    return INSTANCE;
  }

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final Map<String, ClusterNode> nodes = new ConcurrentHashMap<>();
  private volatile Config cfg;
  private volatile boolean running;
  private volatile Thread worker;
  private volatile String lastStateDigest = "";

  private ClusterRuntime() {}

  void start(Config cfg) {
    this.cfg = cfg;
    registerSelf();
    log.info("cluster start: node=" + cfg.clusterNodeId() + ", listen=" + cfg.clusterListen() + ", peers=" + cfg.clusterPeers());
    if (running || cfg.clusterPeers().isEmpty()) return;
    running = true;
    worker = new Thread(this::loop, "cluster-runtime");
    worker.setDaemon(true);
    worker.start();
  }

  void stop() {
    running = false;
    if (worker != null) worker.interrupt();
  }

  void registerSelf() {
    Config c = cfg;
    if (c == null) return;
    String endpoint = "";
    if (!c.listenPorts().isEmpty()) {
      String host = resolveSelfHost(c);
      endpoint = "http://" + host.trim() + ":" + c.listenPorts().get(0) + c.clusterMapPath();
    }
    nodes.put(c.clusterNodeId(), new ClusterNode(c.clusterNodeId(), endpoint, System.currentTimeMillis(), true));
  }

  private String resolveSelfHost(Config c) {
    String host = c.publicHost();
    if (host != null && !host.isBlank()) {
      return host.trim();
    }
    String[] endpoints = new String[] {
        "https://api.ipify.org",
        "https://checkip.amazonaws.com",
        "https://ipv4.icanhazip.com",
    };
    for (String u : endpoints) {
      try {
        HttpRequest req = HttpRequest.newBuilder(URI.create(u))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) continue;
        String ip = resp.body() == null ? "" : resp.body().trim();
        if (!ip.isBlank()) return ip;
      } catch (Exception ignored) {
      }
    }
    return "127.0.0.1";
  }

  String clusterMapJson() {
    Config c = cfg;
    if (c == null) return "{\"v\":1,\"nodeId\":\"\",\"nodes\":[]}";
    registerSelf();
    long now = System.currentTimeMillis();
    List<ClusterNode> copy = new ArrayList<>(nodes.values());
    copy.sort((a, b) -> a.nodeId.compareTo(b.nodeId));
    StringBuilder sb = new StringBuilder(256 + copy.size() * 96);
    sb.append("{\"v\":1,\"nodeId\":\"").append(json(c.clusterNodeId())).append("\",");
    sb.append("\"clusterListen\":").append(c.clusterListen()).append(",");
    sb.append("\"generatedAt\":").append(now).append(",");
    sb.append("\"nodes\":[");
    for (int i = 0; i < copy.size(); i++) {
      ClusterNode n = copy.get(i);
      if (i > 0) sb.append(',');
      sb.append("{\"id\":\"").append(json(n.nodeId)).append("\",");
      sb.append("\"endpoint\":\"").append(json(n.endpoint)).append("\",");
      sb.append("\"ts\":").append(n.lastSeenMs).append(",");
      sb.append("\"alive\":").append(n.alive).append("}");
    }
    sb.append("]}");
    return sb.toString();
  }

  private void loop() {
    while (running) {
      try {
        pullPeers();
      } catch (Exception e) {
        log.fine("cluster pull: " + e.getMessage());
      }
      try {
        Thread.sleep(Math.max(1_000, cfg != null ? cfg.clusterGossipIntervalMs() : 5_000));
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void pullPeers() {
    Config c = cfg;
    if (c == null) return;
    for (String peer : c.clusterPeers()) {
      String u = peer;
      if (!u.startsWith("http://") && !u.startsWith("https://")) {
        u = "http://" + u;
      }
      if (!u.endsWith(c.clusterMapPath())) {
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        u = u + c.clusterMapPath();
      }
      try {
        HttpRequest req = clusterPeerGet(URI.create(u));
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int sc = resp.statusCode();
        if (sc < 200 || sc >= 300) {
          log.warning("cluster pull map non-2xx peer=" + normalizeEndpoint(u) + " status=" + sc);
          continue;
        }
        int before = nodes.size();
        int merged = mergeFromJson(resp.body());
        int after = nodes.size();
        log.info("cluster pull map ok peer=" + normalizeEndpoint(u) + " merged=" + merged + " nodes=" + before + "->" + after);
        String sessUrl = sessionsUrlFromMapUrl(u, c);
        try {
          HttpRequest sreq = clusterPeerGet(URI.create(sessUrl));
          HttpResponse<String> sresp = http.send(sreq, HttpResponse.BodyHandlers.ofString());
          if (sresp.statusCode() >= 200 && sresp.statusCode() < 300) {
            SessionResumeRegistry.get().mergeFromJson(sresp.body());
          } else {
            log.warning("cluster pull sessions non-2xx peer=" + normalizeEndpoint(sessUrl) + " status=" + sresp.statusCode());
          }
        } catch (Exception ex) {
          log.warning("cluster pull sessions failed peer=" + normalizeEndpoint(sessUrl) + " err=" + ex.getMessage());
        }
      } catch (Exception e) {
        log.warning("cluster pull map failed peer=" + normalizeEndpoint(u) + " err=" + e.getMessage());
        markPeerDown(u);
      }
    }
    logClusterState(c);
  }

  private HttpRequest clusterPeerGet(URI uri) {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json");
    Config c = cfg;
    if (c != null && c.clusterHttpAuth()) {
      String k = c.clusterHttpExpectedKey();
      if (k != null && !k.isEmpty()) {
        b.header("X-Volter-Cluster-Key", k);
      }
    }
    return b.GET().build();
  }

  private static String sessionsUrlFromMapUrl(String mapUrl, Config c) {
    String base = mapUrl;
    String mp = c.clusterMapPath();
    if (base.endsWith(mp)) {
      base = base.substring(0, base.length() - mp.length());
    }
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + c.clusterSessionsPath();
  }

  private void markPeerDown(String endpoint) {
    for (Map.Entry<String, ClusterNode> e : nodes.entrySet()) {
      if (!e.getValue().endpoint.equals(endpoint)) continue;
      ClusterNode n = e.getValue();
      nodes.put(e.getKey(), new ClusterNode(n.nodeId, n.endpoint, System.currentTimeMillis(), false));
    }
  }

  private void logClusterState(Config c) {
    List<ClusterNode> copy = new ArrayList<>(nodes.values());
    List<String> online = new ArrayList<>();
    for (ClusterNode n : copy) {
      if (!n.alive) continue;
      String ep = normalizeEndpoint(n.endpoint);
      if (ep.isBlank()) {
        online.add(n.nodeId);
      } else {
        online.add(n.nodeId + "(" + ep + ")");
      }
    }
    online.sort(String::compareTo);

    Set<String> onlineEndpoints = new HashSet<>();
    for (ClusterNode n : copy) {
      if (!n.alive) continue;
      String ep = normalizeEndpoint(n.endpoint);
      if (!ep.isBlank()) onlineEndpoints.add(ep);
    }

    List<String> waiting = new ArrayList<>();
    for (String raw : c.clusterPeers()) {
      String ep = normalizeEndpoint(raw);
      if (ep.isBlank()) continue;
      if (!onlineEndpoints.contains(ep)) waiting.add(ep);
    }
    waiting.sort(String::compareTo);

    int total = 1 + c.clusterPeers().size();
    int connected = total - waiting.size();
    String digest = "connected=" + connected + "/" + total + "|online=" + online + "|waiting=" + waiting;
    if (digest.equals(lastStateDigest)) return;
    lastStateDigest = digest;

    if (waiting.isEmpty()) {
      log.info("cluster ready: connected " + connected + "/" + total + ", online " + online);
      return;
    }
    log.info("cluster sync: connected " + connected + "/" + total + ", online " + online + ", waiting " + waiting);
  }

  private static String normalizeEndpoint(String raw) {
    if (raw == null) return "";
    String s = raw.trim();
    if (s.isEmpty()) return "";
    try {
      String u = s;
      if (!u.startsWith("http://") && !u.startsWith("https://")) {
        u = "http://" + u;
      }
      URI uri = URI.create(u);
      String host = uri.getHost();
      int port = uri.getPort();
      if (host == null || host.isBlank()) {
        return s;
      }
      if (port <= 0) {
        return host;
      }
      return host + ":" + port;
    } catch (Exception ignored) {
      return s;
    }
  }

  private int mergeFromJson(String raw) {
    if (raw == null || raw.isBlank()) return 0;
    List<Map<String, String>> parsed = parseNodes(raw);
    long now = System.currentTimeMillis();
    int merged = 0;
    for (Map<String, String> row : parsed) {
      String id = row.getOrDefault("id", "").trim();
      String endpoint = row.getOrDefault("endpoint", "").trim();
      if (id.isEmpty()) continue;
      if (endpoint.isEmpty()) endpoint = "";
      nodes.put(id, new ClusterNode(id, endpoint, now, true));
      merged++;
    }
    return merged;
  }

  private static List<Map<String, String>> parseNodes(String json) {
    if (json == null) return List.of();
    int i = json.indexOf("\"nodes\"");
    if (i < 0) return List.of();
    int l = json.indexOf('[', i);
    int r = json.indexOf(']', l + 1);
    if (l < 0 || r <= l) return List.of();
    String arr = json.substring(l + 1, r);
    if (arr.isBlank()) return List.of();
    List<Map<String, String>> out = new ArrayList<>();
    String[] objs = arr.split("\\},\\{");
    for (String chunk : objs) {
      String c = chunk.trim();
      if (!c.startsWith("{")) c = "{" + c;
      if (!c.endsWith("}")) c = c + "}";
      Map<String, String> m = new LinkedHashMap<>();
      String id = jsonField(c, "id");
      String endpoint = jsonField(c, "endpoint");
      if (id != null) m.put("id", id);
      if (endpoint != null) m.put("endpoint", endpoint);
      if (!m.isEmpty()) out.add(m);
    }
    return out;
  }

  private static String jsonField(String json, String key) {
    String needle = "\"" + key + "\"";
    int i = json.indexOf(needle);
    if (i < 0) return null;
    int colon = json.indexOf(':', i + needle.length());
    if (colon < 0) return null;
    int q1 = json.indexOf('"', colon + 1);
    if (q1 < 0) return null;
    int q2 = json.indexOf('"', q1 + 1);
    if (q2 < 0) return null;
    return json.substring(q1 + 1, q2);
  }

  private static String json(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static final class ClusterNode {
    final String nodeId;
    final String endpoint;
    final long lastSeenMs;
    final boolean alive;

    ClusterNode(String nodeId, String endpoint, long lastSeenMs, boolean alive) {
      this.nodeId = nodeId;
      this.endpoint = endpoint;
      this.lastSeenMs = lastSeenMs;
      this.alive = alive;
    }
  }
}
