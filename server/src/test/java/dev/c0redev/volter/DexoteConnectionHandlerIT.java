package dev.c0redev.volter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class DexoteConnectionHandlerIT {

  @Test
  void dexoteClientThroughHandlerToEcho() throws Exception {

    ServerSocket echo = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    Thread echoThread = new Thread(() -> {
      try (Socket d = echo.accept()) {
        InputStream din = d.getInputStream();
        OutputStream dout = d.getOutputStream();
        byte[] buf = new byte[4096];
        int n = din.read(buf);
        if (n > 0) {
          dout.write(buf, 0, n);
          dout.flush();
        }

      } catch (Exception ignored) {
      }
    });
    echoThread.setDaemon(true);
    echoThread.start();
    int echoPort = echo.getLocalPort();

    Path cfgFile = Files.createTempFile("volter-it", ".properties");
    Files.writeString(cfgFile,
        "listenPorts=18099\ntoken=it-token\nudpChannels=4\nserverMode=tcp-only\ncamouflageTcpEnabled=false\npeerRelayEnabled=false\n",
        StandardCharsets.UTF_8);
    Config cfg = Config.load(cfgFile);

    Path keyFile = Files.createTempFile("dexote-it", ".key");
    Files.deleteIfExists(keyFile);
    DexoteServerKey key = DexoteServerKey.loadOrCreate(keyFile);
    Dexote.ReplayCache replay = new MemReplayCache();

    UdpSessions udp = new UdpSessions(4);
    ExecutorService handshakePool = Executors.newCachedThreadPool();
    ExecutorService streamPool = Executors.newCachedThreadPool();

    ServerSocket listen = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    int serverPort = listen.getLocalPort();
    Thread acceptThread = new Thread(() -> {
      try {
        Socket s = listen.accept();
        s.setTcpNoDelay(true);
        new ConnectionHandler(s, cfg, udp, streamPool, key, replay).run();
      } catch (Exception ignored) {
      }
    });
    acceptThread.setDaemon(true);
    acceptThread.start();

    long slot = Dexote.effectiveSlot(System.currentTimeMillis() / 1000L, 0);

    try (Socket client = new Socket("127.0.0.1", serverPort)) {
      client.setTcpNoDelay(true);
      InputStream rawIn = client.getInputStream();
      OutputStream rawOut = client.getOutputStream();

      Dexote.Connected con = Dexote.connect(
          rawIn, rawOut, key.pub(), slot, Protocol.ROLE_TCP, "it-token", new byte[0]);
      AeadStream aead = new AeadStream(con.keys, new Poly(con.keys.secret, slot, "tx"));
      InputStream in = aead.wrapInput(rawIn);
      OutputStream out = aead.wrapOutput(rawOut);

      Protocol.writeTcpConnectFrame(out, new Protocol.TcpConnect(
          Protocol.ADDR_V4, InetAddress.getByName("127.0.0.1"), echoPort));
      out.flush();

      byte[] msg = "hello-through-dexote-handler".getBytes(StandardCharsets.UTF_8);
      out.write(msg);
      out.flush();

      byte[] got = new byte[msg.length];
      int off = 0;
      while (off < got.length) {
        int r = in.read(got, off, got.length - off);
        assertTrue(r > 0, "echo stream ended early at " + off);
        off += r;
      }
      assertArrayEquals(msg, got, "echo mismatch");
    } finally {
      listen.close();
      echo.close();
      handshakePool.shutdownNow();
      streamPool.shutdownNow();
      udp.close();
    }
  }

  @Test
  void tlsProbeGetsFakeTlsAlert() throws Exception {
    Path cfgFile = Files.createTempFile("volter-it-tls", ".properties");
    Files.writeString(
        cfgFile,
        "listenPorts=18098\ntoken=it-token\nudpChannels=4\nserverMode=tcp-only\ncamouflageTcpEnabled=true\ncamouflageTcpProxyHost=\ncamouflageTcpProxyPort=0\npeerRelayEnabled=false\n",
        StandardCharsets.UTF_8);
    Config cfg = Config.load(cfgFile);

    Path keyFile = Files.createTempFile("dexote-it-tls", ".key");
    Files.deleteIfExists(keyFile);
    DexoteServerKey key = DexoteServerKey.loadOrCreate(keyFile);
    Dexote.ReplayCache replay = new MemReplayCache();
    UdpSessions udp = new UdpSessions(4);
    ExecutorService streamPool = Executors.newCachedThreadPool();

    ServerSocket listen = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    int serverPort = listen.getLocalPort();
    Thread acceptThread = new Thread(() -> {
      try {
        Socket s = listen.accept();
        s.setTcpNoDelay(true);
        new ConnectionHandler(s, cfg, udp, streamPool, key, replay).run();
      } catch (Exception ignored) {
      }
    });
    acceptThread.setDaemon(true);
    acceptThread.start();

    try (Socket client = new Socket("127.0.0.1", serverPort)) {
      client.setTcpNoDelay(true);
      OutputStream rawOut = client.getOutputStream();
      InputStream rawIn = client.getInputStream();

      byte[] clientHello =
          new byte[] {0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x01, 0x00};
      rawOut.write(clientHello);
      rawOut.flush();

      byte[] resp = new byte[7];
      int off = 0;
      while (off < resp.length) {
        int r = rawIn.read(resp, off, resp.length - off);
        if (r <= 0) break;
        off += r;
      }
      assertEquals(7, off, "ожидался полный TLS alert-record");

      assertEquals(0x15, resp[0] & 0xff, "content_type=alert");
      assertEquals(0x03, resp[1] & 0xff, "major version");
      assertEquals(0x02, resp[5] & 0xff, "level=fatal");
      assertEquals(0x28, resp[6] & 0xff, "desc=handshake_failure");
    } finally {
      listen.close();
      streamPool.shutdownNow();
      udp.close();
    }
  }
}
