package dev.c0redev.volter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Logger;

final class ClusterTcpExitBridge {

  private static final Logger log = Log.logger(ClusterTcpExitBridge.class);
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
      Optional<Protocol.ClientOptions> copts,
      Socket clientSocket)
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
    boolean strictExit = !cfg.clusterExitFallbackToDirect();
    ClusterRuntime rt = ClusterRuntime.get();
    Optional<InetSocketAddress> resolved = rt.resolveClusterExitDialAddress(pref);
    if (resolved.isEmpty()) {
      log.warning("cluster exit unresolved or not authorized: " + pref);
      if (strictExit) {
        throw new IOException("cluster exit unresolved or not authorized: " + pref);
      }
      return false;
    }
    InetSocketAddress exitAddr = resolved.get();
    int timeoutMs = Math.max(1_000, cfg.quicTcpConnectTimeoutMs());
    int readTimeoutMs = RelayCopy.relayReadTimeoutMs(timeoutMs);
    Socket upstream = new Socket();
    try {
      upstream.connect(exitAddr, timeoutMs);
      upstream.setTcpNoDelay(true);
      RelayCopy.applyReadTimeout(clientSocket, readTimeoutMs);
    } catch (IOException e) {
      log.warning("cluster exit connect " + exitAddr + ": " + e.getMessage());
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
      if (strictExit) {
        throw e;
      }
      return false;
    }
    XorStream xor = new XorStream(XorStream.keyFromToken(cfg.token()));
    OutputStream upXorOut = xor.wrapOutput(upstream.getOutputStream());
    InputStream upXorIn = xor.wrapInput(upstream.getInputStream());
    try {
      Protocol.writeVolterClientHandshake(upXorOut, Protocol.ROLE_TCP, cfg.token(), null);
      Protocol.writeTcpConnectFrame(upXorOut, c);
    } catch (IOException e) {
      log.warning("cluster exit handshake failed: " + e.getMessage());
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
      if (strictExit) {
        throw e;
      }
      return false;
    }
    log.info("cluster tcp exit bridge -> " + exitAddr + " dst=" + c.ip().getHostAddress() + ":" + c.port());
    Future<?> up = RelayCopyPool.submit(
        () -> RelayCopy.pump(clientIn, upXorOut, clientSocket, upstream, true, readTimeoutMs),
        "cluster-br-up");
    Future<?> down = RelayCopyPool.submit(
        () -> RelayCopy.pump(upXorIn, clientXorOut, upstream, null, false, readTimeoutMs),
        "cluster-br-down");
    try {
      up.get();
      down.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      up.cancel(true);
      down.cancel(true);
      throw new IOException(e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException io) {
        throw io;
      }
      throw new IOException(cause);
    } finally {
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
    }
    return true;
  }

}
