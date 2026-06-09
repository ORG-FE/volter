package dev.c0redev.volter;

import java.net.InetAddress;
import java.net.InetSocketAddress;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

final class ClusterRuntime {
  private static final Logger log = Log.logger(ClusterRuntime.class);
  private static final Duration CLUSTER_HTTP_CONNECT = Duration.ofSeconds(1);
  private static final Duration CLUSTER_HTTP_READ = Duration.ofSeconds(2);
  private static final ExecutorService CLUSTER_HTTP_EXEC = Executors.newFixedThreadPool(2, r -> {
    Thread t = new Thread(r, "cluster-http");
    t.setDaemon(true);
    return t;
  });
  private static final long CLUSTER_NODE_STALE_MS = 10 * 60_000L;
  private static final ClusterRuntime INSTANCE = new ClusterRuntime();

  static ClusterRuntime get() {
    return INSTANCE;
  }

  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(CLUSTER_HTTP_CONNECT)
      .version(HttpClient.Version.HTTP_1_1)
      .executor(CLUSTER_HTTP_EXEC)
      .build();
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
    String dhtRpc = "";
    if (!c.listenPorts().isEmpty()) {
      String host = resolveSelfHost(c);
      endpoint = "http://" + host.trim() + ":" + c.listenPorts().get(0);
      dhtRpc = selfDhtRpc(host, c.dhtRpcListenUdp());
    }
    nodes.put(c.clusterNodeId(), new ClusterNode(c.clusterNodeId(), endpoint, dhtRpc, System.currentTimeMillis(), true));
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

  boolean isAuthorizedClusterExit(String exitRaw) {
    if (exitRaw == null || exitRaw.isBlank()) {
      return false;
    }
    String normalized = ClusterPreferredCanonical.canonical(exitRaw.trim());
    InetSocketAddress want;
    try {
      want = ClusterTcpExitBridge.parseHostPort(normalized);
    } catch (Exception e) {
      return false;
    }
    for (String id : nodes.keySet()) {
      ClusterNode node = nodes.get(id);
      if (node == null || !node.alive) {
        continue;
      }
      Optional<String> ohp = resolveVolterHttpHostPort(id);
      if (ohp.isEmpty()) {
        continue;
      }
      try {
        InetSocketAddress known = ClusterTcpExitBridge.parseHostPort(ohp.get());
        if (known.getPort() == want.getPort() && known.getAddress().equals(want.getAddress())) {
          return true;
        }
      } catch (Exception ignored) {
      }
    }
    return false;
  }

  boolean isOwnAddress(InetSocketAddress addr) {
    if (addr == null) return false;
    Config c = cfg;
    if (c == null) return false;

    ClusterNode self = nodes.get(c.clusterNodeId());
    if (self != null && self.endpoint != null && !self.endpoint.isBlank()) {
      try {
        URI u = URI.create(self.endpoint.trim());
        InetAddress selfHost = InetAddress.getByName(u.getHost());
        int selfPort = u.getPort();
        if (selfPort <= 0) {
          selfPort = "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
        }
        if (selfPort == addr.getPort() && selfHost.equals(addr.getAddress())) {
          return true;
        }
      } catch (Exception ignored) {
      }
    }

    for (int port : c.listenPorts()) {
      if (port == addr.getPort()) {
        try {
          InetAddress loop = InetAddress.getByName("127.0.0.1");
          if (loop.equals(addr.getAddress())) return true;
        } catch (Exception ignored) {
        }
      }
    }
    return false;
  }

  Optional<InetSocketAddress> resolveClusterExitDialAddress(String hint) {
    if (hint == null || hint.isBlank()) {
      return Optional.empty();
    }
    String h = ClusterPreferredCanonical.canonical(hint.trim());
    if (nodes.containsKey(h)) {
      ClusterNode node = nodes.get(h);
      if (node == null || !node.alive) {
        log.info("cluster exit " + h + " is offline, skip bridge");
        return Optional.empty();
      }
      return resolveVolterHttpHostPort(h).flatMap(hp -> {
        try {
          InetSocketAddress addr = ClusterTcpExitBridge.parseHostPort(hp);
          if (isOwnAddress(addr)) {
            log.info("cluster exit " + h + " resolved to self: " + addr + ", skip bridge");
            return Optional.empty();
          }
          return Optional.of(addr);
        } catch (Exception e) {
          return Optional.empty();
        }
      });
    }
    try {
      InetSocketAddress addr = ClusterTcpExitBridge.parseHostPort(h);
      if (isOwnAddress(addr)) {
        log.info("cluster exit resolved to self: " + h + ", skip bridge");
        return Optional.empty();
      }
      if (isAuthorizedClusterExit(h)) {
        return Optional.of(addr);
      }
    } catch (Exception ignored) {
    }
    return Optional.empty();
  }

