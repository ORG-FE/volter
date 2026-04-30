package dev.c0redev.volter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ClusterClientRegistry {
  private static final ClusterClientRegistry INSTANCE = new ClusterClientRegistry();
  private static final long TTL_MS = 2 * 60_000L;
  private static final int MAX_CLIENTS = 8192;

  private final Map<String, Entry> byClient = new ConcurrentHashMap<>();

  private ClusterClientRegistry() {}

  static ClusterClientRegistry get() {
    return INSTANCE;
  }

  void touch(String ownerNodeId, String remote, String peerId, byte role) {
    long now = System.currentTimeMillis();
    gc(now);
    String pid = peerId != null ? peerId.trim() : "";
    String rem = remote != null ? remote.trim() : "";
    String id = !pid.isEmpty() ? pid : rem;
    if (id.isEmpty()) {
      return;
    }
    evictIfNeeded();
    byClient.put(id, new Entry(id, pid, rem, ownerNodeId != null ? ownerNodeId : "", role, now));
  }

  void mergeFromJson(String raw) {
    if (raw == null || raw.isBlank()) {
      return;
    }
    long now = System.currentTimeMillis();
    gc(now);
    List<Entry> rows = parseRows(raw);
    for (Entry r : rows) {
      if (r.id.isBlank()) {
        continue;
      }
      Entry old = byClient.get(r.id);
      if (old == null || r.tsMs >= old.tsMs) {
        byClient.put(r.id, r);
      }
    }
  }

  String exportJson(String localNodeId) {
    long now = System.currentTimeMillis();
    gc(now);
    List<String> keys = new ArrayList<>(byClient.keySet());
    keys.sort(String::compareTo);
    StringBuilder sb = new StringBuilder(256 + keys.size() * 128);
    sb.append("{\"v\":1,\"nodeId\":\"").append(json(localNodeId)).append("\",");
    sb.append("\"generatedAt\":").append(now).append(",\"clients\":[");
    boolean first = true;
    for (String k : keys) {
      Entry e = byClient.get(k);
      if (e == null) {
        continue;
      }
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append("{\"id\":\"").append(json(e.id)).append("\",");
      sb.append("\"peerId\":\"").append(json(e.peerId)).append("\",");
      sb.append("\"remote\":\"").append(json(e.remote)).append("\",");
      sb.append("\"owner\":\"").append(json(e.ownerNodeId)).append("\",");
      sb.append("\"role\":").append(e.role & 0xff).append(",");
      sb.append("\"ts\":").append(e.tsMs).append("}");
    }
    sb.append("]}");
    return sb.toString();
  }

  private void evictIfNeeded() {
    while (byClient.size() >= MAX_CLIENTS) {
      String minKey = null;
      long minTs = Long.MAX_VALUE;
      for (Map.Entry<String, Entry> e : byClient.entrySet()) {
        if (e.getValue().tsMs <= minTs) {
          minTs = e.getValue().tsMs;
          minKey = e.getKey();
        }
      }
      if (minKey == null) {
        return;
      }
      byClient.remove(minKey);
    }
  }

  private void gc(long now) {
    byClient.entrySet().removeIf(e -> now - e.getValue().tsMs > TTL_MS);
  }

  private static List<Entry> parseRows(String json) {
    int i = json.indexOf("\"clients\"");
    if (i < 0) {
      return List.of();
    }
    int l = json.indexOf('[', i);
    int r = json.indexOf(']', l + 1);
    if (l < 0 || r <= l) {
      return List.of();
    }
    String arr = json.substring(l + 1, r);
    if (arr.isBlank()) {
      return List.of();
    }
    String[] objs = arr.split("\\},\\{");
    List<Entry> out = new ArrayList<>();
    for (String chunk : objs) {
      String c = chunk.trim();
      if (!c.startsWith("{")) {
        c = "{" + c;
      }
      if (!c.endsWith("}")) {
        c = c + "}";
      }
      String id = jsonStringField(c, "id");
      String peerId = jsonStringField(c, "peerId");
      String remote = jsonStringField(c, "remote");
      String owner = jsonStringField(c, "owner");
      long ts = jsonLongField(c, "ts");
      int roleI = (int) jsonLongField(c, "role");
      if (id == null || id.isBlank()) {
        continue;
      }
      out.add(new Entry(
          id,
          peerId != null ? peerId : "",
          remote != null ? remote : "",
          owner != null ? owner : "",
          (byte) roleI,
          ts > 0 ? ts : System.currentTimeMillis()));
    }
    return out;
  }

  private static String jsonStringField(String json, String key) {
    String needle = "\"" + key + "\"";
    int i = json.indexOf(needle);
    if (i < 0) {
      return null;
    }
    int colon = json.indexOf(':', i + needle.length());
    if (colon < 0) {
      return null;
    }
    int q1 = json.indexOf('"', colon + 1);
    if (q1 < 0) {
      return null;
    }
    int q2 = json.indexOf('"', q1 + 1);
    if (q2 < 0) {
      return null;
    }
    return json.substring(q1 + 1, q2);
  }

  private static long jsonLongField(String json, String key) {
    String needle = "\"" + key + "\"";
    int i = json.indexOf(needle);
    if (i < 0) {
      return 0L;
    }
    int colon = json.indexOf(':', i + needle.length());
    if (colon < 0) {
      return 0L;
    }
    int p = colon + 1;
    int n = json.length();
    while (p < n && Character.isWhitespace(json.charAt(p))) {
      p++;
    }
    int end = p;
    while (end < n && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
      end++;
    }
    if (end <= p) {
      return 0L;
    }
    try {
      return Long.parseLong(json.substring(p, end).trim());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private static String json(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static final class Entry {
    final String id;
    final String peerId;
    final String remote;
    final String ownerNodeId;
    final byte role;
    final long tsMs;

    Entry(String id, String peerId, String remote, String ownerNodeId, byte role, long tsMs) {
      this.id = id;
      this.peerId = peerId;
      this.remote = remote;
      this.ownerNodeId = ownerNodeId;
      this.role = role;
      this.tsMs = tsMs;
    }
  }
}
