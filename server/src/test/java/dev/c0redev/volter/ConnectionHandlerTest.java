package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionHandlerTest {
  @Test
  void clusterMapHttpDoesNotWaitForVpnHandshakeTimeout() throws Exception {
    Path f = Files.createTempFile("volter-cluster-http", ".properties");
    Files.writeString(f, """
        listenPorts=18080
        token=test-token
        udpChannels=4
        serverMode=tcp-only
        cluster.nodeId=node-a
        cluster.httpAuth=false
        """, StandardCharsets.UTF_8);
    Config cfg = Config.load(f);
    ExecutorService streamPool = Executors.newCachedThreadPool();
    ExecutorService acceptPool = Executors.newSingleThreadExecutor();
    try (
        ServerSocket server = new ServerSocket(0);
        UdpSessions udp = new UdpSessions(1)
    ) {
      TcpReactorPool tcpPool = new TcpReactorPool(1);
      try {
        Future<?> accepted = acceptPool.submit(() -> {
          try {
            Socket s = server.accept();
            new ConnectionHandler(s, cfg, udp, tcpPool, streamPool).run();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
        try (Socket client = new Socket("127.0.0.1", server.getLocalPort())) {
          client.setSoTimeout(1500);
          PrintWriter w = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));
          w.print("GET /volter/cluster-map.json HTTP/1.1\r\nHost: localhost\r\nX-Volter-Cluster-Pull: 1\r\nConnection: close\r\n\r\n");
          w.flush();
          BufferedReader r = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
          assertEquals("HTTP/1.1 200 OK", r.readLine());
          while (r.readLine() != null) {
            // response must end quickly because handler writes Connection: close
          }
        }
        accepted.get();
      } finally {
        tcpPool.shutdown();
      }
    } finally {
      acceptPool.shutdownNow();
      streamPool.shutdownNow();
    }
  }

  @Test
  void rawHttpLikePrefixDoesNotBypassVpnHandshake() throws Exception {
    byte[] raw = encodedVpnHandshakeWithRawPrefix("GET ");
    assertEquals('G', raw[0] & 0xff);
    assertEquals('E', raw[1] & 0xff);
    assertEquals('T', raw[2] & 0xff);
    assertEquals(' ', raw[3] & 0xff);
    assertVpnStreamGetsVpnHandling(raw);
  }

  @Test
  void rawWhitelistedHttpRequestPrefixDoesNotBypassVpnHandshake() throws Exception {
    byte[] raw = encodedVpnHandshakeWithRawPrefix("GET /volter/cluster-map.json HTTP/1.1\r\nHost: x\r\nX-Volter-Cluster-Pull: 1\r\n\r\n");
    String prefix = new String(raw, 0, "GET /volter/cluster-map.json".length(), StandardCharsets.US_ASCII);
    assertEquals("GET /volter/cluster-map.json", prefix);
    assertVpnStreamGetsVpnHandling(raw);
  }

  private static byte[] encodedVpnHandshakeWithRawPrefix(String rawPrefix) throws Exception {
    byte[] key = XorStream.keyFromToken("test-token");
    ByteArrayOutputStream plain = new ByteArrayOutputStream();
    byte[] prefix = rawPrefix.getBytes(StandardCharsets.US_ASCII);
    for (int i = 0; i < prefix.length; i++) {
      plain.write(prefix[i] ^ key[i % key.length]);
    }
    plain.write(Protocol.MAGIC);
    plain.write(Protocol.VERSION);
    plain.write(Protocol.ROLE_UDP);
    byte[] tok = "test-token".getBytes(StandardCharsets.UTF_8);
    plain.write((tok.length >>> 8) & 0xff);
    plain.write(tok.length & 0xff);
    plain.write(tok);
    plain.write(7);
    plain.write(0);
    plain.write(0);

    byte[] raw = plain.toByteArray();
    for (int i = 0; i < raw.length; i++) {
      raw[i] ^= key[i % key.length];
    }
    return raw;
  }

  private static void assertVpnStreamGetsVpnHandling(byte[] raw) throws Exception {
    Path f = Files.createTempFile("volter-http-like-vpn", ".properties");
    Files.writeString(f, """
        listenPorts=18080
        token=test-token
        udpChannels=4
        serverMode=tcp-only
        camouflageTcpProxyHost=127.0.0.1
        camouflageTcpProxyPort=1
        """, StandardCharsets.UTF_8);
    Config cfg = Config.load(f);
    ExecutorService streamPool = Executors.newCachedThreadPool();
    ExecutorService acceptPool = Executors.newSingleThreadExecutor();
    try (
        ServerSocket server = new ServerSocket(0);
        UdpSessions udp = new UdpSessions(1)
    ) {
      TcpReactorPool tcpPool = new TcpReactorPool(1);
      try {
        Future<?> accepted = acceptPool.submit(() -> {
          try {
            Socket s = server.accept();
            new ConnectionHandler(s, cfg, udp, tcpPool, streamPool).run();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
        try (Socket client = new Socket("127.0.0.1", server.getLocalPort())) {
          client.setSoTimeout(1500);
          OutputStream out = client.getOutputStream();
          out.write(raw);
          out.flush();
          client.shutdownOutput();
          assertEquals(-1, client.getInputStream().read());
        }
        accepted.get();
      } finally {
        tcpPool.shutdown();
      }
    } finally {
      acceptPool.shutdownNow();
      streamPool.shutdownNow();
    }
  }
}
