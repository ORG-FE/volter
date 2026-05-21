package dev.c0redev.volter;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

final class DhtRpcUdpServer implements Closeable {
  private static final Logger log = Log.logger(DhtRpcUdpServer.class);

  private static final byte[] MAGIC = new byte[] {'V', 'L', 'D', 'R'};
  private static final byte VER = 1;
  private static final int HDR = 12;
  private static final int MAC_LEN = 32;
  private static final int MAX_PACKET = 65507;
  private static final int MAX_PAYLOAD = 48_000;
  private static final int MAX_FIND_LIMIT = 256;
  private static final int MAX_KV_VALUE = 16 * 1024;

  private static final byte OP_PING = 1;
  private static final byte OP_FIND_NODE = 2;
  private static final byte OP_STORE = 3;
  private static final byte OP_GET = 4;
  private static final byte OP_RESP = (byte) 0x80;

  private final Config cfg;
  private final DatagramSocket socket;
  private final Thread worker;
  private final ScheduledExecutorService kvCleaner;
  private final Map<String, KvEntry> kv = new ConcurrentHashMap<>();
  private volatile boolean running = true;

  private DhtRpcUdpServer(Config cfg, DatagramSocket socket) {
    this.cfg = cfg;
    this.socket = socket;
    this.worker = new Thread(this::loop, "dht-rpc-udp");
    this.worker.setDaemon(true);
    this.kvCleaner = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "dht-kv-clean");
      t.setDaemon(true);
      return t;
    });
    this.kvCleaner.scheduleAtFixedRate(this::purgeExpiredKv, 30, 30, TimeUnit.SECONDS);
  }

  static DhtRpcUdpServer startIfEnabled(Config cfg) throws IOException {
    String listen = cfg.dhtRpcListenUdp();
    if (listen == null || listen.isBlank()) {
      return null;
    }
    InetSocketAddress addr = parseListenAddr(listen.trim());
    DatagramSocket socket = new DatagramSocket(addr);
    DhtRpcUdpServer srv = new DhtRpcUdpServer(cfg, socket);
    srv.worker.start();
    log.info("DHT RPC UDP listening on " + addr);
    return srv;
  }

  @Override
  public void close() {
    running = false;
    kvCleaner.shutdownNow();
    socket.close();
    worker.interrupt();
  }

  private void purgeExpiredKv() {
    long now = Instant.now().toEpochMilli();
    kv.entrySet().removeIf(e -> e.getValue().expMs < now);
  }

  private void loop() {
    byte[] buf = new byte[MAX_PACKET];
    while (running) {
      try {
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        socket.receive(pkt);
        byte[] in = Arrays.copyOfRange(pkt.getData(), pkt.getOffset(), pkt.getOffset() + pkt.getLength());
        byte[] body = verifyMac(cfg.dhtRpcSecret(), in);
        if (body == null) {
          continue;
        }
        RpcPacket req = decodePacket(body);
        if (req == null) {
          continue;
        }
        if ((req.op & OP_RESP) != 0) {
          continue;
        }
        byte[] out = handle(req.reqId, req.op, req.payload);
        if (out == null) {
          continue;
        }
        out = appendMac(cfg.dhtRpcSecret(), out);
        DatagramPacket resp = new DatagramPacket(out, out.length, pkt.getSocketAddress());
        socket.send(resp);
      } catch (SocketException e) {
        if (running) {
          log.warning("DHT RPC UDP socket: " + e.getMessage());
        }
        return;
      } catch (Exception e) {
        log.fine("DHT RPC UDP packet error: " + e.getMessage());
      }
    }
  }

  private byte[] handle(int reqId, byte op, byte[] payload) {
    try {
      switch (op) {
        case OP_PING:
          return encodePacket(reqId, (byte) (OP_PING | OP_RESP), new byte[0]);
        case OP_FIND_NODE:
          return handleFindNode(reqId, payload);
        case OP_STORE:
          return handleStore(reqId, payload);
        case OP_GET:
          return handleGet(reqId, payload);
        default:
          return null;
      }
    } catch (Exception e) {
      log.fine("DHT RPC op error: " + e.getMessage());
      return null;
    }
  }

  private byte[] handleFindNode(int reqId, byte[] payload) {
    if (payload == null || payload.length < 33) {
      return null;
    }
    byte[] target = Arrays.copyOfRange(payload, 0, 32);
    int limit = payload[32] & 0xff;
    if (limit < 1) limit = 1;
    if (limit > MAX_FIND_LIMIT) limit = MAX_FIND_LIMIT;

    JSONArray src = loadRelayNodesArray();
    ArrayList<NodeDist> list = new ArrayList<>();
    for (int i = 0; i < src.length(); i++) {
      JSONObject n = src.optJSONObject(i);
      if (n == null) continue;
      String id = n.optString("id", "").trim();
      if (id.isEmpty()) continue;
      byte[] xid = xor(sha256(id.getBytes(StandardCharsets.UTF_8)), target);
      list.add(new NodeDist(n, xid));
    }
    list.sort(Comparator.comparing(a -> a.dist, DhtRpcUdpServer::compareUnsigned));
    JSONArray outNodes = new JSONArray();
    for (int i = 0; i < Math.min(limit, list.size()); i++) {
      outNodes.put(list.get(i).node);
    }
    JSONObject body = new JSONObject();
    body.put("nodes", outNodes);
    byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
    return encodePacket(reqId, (byte) (OP_FIND_NODE | OP_RESP), bodyBytes);
  }

  private byte[] handleStore(int reqId, byte[] payload) {
    JSONObject in = new JSONObject(new String(payload, StandardCharsets.UTF_8));
    String key = in.optString("key", "").trim().toLowerCase();
    int ttlSec = in.optInt("ttlSec", 0);
    String hexPayload = in.optString("payload", "");
    if (key.length() != 64 || ttlSec <= 0) {
      return encodeStoreResp(reqId, false);
    }
    byte[] val;
    try {
      val = hexToBytes(hexPayload);
    } catch (Exception e) {
      return encodeStoreResp(reqId, false);
    }
    if (val.length > MAX_KV_VALUE) {
      val = Arrays.copyOf(val, MAX_KV_VALUE);
    }
    long expMs = Instant.now().toEpochMilli() + (ttlSec * 1000L);
    kv.put(key, new KvEntry(val, expMs));
    return encodeStoreResp(reqId, true);
  }

  private byte[] handleGet(int reqId, byte[] payload) {
    JSONObject in = new JSONObject(new String(payload, StandardCharsets.UTF_8));
    String key = in.optString("key", "").trim().toLowerCase();
    JSONObject out = new JSONObject();
    KvEntry e = kv.get(key);
    if (e == null || e.expMs < Instant.now().toEpochMilli()) {
      kv.remove(key);
      out.put("found", false);
    } else {
      out.put("found", true);
      out.put("payload", bytesToHex(e.val));
    }
    return encodePacket(reqId, (byte) (OP_GET | OP_RESP), out.toString().getBytes(StandardCharsets.UTF_8));
  }

  private byte[] encodeStoreResp(int reqId, boolean ok) {
    JSONObject out = new JSONObject();
    out.put("ok", ok);
    return encodePacket(reqId, (byte) (OP_STORE | OP_RESP), out.toString().getBytes(StandardCharsets.UTF_8));
  }

  private JSONArray loadRelayNodesArray() {
    String f = cfg.relayIndexFile();
    if (f == null || f.isBlank()) {
      f = cfg.gossipIndexFile();
    }
    if (f == null || f.isBlank()) {
      return new JSONArray();
    }
    try {
      Path p = Path.of(f.trim());
      if (!Files.isRegularFile(p)) return new JSONArray();
      byte[] raw = Files.readAllBytes(p);
      JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
      JSONArray arr = root.optJSONArray("nodes");
      return arr != null ? arr : new JSONArray();
    } catch (Exception e) {
      log.fine("DHT RPC load nodes: " + e.getMessage());
      return new JSONArray();
    }
  }

  private static byte[] encodePacket(int reqId, byte op, byte[] payload) {
    if (payload == null) payload = new byte[0];
    if (payload.length > MAX_PAYLOAD) {
      payload = Arrays.copyOf(payload, MAX_PAYLOAD);
    }
    ByteBuffer b = ByteBuffer.allocate(HDR + payload.length).order(ByteOrder.BIG_ENDIAN);
    b.put(MAGIC[0]).put(MAGIC[1]).put(MAGIC[2]).put(MAGIC[3]);
    b.put(VER);
    b.put(op);
    b.putInt(reqId);
    b.putShort((short) payload.length);
    b.put(payload);
    return b.array();
  }

  private static RpcPacket decodePacket(byte[] pkt) {
    if (pkt == null || pkt.length < HDR) return null;
    if (pkt[0] != MAGIC[0] || pkt[1] != MAGIC[1] || pkt[2] != MAGIC[2] || pkt[3] != MAGIC[3]) return null;
    if (pkt[4] != VER) return null;
    byte op = pkt[5];
    int reqId = ByteBuffer.wrap(pkt, 6, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    int len = ByteBuffer.wrap(pkt, 10, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xffff;
    if (HDR + len > pkt.length) return null;
    byte[] payload = Arrays.copyOfRange(pkt, HDR, HDR + len);
    return new RpcPacket(reqId, op, payload);
  }

  private static byte[] appendMac(String secret, byte[] pkt) {
    if (secret == null || secret.isBlank()) return pkt;
    byte[] mac = hmacSha256(secret.getBytes(StandardCharsets.UTF_8), pkt);
    byte[] out = Arrays.copyOf(pkt, pkt.length + MAC_LEN);
    System.arraycopy(mac, 0, out, pkt.length, MAC_LEN);
    return out;
  }

  private static byte[] verifyMac(String secret, byte[] pkt) {
    if (secret == null || secret.isBlank()) return pkt;
    if (pkt == null || pkt.length < MAC_LEN) return null;
    byte[] body = Arrays.copyOf(pkt, pkt.length - MAC_LEN);
    byte[] got = Arrays.copyOfRange(pkt, pkt.length - MAC_LEN, pkt.length);
    byte[] exp = hmacSha256(secret.getBytes(StandardCharsets.UTF_8), body);
    if (!MessageDigest.isEqual(got, exp)) return null;
    return body;
  }

  private static byte[] hmacSha256(byte[] key, byte[] msg) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(msg);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] sha256(byte[] in) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(in);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] xor(byte[] a, byte[] b) {
    byte[] out = new byte[Math.min(a.length, b.length)];
    for (int i = 0; i < out.length; i++) out[i] = (byte) (a[i] ^ b[i]);
    return out;
  }

  private static int compareUnsigned(byte[] a, byte[] b) {
    int n = Math.min(a.length, b.length);
    for (int i = 0; i < n; i++) {
      int x = a[i] & 0xff;
      int y = b[i] & 0xff;
      if (x != y) return Integer.compare(x, y);
    }
    return Integer.compare(a.length, b.length);
  }

  private static InetSocketAddress parseListenAddr(String listen) {
    String s = listen.trim();
    if (s.isEmpty()) throw new IllegalArgumentException("empty dhtRpcListenUdp");
    if (s.startsWith(":")) {
      int p = Integer.parseInt(s.substring(1));
      return new InetSocketAddress("0.0.0.0", p);
    }
    if (s.startsWith("[")) {
      int end = s.indexOf(']');
      if (end <= 0 || end + 2 > s.length() || s.charAt(end + 1) != ':') {
        throw new IllegalArgumentException("bad dhtRpcListenUdp");
      }
      String host = s.substring(1, end);
      int p = Integer.parseInt(s.substring(end + 2));
      return new InetSocketAddress(host, p);
    }
    int idx = s.lastIndexOf(':');
    if (idx <= 0 || idx == s.length() - 1) {
      throw new IllegalArgumentException("bad dhtRpcListenUdp");
    }
    String host = s.substring(0, idx);
    int p = Integer.parseInt(s.substring(idx + 1));
    return new InetSocketAddress(host, p);
  }

  private static byte[] hexToBytes(String h) {
    String s = h == null ? "" : h.trim();
    if ((s.length() & 1) != 0) throw new IllegalArgumentException("bad hex");
    byte[] out = new byte[s.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }

  private static String bytesToHex(byte[] in) {
    StringBuilder b = new StringBuilder(in.length * 2);
    for (byte x : in) b.append(String.format("%02x", x));
    return b.toString();
  }

  private record RpcPacket(int reqId, byte op, byte[] payload) {}
  private record NodeDist(JSONObject node, byte[] dist) {}
  private record KvEntry(byte[] val, long expMs) {}
}