  Optional<String> resolveVolterHttpHostPort(String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      return Optional.empty();
    }
    ClusterNode n = nodes.get(nodeId.trim());
    if (n == null || n.endpoint == null || n.endpoint.isBlank()) {
      return Optional.empty();
    }
    try {
      URI u = URI.create(n.endpoint.trim());
      String host = u.getHost();
      if (host == null || host.isBlank()) {
        return Optional.empty();
      }
      int port = u.getPort();
      if (port <= 0) {
        String sch = u.getScheme();
        port = "https".equalsIgnoreCase(sch) ? 443 : 80;
      }
      return Optional.of(host + ":" + port);
    } catch (Exception e) {
      return Optional.empty();
    }
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
      sb.append("\"region\":\"").append(json(regionFromNodeId(n.nodeId))).append("\",");
      sb.append("\"endpoint\":\"").append(json(n.endpoint)).append("\",");
      if (n.dhtRpc != null && !n.dhtRpc.isBlank()) {
        sb.append("\"dhtRpc\":\"").append(json(n.dhtRpc)).append("\",");
      }
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
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    for (String peer : c.clusterPeers()) {
      if (System.nanoTime() > deadline) {
        log.warning("cluster pull: cycle budget exceeded, skip remaining peers");
        break;
      }
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
        if (System.nanoTime() > deadline) {
          continue;
        }
        String sessUrl = sessionsUrlFromMapUrl(u, c);
        String clientsUrl = clientsUrlFromMapUrl(u, c);
        try {
          HttpRequest sreq = clusterPeerGet(URI.create(sessUrl));
          HttpResponse<String> sresp = http.send(sreq, HttpResponse.BodyHandlers.ofString());
          if (sresp.statusCode() >= 200 && sresp.statusCode() < 300) {
            SessionResumeRegistry.get().mergeFromJson(sresp.body());
          } else {
            log.warning("cluster pull sessions non-2xx peer=" + normalizeEndpoint(sessUrl) + " status=" + sresp.statusCode());
            markPeerDown(sessUrl);
          }
        } catch (Exception ex) {
          log.warning("cluster pull sessions failed peer=" + normalizeEndpoint(sessUrl) + " err=" + ex.getMessage());
          markPeerDown(sessUrl);
        }
        if (System.nanoTime() > deadline) {
          continue;
        }
        try {
          HttpRequest creq = clusterPeerGet(URI.create(clientsUrl));
          HttpResponse<String> cresp = http.send(creq, HttpResponse.BodyHandlers.ofString());
          if (cresp.statusCode() >= 200 && cresp.statusCode() < 300) {
            ClusterClientRegistry.get().mergeFromJson(cresp.body());
          } else {
            log.warning("cluster pull clients non-2xx peer=" + normalizeEndpoint(clientsUrl) + " status=" + cresp.statusCode());
            markPeerDown(clientsUrl);
          }
        } catch (Exception ex) {
          log.warning("cluster pull clients failed peer=" + normalizeEndpoint(clientsUrl) + " err=" + ex.getMessage());
          markPeerDown(clientsUrl);
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
        .timeout(CLUSTER_HTTP_READ)
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

  private static String clientsUrlFromMapUrl(String mapUrl, Config c) {
    String base = mapUrl;
    String mp = c.clusterMapPath();
    if (base.endsWith(mp)) {
      base = base.substring(0, base.length() - mp.length());
    }
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + c.clusterClientsPath();
  }

  private void markPeerDown(String endpoint) {
    String want = normalizeEndpoint(endpoint);
    if (want.isBlank()) return;
    for (Map.Entry<String, ClusterNode> e : nodes.entrySet()) {
      if (!normalizeEndpoint(e.getValue().endpoint).equals(want)) continue;
      ClusterNode n = e.getValue();
      nodes.put(e.getKey(), new ClusterNode(n.nodeId, n.endpoint, n.dhtRpc, System.currentTimeMillis(), false));
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
    evictStaleNodes(now);
    int merged = 0;
    for (Map<String, String> row : parsed) {
      String id = row.getOrDefault("id", "").trim();
      String endpoint = row.getOrDefault("endpoint", "").trim();
      String dhtRpc = row.getOrDefault("dhtRpc", "").trim();
      if (id.isEmpty()) continue;
      if (endpoint.isEmpty()) endpoint = "";
      long seen = now;
      String ts = row.get("ts");
      if (ts != null && !ts.isBlank()) {
        try {
          seen = Long.parseLong(ts.trim());
        } catch (NumberFormatException ignored) {
        }
      }
      nodes.put(id, new ClusterNode(id, endpoint, dhtRpc, seen, true));
      merged++;
    }
    return merged;
  }

  private void evictStaleNodes(long now) {
    Config c = cfg;
    String self = c != null ? c.clusterNodeId() : "";
    nodes.entrySet().removeIf(e -> {
      if (e.getKey().equals(self)) {
        return false;
      }
      return now - e.getValue().lastSeenMs > CLUSTER_NODE_STALE_MS;
    });
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
      String dhtRpc = jsonField(c, "dhtRpc");
      String ts = jsonFieldLong(c, "ts");
      if (id != null) m.put("id", id);
      if (endpoint != null) m.put("endpoint", endpoint);
      if (dhtRpc != null) m.put("dhtRpc", dhtRpc);
      if (ts != null) m.put("ts", ts);
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

  private static String jsonFieldLong(String json, String key) {
    String needle = "\"" + key + "\"";
    int i = json.indexOf(needle);
    if (i < 0) return null;
    int colon = json.indexOf(':', i + needle.length());
    if (colon < 0) return null;
    int p = colon + 1;
    while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
      p++;
    }
    int end = p;
    while (end < json.length() && Character.isDigit(json.charAt(end))) {
      end++;
    }
    if (end <= p) return null;
    return json.substring(p, end);
  }

  private static String selfDhtRpc(String publicHost, String listenUdp) {
    String host = publicHost == null ? "" : publicHost.trim();
    if (host.isBlank()) return "";
    if (listenUdp == null || listenUdp.isBlank()) return "";
    int port = dhtListenPort(listenUdp.trim());
    if (port <= 0) return "";
    return host + ":" + port;
  }

  private static int dhtListenPort(String listenUdp) {
    if (listenUdp == null || listenUdp.isBlank()) return 0;
    String s = listenUdp.trim();
    try {
      if (s.startsWith(":")) {
        return Integer.parseInt(s.substring(1));
      }
      if (s.startsWith("[")) {
        int end = s.indexOf(']');
        if (end > 0 && end+2 <= s.length() && s.charAt(end + 1) == ':') {
          return Integer.parseInt(s.substring(end + 2));
        }
      }
      int idx = s.lastIndexOf(':');
      if (idx > 0 && idx < s.length()-1) {
        return Integer.parseInt(s.substring(idx + 1));
      }
    } catch (Exception ignored) {}
    return 0;
  }

  private static String json(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String regionFromNodeId(String nodeId) {
    if (nodeId == null) return "";
    String s = nodeId.trim().toLowerCase();
    if (s.isEmpty()) return "";
    int dash = s.indexOf('-');
    if (dash > 0) return s.substring(0, dash);
    int under = s.indexOf('_');
    if (under > 0) return s.substring(0, under);
    return s;
  }

  private static final class ClusterNode {
    final String nodeId;
    final String endpoint;
    final String dhtRpc;
    final long lastSeenMs;
    final boolean alive;

    ClusterNode(String nodeId, String endpoint, String dhtRpc, long lastSeenMs, boolean alive) {
      this.nodeId = nodeId;
      this.endpoint = endpoint;
      this.dhtRpc = dhtRpc;
      this.lastSeenMs = lastSeenMs;
      this.alive = alive;
    }
  }
}
