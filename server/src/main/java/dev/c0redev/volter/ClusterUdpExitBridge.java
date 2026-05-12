package dev.c0redev.volter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;
import java.util.logging.Logger;

final class ClusterUdpExitBridge {

  private static final Logger log = Log.logger(ClusterUdpExitBridge.class);
  private static final int BUF = 32 * 1024;

  private ClusterUdpExitBridge() {}

  static boolean maybeBridge(
      Config cfg,
      int channelId,
      InputStream clientIn,
      OutputStream clientOut,
      Optional<Protocol.ClientOptions> copts)
      throws IOException {
    if (cfg == null || clientIn == null || clientOut == null) {
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
      throw new IOException("cluster udp exit unresolved or not authorized: " + pref);
    }
    InetSocketAddress exitAddr = resolved.get();
    int timeoutMs = Math.max(1_000, cfg.quicTcpConnectTimeoutMs());
    Socket upstream = new Socket();
    try {
      upstream.connect(exitAddr, timeoutMs);
      upstream.setTcpNoDelay(true);
    } catch (IOException e) {
      log.warning("cluster udp exit connect " + exitAddr + ": " + e.getMessage());
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
      throw new IOException("cluster udp exit connect failed: " + pref + " -> " + exitAddr, e);
    }

    XorStream xor = new XorStream(XorStream.keyFromToken(cfg.token()));
    OutputStream upXorOut = xor.wrapOutput(upstream.getOutputStream());
    InputStream upXorIn = xor.wrapInput(upstream.getInputStream());
    try {
      byte[] optsBytes = null;
      String optsJson = copts.get().toJsonForClusterRelay();
      if (!optsJson.equals("{}")) {
        optsBytes = optsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      }
      Protocol.writeVolterClientHandshake(upXorOut, Protocol.ROLE_UDP, cfg.token(), optsBytes);
    } catch (IOException e) {
      log.warning("cluster udp exit handshake failed: " + e.getMessage());
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
      throw new IOException("cluster udp exit handshake failed: " + pref + " -> " + exitAddr, e);
    }

    log.info("cluster udp exit bridge -> " + exitAddr + " channel=" + channelId);
    Thread up = new Thread(() -> copyQuiet("cluster-udp-up", clientIn, upXorOut), "cluster-udp-up");
    Thread down = new Thread(() -> copyQuiet("cluster-udp-down", upXorIn, clientOut), "cluster-udp-down");
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
