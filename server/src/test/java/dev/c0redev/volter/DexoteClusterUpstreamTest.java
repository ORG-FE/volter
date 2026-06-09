package dev.c0redev.volter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DexoteClusterUpstreamTest {

  private static byte[] fixedScalar() {
    byte[] s = new byte[32];
    for (int i = 0; i < 32; i++) s[i] = (byte) (i * 5 + 3);
    return s;
  }

  @Test
  void relayDialsDexoteUpstreamAndBridges() throws Exception {
    byte[] scalar = fixedScalar();
    byte[] pub = Dexote.pubFromScalar(scalar);
    long slot = Dexote.effectiveSlot(System.currentTimeMillis() / 1000L, 0);
    String token = "cluster-token";
    byte[] opts = "{\"relayHop\":1}".getBytes(StandardCharsets.UTF_8);

    InetAddress dstIp = InetAddress.getByName("93.184.216.34");
    int dstPort = 8443;

    AtomicReference<String> upErr = new AtomicReference<>();
    AtomicReference<Protocol.TcpConnect> gotConnect = new AtomicReference<>();
    AtomicReference<Byte> gotRole = new AtomicReference<>();
    AtomicReference<String> gotToken = new AtomicReference<>();
    AtomicReference<byte[]> gotOpts = new AtomicReference<>();

    try (ServerSocket ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
      ss.setSoTimeout(15000);
      int port = ss.getLocalPort();

      Thread upstream = new Thread(() -> {
        try (Socket c = ss.accept()) {
          c.setTcpNoDelay(true);
          InputStream rawIn = c.getInputStream();
          OutputStream rawOut = c.getOutputStream();
          Dexote.Accepted acc = Dexote.accept(
              rawIn, rawOut, scalar, pub, Dexote.candidateSlots(System.currentTimeMillis() / 1000L, 0),
              new byte[] {9, 9}, (n, ts) -> true);
          if (acc == null) {
            upErr.set("accept rejected");
            return;
          }
          gotRole.set(acc.hello.role);
          gotToken.set(acc.hello.token);
          gotOpts.set(acc.hello.opts);
          AeadStream as = new AeadStream(acc.keys, new Poly(acc.keys.secret, acc.slot, "tx"));
          InputStream in = as.wrapInput(rawIn);
          OutputStream out = as.wrapOutput(rawOut);

          Protocol.TcpConnect tc = Protocol.readTcpConnect(in);
          gotConnect.set(tc);

          byte[] buf = new byte[4096];
          int n = in.read(buf);
          if (n > 0) {
            out.write(buf, 0, n);
            out.flush();
          }
          Thread.sleep(150);
        } catch (Exception e) {
          upErr.set("upstream: " + e);
        }
      });
      upstream.setDaemon(true);
      upstream.start();

      try (Socket relay = new Socket("127.0.0.1", port)) {
        relay.setTcpNoDelay(true);
        DexoteUpstream.Streams up = DexoteUpstream.dial(
            relay, pub, slot, Protocol.ROLE_RELAY_TCP, token, opts, 5000);
        DexoteUpstream.writeTcpConnect(up.out, new Protocol.TcpConnect(Protocol.ADDR_V4, dstIp, dstPort));

        byte[] msg = "relay-through-dexote-upstream".getBytes(StandardCharsets.UTF_8);
        up.out.write(msg);
        up.out.flush();

        byte[] got = new byte[msg.length];
        int off = 0;
        while (off < got.length) {
          int r = up.in.read(got, off, got.length - off);
          assertTrue(r > 0, "echo ended early at " + off);
          off += r;
        }
        assertArrayEquals(msg, got, "echo mismatch through AEAD");
      }

      upstream.join(3000);
    }

    assertEquals(null, upErr.get(), "upstream error");
    assertEquals(Protocol.ROLE_RELAY_TCP, gotRole.get().byteValue(), "role");
    assertEquals(token, gotToken.get(), "token");
    assertArrayEquals(opts, gotOpts.get(), "opts JSON rode inside encrypted ClientHello");
    Protocol.TcpConnect tc = gotConnect.get();
    assertNotNull(tc, "upstream never read TcpConnect");
    assertEquals("93.184.216.34", tc.ip().getHostAddress(), "dst ip");
    assertEquals(dstPort, tc.port(), "dst port");
  }

  @Test
  void wrongUpstreamKeyIsRejected() throws Exception {
    byte[] scalar = fixedScalar();
    byte[] pub = Dexote.pubFromScalar(scalar);
    byte[] wrongPub = Dexote.pubFromScalar(new byte[32]);
    long slot = Dexote.effectiveSlot(System.currentTimeMillis() / 1000L, 0);

    try (ServerSocket ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
      ss.setSoTimeout(8000);
      int port = ss.getLocalPort();

      Thread upstream = new Thread(() -> {
        try (Socket c = ss.accept()) {
          InputStream rawIn = c.getInputStream();
          OutputStream rawOut = c.getOutputStream();

          Dexote.Accepted acc = Dexote.accept(
              rawIn, rawOut, scalar, pub, Dexote.candidateSlots(System.currentTimeMillis() / 1000L, 0),
              new byte[] {1}, (n, ts) -> true);

          if (acc != null) {

            Thread.sleep(50);
          }
        } catch (Exception ignored) {
        }
      });
      upstream.setDaemon(true);
      upstream.start();

      try (Socket relay = new Socket("127.0.0.1", port)) {
        relay.setTcpNoDelay(true);

        assertThrows(IOException.class, () ->
            DexoteUpstream.dial(relay, wrongPub, slot, Protocol.ROLE_TCP, "t", null, 3000));
      }
      upstream.join(2000);
    }
  }
}
