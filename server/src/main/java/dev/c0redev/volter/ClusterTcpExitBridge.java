package dev.c0redev.volter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;
import java.util.logging.Logger;

final class ClusterTcpExitBridge {

  private static final Logger log = Log.logger(ClusterTcpExitBridge.class);
  private static final int BUF = 32 * 1024;

  private ClusterTcpExitBridge() {}

  static InetSocketAddress parseHostPort(String raw) throws IOException {
    String s = raw == null ? "" : raw.trim();
    if (s.isEmpty()) {
      throw new IOException("empty host:port");
    }
    if (s.startsWith("[")) {
      int close = s.indexOf(']');
      if (close < 1) {
        throw new IOException("bad ipv6 bracket");
      }
      String host = s.substring(1, close).trim();
      int colon = s.indexOf(':', close);
      if (colon != close + 1) {
        throw new IOException("bad ipv6 host:port");
      }
      String portStr = s.substring(close + 2).trim();
      int port = Integer.parseInt(portStr);
      if (port <= 0 || port > 65535) {
        throw new IOException("bad port");
      }
      InetAddress ip = InetAddress.getByName(host);
      return new InetSocketAddress(ip, port);
    }
    int colon = s.lastIndexOf(':');
    if (colon <= 0 || colon == s.length() - 1) {
      throw new IOException("missing port");
    }
    String host = s.substring(0, colon).trim();
    String portStr = s.substring(colon + 1).trim();
    int port = Integer.parseInt(portStr);
    if (port <= 0 || port > 65535) {
      throw new IOException("bad port");
    }
    InetAddress ip = InetAddress.getByName(host);
    return new InetSocketAddress(ip, port);
  }

  static boolean maybeBridge(
      Config cfg,
      Protocol.TcpConnect c,
      InputStream clientIn,
      OutputStream clientXorOut,
      Optional<Protocol.ClientOptions> copts)
      throws IOException {
    if (cfg == null || c == null || clientIn == null || clientXorOut == null) {
      return false;
    }
    if (copts.isEmpty()) {
      return false;
    }
    String pref = copts.get().clusterPreferredServer();
    if (pref == null || pref.isBlank()) {
      return false;
    }
    pref = pref.trim();
    ClusterRuntime rt = ClusterRuntime.get();
    Optional<InetSocketAddress> resolved = rt.resolveClusterExitDialAddress(pref);
    if (resolved.isEmpty()) {
      throw new IOException("cluster exit unresolved or not authorized: " + pref);
    }
    InetSocketAddress exitAddr = resolved.get();
    int timeoutMs = Math.max(1_000, cfg.quicTcpConnectTimeoutMs());
    Socket upstream = new Socket();
    try {
      upstream.connect(exitAddr, timeoutMs);
      upstream.setTcpNoDelay(true);
    } catch (IOException e) {
      log.warning("cluster exit connect " + exitAddr + ": " + e.getMessage());
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
      throw new IOException("cluster exit connect failed: " + pref + " -> " + exitAddr, e);
    }
    XorStream xor = new XorStream(XorStream.keyFromToken(cfg.token()));
    OutputStream upXorOut = xor.wrapOutput(upstream.getOutputStream());
    InputStream upXorIn = xor.wrapInput(upstream.getInputStream());
    try {
      byte[] optsBytes = null;
      if (copts.isPresent()) {
        String optsJson = copts.get().toJsonForClusterRelay();
        if (!optsJson.equals("{}")) {
          optsBytes = optsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
      }
      Protocol.writeVolterClientHandshake(upXorOut, Protocol.ROLE_TCP, cfg.token(), optsBytes);
      Protocol.writeTcpConnectFrame(upXorOut, c);
    } catch (IOException e) {
      log.warning("cluster exit handshake failed: " + e.getMessage());
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
      throw new IOException("cluster exit handshake failed: " + pref + " -> " + exitAddr, e);
    }
    log.info("cluster tcp exit bridge -> " + exitAddr + " dst=" + c.ip().getHostAddress() + ":" + c.port());
    Thread up = new Thread(() -> copyQuiet("cluster-br-up", clientIn, upXorOut), "cluster-br-up");
    Thread down = new Thread(() -> copyQuiet("cluster-br-down", upXorIn, clientXorOut), "cluster-br-down");
    up.setDaemon(true);
    down.setDaemon(true);
    up.start();
    down.start();
    try {
      up.join();
      down.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      up.interrupt();
      down.interrupt();
      throw new IOException(e);
    } finally {
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
    }
    return true;
  }

  private static void copyQuiet(String tag, InputStream in, OutputStream out) {
    byte[] buf = new byte[BUF];
    try {
      while (true) {
        int n = in.read(buf);
        if (n < 0) {
          return;
        }
        out.write(buf, 0, n);
        out.flush();
      }
    } catch (IOException e) {
      log.fine(tag + " end: " + e.getMessage());
    }
  }
}
