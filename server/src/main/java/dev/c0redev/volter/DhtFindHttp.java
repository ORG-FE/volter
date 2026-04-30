package dev.c0redev.volter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class DhtFindHttp {
  private static final long MAX_FILE = 2L * 1024 * 1024;

  private DhtFindHttp() {}

  static boolean tryServe(Config cfg, BufferedInputStream rawIn, OutputStream rawOut) throws IOException {
    String file = cfg.relayIndexFile();
    if (file == null || file.isBlank()) {
      return false;
    }
    rawIn.mark(65536);
    String line = readHttpLine(rawIn, 8192);
    String resource = httpRequestResource(line);
    if (resource == null) {
      rawIn.reset();
      return false;
    }
    int q = resource.indexOf('?');
    String pathOnly = q >= 0 ? resource.substring(0, q) : resource;
    if (!cfg.dhtFindPath().equals(pathOnly)) {
      rawIn.reset();
      return false;
    }
    String qs = q >= 0 ? resource.substring(q + 1) : "";
    Map<String, String> qm = parseQuery(qs);
    String targetHex = qm.getOrDefault("target", "").trim();
    if (targetHex.isEmpty()) {
      rawIn.reset();
      return false;
    }
    final byte[] target;
    try {
      target = hexToBytes(targetHex);
    } catch (IllegalArgumentException e) {
      rawIn.reset();
      return false;
    }
    if (target.length != 32) {
      rawIn.reset();
      return false;
    }
    int limit = 16;
    if (qm.containsKey("limit")) {
      try {
        limit = Integer.parseInt(qm.get("limit").trim());
      } catch (NumberFormatException ignored) {
        limit = 16;
      }
    }
    if (limit < 1) {
      limit = 1;
    }
    if (limit > 256) {
      limit = 256;
    }
    Path fp = Path.of(file);
    if (!Files.isRegularFile(fp)) {
      rawIn.reset();
      return false;
    }
    long sz = Files.size(fp);
    if (sz <= 0 || sz > MAX_FILE) {
      rawIn.reset();
      return false;
    }
    drainHttpHeaders(rawIn);
    byte[] raw = Files.readAllBytes(fp);
    JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
    JSONArray nodesIn = root.optJSONArray("nodes");
    if (nodesIn == null) {
      writeResponse(cfg, rawOut, new JSONArray());
      return true;
    }
    List<JSONObject> rows = new ArrayList<>();
    for (int i = 0; i < nodesIn.length(); i++) {
      JSONObject o = nodesIn.optJSONObject(i);
      if (o != null && !o.optString("id", "").isBlank()) {
        rows.add(o);
      }
    }
    rows.sort(Comparator.comparing(n -> keyXorTarget(n.optString("id", ""), target), UNSIGNED_BYTES));
    JSONArray out = new JSONArray();
    for (int i = 0; i < Math.min(limit, rows.size()); i++) {
      out.put(rows.get(i));
    }
    writeResponse(cfg, rawOut, out);
    return true;
  }

  private static final Comparator<byte[]> UNSIGNED_BYTES = DhtFindHttp::compareBytesLex;

  private static int compareBytesLex(byte[] a, byte[] b) {
    int n = Math.min(a.length, b.length);
    for (int i = 0; i < n; i++) {
      int x = a[i] & 0xff;
      int y = b[i] & 0xff;
      if (x != y) {
        return Integer.compare(x, y);
      }
    }
    return Integer.compare(a.length, b.length);
  }

  private static byte[] keyXorTarget(String id, byte[] target) {
    byte[] nid = sha256(id.getBytes(StandardCharsets.UTF_8));
    byte[] d = new byte[32];
    for (int i = 0; i < 32; i++) {
      d[i] = (byte) (nid[i] ^ target[i]);
    }
    return d;
  }

  private static byte[] sha256(byte[] in) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(in);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
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

  private static String httpRequestResource(String line) {
    if (line == null || !line.startsWith("GET ")) {
      return null;
    }
    int sp = line.indexOf(" HTTP/");
    if (sp < 0) {
      return null;
    }
    return line.substring(4, sp).trim();
  }

  private static Map<String, String> parseQuery(String qs) {
    Map<String, String> m = new HashMap<>();
    if (qs == null || qs.isEmpty()) {
      return m;
    }
    for (String part : qs.split("&")) {
      int eq = part.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String k = urlDecode(part.substring(0, eq)).trim();
      String v = urlDecode(part.substring(eq + 1)).trim();
      if (!k.isEmpty()) {
        m.put(k, v);
      }
    }
    return m;
  }

  private static String urlDecode(String s) {
    try {
      return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return s;
    }
  }

  private static byte[] hexToBytes(String hex) {
    String h = hex.trim();
    if ((h.length() & 1) != 0) {
      throw new IllegalArgumentException("bad hex len");
    }
    int n = h.length() / 2;
    byte[] out = new byte[n];
    for (int i = 0; i < n; i++) {
      out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }

  private static void writeResponse(Config cfg, OutputStream rawOut, JSONArray nodes) throws IOException {
    JSONObject body = new JSONObject();
    body.put("nodes", nodes);
    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
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
  }
}
