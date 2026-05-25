package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {

  static byte[] u16(int v) {
    return new byte[]{(byte) (v >>> 8), (byte) (v & 0xff)};
  }

  static byte[] u32(int v) {
    return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) (v & 0xff)};
  }

  @Test
  void readHandshakeTcp() throws IOException {
    byte[] tok = "secret".getBytes();
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write(Protocol.MAGIC);
    buf.write(Protocol.VERSION);
    buf.write(Protocol.ROLE_TCP);
    buf.write(u16(tok.length));
    buf.write(tok);
    buf.write(u16(0));
    var hr = Protocol.readHandshake(new BufferedInputStream(new ByteArrayInputStream(buf.toByteArray())));
    var hs = hr.handshake();
    assertEquals(Protocol.ROLE_TCP, hs.role());
    assertEquals("secret", hs.token());
    assertEquals(-1, hs.channelId());
  }

  @Test
  void readHandshakeTcpWithOptsThenReadTcpConnect() throws IOException {
    byte[] tok = "secret".getBytes();
    String opts = "{\"padS4\":40}";
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write(Protocol.MAGIC);
    buf.write(Protocol.VERSION);
    buf.write(Protocol.ROLE_TCP);
    buf.write(u16(tok.length));
    buf.write(tok);
    buf.write(u16(opts.length()));
    buf.write(opts.getBytes(StandardCharsets.UTF_8));
    buf.write(Protocol.ADDR_V4);
    buf.write(new byte[]{9, 9, 9, 9});
    buf.write(u16(443));
    var in = new BufferedInputStream(new ByteArrayInputStream(buf.toByteArray()));
    var hr = Protocol.readHandshake(in);
    assertTrue(hr.opts().isPresent());
    assertEquals(40, hr.opts().get().padS4());
    var c = Protocol.readTcpConnect(in);
    assertArrayEquals(InetAddress.getByAddress(new byte[]{9, 9, 9, 9}).getAddress(), c.ip().getAddress());
    assertEquals(443, c.port());
  }

  @Test
  void writeBridgeHandshakeTcpWithEmptyOptsThenTcpConnect() throws IOException {
    var out = new ByteArrayOutputStream();
    Protocol.writeVolterClientHandshake(out, Protocol.ROLE_TCP, "secret", null);
    Protocol.writeTcpConnectFrame(out, new Protocol.TcpConnect(
        Protocol.ADDR_V4,
        InetAddress.getByAddress(new byte[]{1, 1, 1, 1}),
        853));

    var in = new BufferedInputStream(new ByteArrayInputStream(out.toByteArray()));
    var hr = Protocol.readHandshake(in);
    assertEquals(Protocol.ROLE_TCP, hr.handshake().role());
    assertEquals("secret", hr.handshake().token());
    assertTrue(hr.opts().isEmpty());

    var c = Protocol.readTcpConnect(in);
    assertArrayEquals(InetAddress.getByAddress(new byte[]{1, 1, 1, 1}).getAddress(), c.ip().getAddress());
    assertEquals(853, c.port());
  }

  @Test
  void writeVolterClientHandshakeThroughXorKeepsMagicImmutable() throws IOException {
    byte[] magic = Protocol.MAGIC.clone();
    var raw = new ByteArrayOutputStream();
    var xorOut = new XorStream(XorStream.keyFromToken("secret")).wrapOutput(raw);

    Protocol.writeVolterClientHandshake(xorOut, Protocol.ROLE_TCP, "secret", null);
    Protocol.writeTcpConnectFrame(xorOut, new Protocol.TcpConnect(
        Protocol.ADDR_V4,
        InetAddress.getByAddress(new byte[]{8, 8, 8, 8}),
        853));
    xorOut.flush();

    assertArrayEquals(magic, Protocol.MAGIC);

    var xorIn = new XorStream(XorStream.keyFromToken("secret"))
        .wrapInput(new ByteArrayInputStream(raw.toByteArray()));
    var in = new BufferedInputStream(xorIn);
    var hr = Protocol.readHandshake(in);
    assertEquals(Protocol.ROLE_TCP, hr.handshake().role());
    assertEquals("secret", hr.handshake().token());
    var c = Protocol.readTcpConnect(in);
    assertArrayEquals(InetAddress.getByAddress(new byte[]{8, 8, 8, 8}).getAddress(), c.ip().getAddress());
    assertEquals(853, c.port());
  }

  @Test
  void writeVolterClientHandshakeThroughXorWorksTwiceInSameJvm() throws IOException {
    for (int i = 0; i < 2; i++) {
      var raw = new ByteArrayOutputStream();
      var xorOut = new XorStream(XorStream.keyFromToken("secret")).wrapOutput(raw);
      Protocol.writeVolterClientHandshake(xorOut, Protocol.ROLE_TCP, "secret", null);
      xorOut.flush();

      var xorIn = new XorStream(XorStream.keyFromToken("secret"))
          .wrapInput(new ByteArrayInputStream(raw.toByteArray()));
      var hr = Protocol.readHandshake(new BufferedInputStream(xorIn));
      assertEquals(Protocol.ROLE_TCP, hr.handshake().role());
      assertEquals("secret", hr.handshake().token());
      assertArrayEquals(new byte[]{'V', 'O', 'L', 'T', 1}, Protocol.MAGIC);
    }
  }

  @Test
  void readHandshakeRelayTcp() throws IOException {
    byte[] tok = "secret".getBytes();
    String opts = "{\"relayHop\":1,\"relayMaxHop\":2,\"relayBudgetKbps\":512,\"peerId\":\"p1\",\"relayNonce\":\"n1\",\"relaySig\":\"s1\"}";
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write(Protocol.MAGIC);
    buf.write(Protocol.VERSION);
    buf.write(Protocol.ROLE_RELAY_TCP);
    buf.write(u16(tok.length));
    buf.write(tok);
    buf.write(u16(opts.length()));
    buf.write(opts.getBytes(StandardCharsets.UTF_8));
    var in = new BufferedInputStream(new ByteArrayInputStream(buf.toByteArray()));
    var hr = Protocol.readHandshake(in);
    assertEquals(Protocol.ROLE_RELAY_TCP, hr.handshake().role());
    assertTrue(hr.opts().isPresent());
    assertEquals(1, hr.opts().get().relayHop());
    assertEquals(2, hr.opts().get().relayMaxHop());
    assertEquals(512, hr.opts().get().relayBudgetKbps());
  }

  @Test
  void readHandshakeUdp() throws IOException {
    byte[] tok = "x".getBytes();
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write(Protocol.MAGIC);
    buf.write(Protocol.VERSION);
    buf.write(Protocol.ROLE_UDP);
    buf.write(u16(tok.length));
    buf.write(tok);
    buf.write(3);
    buf.write(u16(0));
    var hr = Protocol.readHandshake(new BufferedInputStream(new ByteArrayInputStream(buf.toByteArray())));
    var hs = hr.handshake();
    assertEquals(Protocol.ROLE_UDP, hs.role());
    assertEquals(3, hs.channelId());
  }

  @Test
  void readHandshakeBadMagic() {
    var in = new ByteArrayInputStream("XXXXX".getBytes());
    assertThrows(IOException.class, () -> Protocol.readHandshake(in));
  }

  @Test
  void readTcpConnect() throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write(Protocol.ADDR_V4);
    buf.write(new byte[]{1, 2, 3, 4});
    buf.write(u16(443));
    var c = Protocol.readTcpConnect(new ByteArrayInputStream(buf.toByteArray()));
    assertArrayEquals(InetAddress.getByAddress(new byte[]{1, 2, 3, 4}).getAddress(), c.ip().getAddress());
    assertEquals(443, c.port());
  }

  @Test
  void udpFrameRoundtrip() throws IOException {
    var f = new Protocol.UdpFrame(Protocol.ADDR_V4, 12345,
        InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), 53,
        new byte[]{1, 2, 3, 4, 5});
    var out = new ByteArrayOutputStream();
    Protocol.writeUdpFrame(out, f);
    var got = Protocol.readUdpFrame(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(f.srcPort(), got.srcPort());
    assertEquals(f.dstPort(), got.dstPort());
    assertArrayEquals(f.dst().getAddress(), got.dst().getAddress());
    assertArrayEquals(f.payload(), got.payload());
  }

  @Test
  void skipUntilMagicWithPrefix() throws IOException {
    byte[] pad = new byte[]{0x00, 0x01, 0x02, 0x03};
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write(pad);
    buf.write(Protocol.MAGIC);
    buf.write(Protocol.VERSION);
    buf.write(Protocol.ROLE_UDP);
    buf.write(u16(1));
    buf.write('x');
    buf.write(0);
    buf.write(u16(0));
    var hr = Protocol.readHandshake(new BufferedInputStream(new ByteArrayInputStream(buf.toByteArray())));
    var hs = hr.handshake();
    assertEquals(Protocol.ROLE_UDP, hs.role());
    assertEquals("x", hs.token());
  }

  @Test
  void clientOptionsParse() {
    String sig = hmacSig("token", "p1", "n1");
    var opt = Protocol.ClientOptions.parse("{\"padS4\":48,\"relayHop\":1,\"relayMaxHop\":3,\"relayBudgetKbps\":2048,\"peerId\":\"p1\",\"relayNonce\":\"n1\",\"relaySig\":\"" + sig + "\"}");
    assertTrue(opt.isPresent());
    assertEquals(48, opt.get().padS4());
    assertEquals(1, opt.get().relayHop());
    assertEquals(3, opt.get().relayMaxHop());
    assertEquals(2048, opt.get().relayBudgetKbps());
    assertEquals("p1", opt.get().peerId());
  }

  static String hmacSig(String token, String peerId, String nonce) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(peerId.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) '|');
      mac.update(nonce.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void clientOptionsParseEmpty() {
    var opt = Protocol.ClientOptions.parse("{}");
    assertTrue(opt.isPresent());
    assertEquals(32, opt.get().padS4());
    assertEquals(0, opt.get().relayHop());
    assertEquals(2, opt.get().relayMaxHop());
  }

  @Test
  void clientOptionsParseRelayRouteHops() {
    String sig = hmacSig("token", "p1", "n1");
    var opt =
        Protocol.ClientOptions.parse(
            "{\"relayHop\":1,\"hopIndex\":1,\"relayMaxHop\":2,\"peerId\":\"p1\",\"relayNonce\":\"n1\",\"relaySig\":\""
                + sig
                + "\",\"relayRouteHops\":[\"peer_tcp:1.2.3.4:5\",\"peer_tcp:6.7.8.9:10\"]}");
    assertTrue(opt.isPresent());
    assertEquals(2, opt.get().relayRouteHops().size());
    assertEquals("peer_tcp:1.2.3.4:5", opt.get().relayRouteHops().get(0));
    assertEquals(1, opt.get().hopIndex());
  }

  @Test
  void udpFrameEmptyPayload() throws IOException {
    var f = new Protocol.UdpFrame(Protocol.ADDR_V4, 0,
        InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 53,
        new byte[0]);
    var out = new ByteArrayOutputStream();
    Protocol.writeUdpFrame(out, f);
    var got = Protocol.readUdpFrame(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(0, got.payload().length);
  }

  @Test
  void serverHelloCapsRoundtrip() throws IOException {
    var caps = new Protocol.ServerHelloCaps(
        Protocol.CAPS_VERSION,
        1,
        Protocol.TRANSPORT_TCP | Protocol.TRANSPORT_QUIC,
        Protocol.FEAT_IPV6 | Protocol.FEAT_RELAY_SERVER,
        7443,
        8443,
        2,
        new byte[]{9, 8, 7},
        null,
        2,
        3,
        5);
    var out = new ByteArrayOutputStream();
    Protocol.writeServerHelloCaps(out, caps);
    var got = Protocol.readServerHelloCaps(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(caps.version(), got.version());
    assertEquals(caps.transportMask(), got.transportMask());
    assertEquals(caps.featureBits(), got.featureBits());
    assertEquals(caps.quicPort(), got.quicPort());
    assertEquals(caps.tcpPortHint(), got.tcpPortHint());
    assertArrayEquals(caps.nonce(), got.nonce());
    assertEquals(caps.relayClass(), got.relayClass());
    assertEquals(caps.pathTtl(), got.pathTtl());
    assertEquals(caps.relayFlags(), got.relayFlags());
    assertNull(got.quicLeafPinSha256());
  }

  @Test
  void serverHelloCapsRoundtripWithQuicPin() throws IOException {
    byte[] pin = new byte[32];
    for (int i = 0; i < 32; i++) pin[i] = (byte) i;
    var caps = new Protocol.ServerHelloCaps(
        Protocol.CAPS_VERSION,
        0,
        Protocol.TRANSPORT_QUIC,
        0,
        4433,
        0,
        0,
        new byte[]{1},
        pin,
        0,
        0,
        0);
    var out = new ByteArrayOutputStream();
    Protocol.writeServerHelloCaps(out, caps);
    var got = Protocol.readServerHelloCaps(new ByteArrayInputStream(out.toByteArray()));
    assertArrayEquals(pin, got.quicLeafPinSha256());
  }

  @Test
  void serverHelloCapsUnknownExtensionFails() {
    byte[] wire = new byte[] {
        (byte) Protocol.CAPS_VERSION, 0, (byte) Protocol.TRANSPORT_TCP,
        0, 0, 0, 0, 0, 0, 0, 0,
        0x7f, 0x01, (byte) 0xaa
    };
    assertThrows(IOException.class, () -> Protocol.readServerHelloCaps(new ByteArrayInputStream(wire)));
  }
}
