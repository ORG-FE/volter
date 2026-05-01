package dev.c0redev.volter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class DhtRpcUdpServerTest {
  private static final byte OP_PING = 1;
  private static final byte OP_FIND_NODE = 2;
  private static final byte OP_RESP = (byte) 0x80;

  @Test
  void respondsToPingAndFindNode() throws Exception {
    int udpPort;
    try (DatagramSocket s = new DatagramSocket(0)) {
      udpPort = s.getLocalPort();
    }

    Path relayIndex = Files.createTempFile("relay-index", ".json");
    JSONObject root = new JSONObject();
    JSONArray nodes = new JSONArray();
    nodes.put(new JSONObject()
        .put("id", "node-a")
        .put("endpoints", new JSONArray().put("127.0.0.1:4433"))
        .put("dhtRpc", "127.0.0.1:" + udpPort));
    nodes.put(new JSONObject()
        .put("id", "node-b")
        .put("endpoints", new JSONArray().put("127.0.0.1:4434"))
        .put("dhtRpc", "127.0.0.1:" + udpPort));
    root.put("nodes", nodes);
    Files.writeString(relayIndex, root.toString(), StandardCharsets.UTF_8);

    Path cfgFile = Files.createTempFile("volter-server", ".properties");
    Files.writeString(cfgFile, """
        listenPorts=18080
        token=test-token
        udpChannels=4
        serverMode=tcp-only
        relayIndexFile=%s
        dhtRpcListenUdp=127.0.0.1:%d
        dhtRpcSecret=test-secret
        """.formatted(relayIndex.toAbsolutePath(), udpPort), StandardCharsets.UTF_8);

    Config cfg = Config.load(cfgFile);
    DhtRpcUdpServer srv = DhtRpcUdpServer.startIfEnabled(cfg);
    assertNotNull(srv);
    try (DatagramSocket client = new DatagramSocket()) {
      client.setSoTimeout(2500);
      InetSocketAddress dst = new InetSocketAddress("127.0.0.1", udpPort);

      int pingId = new SecureRandom().nextInt();
      byte[] pingReq = withMac("test-secret", encodePacket(pingId, OP_PING, new byte[0]));
      byte[] pingBody = roundTrip(client, dst, pingReq, "test-secret");
      Decoded ping = decodePacket(pingBody);
      assertEquals(pingId, ping.reqId());
      assertEquals((byte) (OP_PING | OP_RESP), ping.op());
      assertEquals(0, ping.payload().length);

      byte[] target = MessageDigest.getInstance("SHA-256").digest("node-a".getBytes(StandardCharsets.UTF_8));
      byte[] findPayload = new byte[33];
      System.arraycopy(target, 0, findPayload, 0, 32);
      findPayload[32] = 2;
      int findId = new SecureRandom().nextInt();
      byte[] findReq = withMac("test-secret", encodePacket(findId, OP_FIND_NODE, findPayload));
      byte[] findBody = roundTrip(client, dst, findReq, "test-secret");
      Decoded find = decodePacket(findBody);
      assertEquals(findId, find.reqId());
      assertEquals((byte) (OP_FIND_NODE | OP_RESP), find.op());
      JSONObject findJson = new JSONObject(new String(find.payload(), StandardCharsets.UTF_8));
      JSONArray out = findJson.getJSONArray("nodes");
      assertFalse(out.isEmpty());
      assertEquals("node-a", out.getJSONObject(0).getString("id"));
    } finally {
      srv.close();
    }
  }

  private static byte[] roundTrip(DatagramSocket c, InetSocketAddress dst, byte[] req, String secret) throws Exception {
    DatagramPacket out = new DatagramPacket(req, req.length, dst);
    c.send(out);
    byte[] buf = new byte[4096];
    DatagramPacket in = new DatagramPacket(buf, buf.length);
    c.receive(in);
    byte[] raw = new byte[in.getLength()];
    System.arraycopy(in.getData(), in.getOffset(), raw, 0, raw.length);
    byte[] body = stripMac(secret, raw);
    assertNotNull(body);
    return body;
  }

  private static byte[] encodePacket(int reqId, byte op, byte[] payload) {
    ByteBuffer b = ByteBuffer.allocate(12 + payload.length).order(ByteOrder.BIG_ENDIAN);
    b.put((byte) 'V').put((byte) 'L').put((byte) 'D').put((byte) 'R');
    b.put((byte) 1);
    b.put(op);
    b.putInt(reqId);
    b.putShort((short) payload.length);
    b.put(payload);
    return b.array();
  }

  private static Decoded decodePacket(byte[] raw) {
    ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
    assertEquals((byte) 'V', b.get());
    assertEquals((byte) 'L', b.get());
    assertEquals((byte) 'D', b.get());
    assertEquals((byte) 'R', b.get());
    assertEquals((byte) 1, b.get());
    byte op = b.get();
    int reqId = b.getInt();
    int len = b.getShort() & 0xffff;
    byte[] payload = new byte[len];
    b.get(payload);
    return new Decoded(reqId, op, payload);
  }

  private static byte[] withMac(String secret, byte[] body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] sig = mac.doFinal(body);
    byte[] out = new byte[body.length + sig.length];
    System.arraycopy(body, 0, out, 0, body.length);
    System.arraycopy(sig, 0, out, body.length, sig.length);
    return out;
  }

  private static byte[] stripMac(String secret, byte[] pkt) throws Exception {
    if (pkt.length < 32) return null;
    byte[] body = new byte[pkt.length - 32];
    System.arraycopy(pkt, 0, body, 0, body.length);
    byte[] got = new byte[32];
    System.arraycopy(pkt, body.length, got, 0, got.length);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] exp = mac.doFinal(body);
    if (!MessageDigest.isEqual(got, exp)) return null;
    return body;
  }

  private record Decoded(int reqId, byte op, byte[] payload) {}
}
