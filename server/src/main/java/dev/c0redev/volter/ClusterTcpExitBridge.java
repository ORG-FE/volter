package dev.c0redev.volter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
      OutputStream clientOut,
      Optional<Protocol.ClientOptions> copts,
      Socket clientSocket,
      long slot)
      throws IOException {
    if (cfg == null || c == null || clientIn == null || clientOut == null) {
      return false;
    }
    if (copts.isEmpty()) {
      log.fine("cluster exit: copts empty, skip bridge");
      return false;
    }
    String pref = copts.get().clusterPreferredServer();
    log.info("cluster exit: client clusterPreferredServer=" + pref + " relayHop=" + copts.get().relayHop() +
        " peerId=" + copts.get().peerId() + " relayRouteHops=" + copts.get().relayRouteHops());
    if (pref == null || pref.isBlank()) {
      log.fine("cluster exit: empty clusterPreferredServer, skip bridge");
      return false;
    }
    pref = pref.trim();
    boolean strictExit = !cfg.clusterExitFallbackToDirect();
    ClusterRuntime rt = ClusterRuntime.get();
    Optional<ClusterRuntime.ClusterExitTarget> resolved = rt.resolveClusterExit(pref);
    if (resolved.isEmpty()) {
      log.warning("cluster exit unresolved or not authorized: " + pref + " (strict=" + strictExit + ")");
      if (strictExit) {
        throw new IOException("cluster exit unresolved or not authorized: " + pref);
      }
      return false;
    }
    InetSocketAddress exitAddr = resolved.get().addr;
    byte[] upstreamPub = resolved.get().dexotePub;
    log.info("cluster exit resolved: " + exitAddr);
    int timeoutMs = Math.max(1_000, cfg.quicTcpConnectTimeoutMs());
    int readTimeoutMs = RelayCopy.relayReadTimeoutMs(timeoutMs);
    Socket upstream = new Socket();
    try {
      upstream.connect(exitAddr, timeoutMs);
      upstream.setTcpNoDelay(true);
      RelayCopy.applyReadTimeout(clientSocket, readTimeoutMs);
      log.info("cluster exit connected to " + exitAddr);
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
    DexoteUpstream.Streams up;
    try {
      up = DexoteUpstream.dial(upstream, upstreamPub, slot, Protocol.ROLE_TCP, cfg.token(), null, timeoutMs);
      DexoteUpstream.writeTcpConnect(up.out, c);
      log.info("cluster exit dexote handshake sent to " + exitAddr);
    } catch (IOException e) {

      log.warning("cluster exit dexote handshake to " + exitAddr + " failed (wrong cluster dexote.key?): " + e.getMessage());
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
      if (strictExit) {
        throw e;
      }
      return false;
    }
    OutputStream upOut = up.out;
    InputStream upIn = up.in;
    log.info("cluster tcp exit bridge active -> " + exitAddr + " dst=" + c.ip().getHostAddress() + ":" + c.port());

    CompletableFuture<Void> upFuture = CompletableFuture.runAsync(
        () -> RelayCopy.pump(clientIn, upOut, clientSocket, upstream, true, readTimeoutMs),
        RelayCopyPool.executor());
    CompletableFuture<Void> downFuture = CompletableFuture.runAsync(
        () -> RelayCopy.pump(upIn, clientOut, upstream, clientSocket, true, readTimeoutMs),
        RelayCopyPool.executor());
    CompletableFuture.allOf(upFuture, downFuture).whenComplete((unused, ex) -> {
      if (ex != null) {
        log.warning("cluster exit pump failed: " + ex.getMessage());
      }
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
      try {
        clientSocket.close();
      } catch (IOException ignored) {
      }
    });
    return true;
  }

}
