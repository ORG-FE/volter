package dev.c0redev.volter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

final class SessionResumeRegistry {
  private static final Logger log = Log.logger(SessionResumeRegistry.class);
  private static final SessionResumeRegistry INSTANCE = new SessionResumeRegistry();
  private static final long TTL_MS = 15 * 60_000L;
  private static final int MAX_SESSIONS = 4096;
  private static final int MERGE_MAX_BYTES = 512 * 1024;

  private final Map<String, Entry> bySession = new ConcurrentHashMap<>();

  private SessionResumeRegistry() {}

  static SessionResumeRegistry get() {
    return INSTANCE;
  }

  boolean accept(String sessionId, String resumeToken, String remote, String ownerNodeId) {
    if (sessionId == null || sessionId.isBlank() || resumeToken == null || resumeToken.isBlank()) {
      return true;
    }
    long now = System.currentTimeMillis();
    gc(now);
    evictIfNeeded();
    String own = ownerNodeId != null ? ownerNodeId : "";
    Entry old = bySession.get(sessionId);
    if (old == null) {
      bySession.put(sessionId, new Entry(resumeToken, remote, now, own));
      return true;
    }
    if (!old.resumeToken.equals(resumeToken)) {
      return false;
    }
    bySession.put(sessionId, new Entry(resumeToken, remote, now, own));
    return true;
  }

  void mergeFromJson(String raw) {
    if (raw == null || raw.isBlank()) {
      return;
    }
    if (raw.length() > MERGE_MAX_BYTES) {
      log.fine("cluster sessions merge: body too large");
      return;
    }
    long now = System.currentTimeMillis();
    gc(now);
    List<EntryRow> rows = parseSessionRows(raw);
    for (EntryRow r : rows) {
      if (r.sessionId.isEmpty() || r.resumeToken.isEmpty()) {
        continue;
      }
      Entry old = bySession.get(r.sessionId);
      if (old == null) {
        evictIfNeeded();
        bySession.put(r.sessionId, new Entry(r.resumeToken, "", r.ts > 0 ? r.ts : now, r.owner));
        continue;
      }
      if (old.resumeToken.equals(r.resumeToken)) {
        long ts = Math.max(old.tsMs, r.ts > 0 ? r.ts : now);
        bySession.put(r.sessionId, new Entry(old.resumeToken, old.remote, ts, old.ownerNodeId));
        continue;
      }
      log.fine("cluster sessions merge: skip conflict sid=" + r.sessionId);
    }
  }

  String exportJson(String localNodeId) {
    long now = System.currentTimeMillis();
    gc(now);
    List<String> keys = new ArrayList<>(bySession.keySet());
    keys.sort(String::compareTo);
    StringBuilder sb = new StringBuilder(256 + keys.size() * 120);
    sb.append("{\"v\":1,\"nodeId\":\"").append(json(localNodeId)).append("\",");
    sb.append("\"generatedAt\":").append(now).append(",\"sessions\":[");
    boolean first = true;
    for (String sid : keys) {
      Entry e = bySession.get(sid);
      if (e == null) {
        continue;
      }
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append("{\"sessionId\":\"").append(json(sid)).append("\",");
      sb.append("\"resumeToken\":\"").append(json(e.resumeToken)).append("\",");
      sb.append("\"owner\":\"").append(json(e.ownerNodeId)).append("\",");
      sb.append("\"ts\":").append(e.tsMs).append('}');
    }
    sb.append("]}");
    return sb.toString();
  }

  private void evictIfNeeded() {
    while (bySession.size() >= MAX_SESSIONS) {
      String minKey = null;
      long minTs = Long.MAX_VALUE;
      for (Map.Entry<String, Entry> e : bySession.entrySet()) {
        if (e.getValue().tsMs <= minTs) {
          minTs = e.getValue().tsMs;
          minKey = e.getKey();
        }
      }
      if (minKey == null) {
        return;
      }
      bySession.remove(minKey);
    }
  }

  private void gc(long now) {
    bySession.entrySet().removeIf(e -> now - e.getValue().tsMs > TTL_MS);
  }

  private static List<EntryRow> parseSessionRows(String json) {
    int i = json.indexOf("\"sessions\"");
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
    List<EntryRow> out = new ArrayList<>();
    for (String chunk : objs) {
      String c = chunk.trim();
      if (!c.startsWith("{")) {
        c = "{" + c;
      }
      if (!c.endsWith("}")) {
        c = c + "}";
      }
      String sid = jsonStringField(c, "sessionId");
      String tok = jsonStringField(c, "resumeToken");
      String owner = jsonStringField(c, "owner");
      if (owner == null || owner.isEmpty()) {
        owner = jsonStringField(c, "ownerNodeId");
      }
      if (owner == null) {
        owner = "";
      }
      long ts = jsonLongField(c, "ts");
      if (sid != null && !sid.isEmpty() && tok != null && !tok.isEmpty()) {
        out.add(new EntryRow(sid, tok, owner != null ? owner : "", ts));
      }
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
    if (p < n && json.charAt(p) == '"') {
      int q2 = json.indexOf('"', p + 1);
      if (q2 > p) {
        try {
          return Long.parseLong(json.substring(p + 1, q2).trim());
        } catch (NumberFormatException e) {
          return 0L;
        }
      }
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
    final String resumeToken;
    final String remote;
    final long tsMs;
    final String ownerNodeId;

    Entry(String resumeToken, String remote, long tsMs, String ownerNodeId) {
      this.resumeToken = resumeToken;
      this.remote = remote;
      this.tsMs = tsMs;
      this.ownerNodeId = ownerNodeId != null ? ownerNodeId : "";
    }
  }

  private record EntryRow(String sessionId, String resumeToken, String owner, long ts) {}
}
