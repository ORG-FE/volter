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

  @Test
  void clientOptionsParseManagedAuth() {
    var opt = Protocol.ClientOptions.parse("{\"managedClientId\":\"cli_1\",\"managedDeviceId\":\"android:abc\",\"managedNonce\":\"n1\",\"managedTsSec\":123,\"managedSig\":\"sig\"}");
    assertTrue(opt.isPresent());
    assertEquals("cli_1", opt.get().managedClientId());
    assertEquals("android:abc", opt.get().managedDeviceId());
    assertEquals("n1", opt.get().managedNonce());
    assertEquals(123, opt.get().managedTsSec());
    assertEquals("sig", opt.get().managedSig());
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
