package dev.c0redev.volter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

final class DexoteTcpExit {

  private static final Logger log = Log.logger(DexoteTcpExit.class);

  private DexoteTcpExit() {}

  static boolean bridge(
      Config cfg,
      Protocol.TcpConnect c,
      InputStream clientIn,
      OutputStream clientOut,
      Socket clientSocket) {
    int timeoutMs = Math.max(1_000, cfg.quicTcpConnectTimeoutMs());
    int readTimeoutMs = RelayCopy.relayReadTimeoutMs(timeoutMs);
    InetSocketAddress dst = new InetSocketAddress(c.ip(), c.port());
    Socket upstream = new Socket();
    try {
      upstream.connect(dst, timeoutMs);
      upstream.setTcpNoDelay(true);
      RelayCopy.applyReadTimeout(clientSocket, readTimeoutMs);
    } catch (IOException e) {
      log.warning("dexote exit connect " + dst + ": " + e.getMessage());
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
      return false;
    }
    OutputStream upOut;
    InputStream upIn;
    try {
      upOut = upstream.getOutputStream();
      upIn = upstream.getInputStream();
    } catch (IOException e) {
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
      return false;
    }
    log.info("dexote tcp exit -> " + c.ip().getHostAddress() + ":" + c.port());
    CompletableFuture<Void> upFuture = CompletableFuture.runAsync(
        () -> RelayCopy.pump(clientIn, upOut, clientSocket, upstream, true, readTimeoutMs),
        RelayCopyPool.executor());
    CompletableFuture<Void> downFuture = CompletableFuture.runAsync(
        () -> RelayCopy.pump(upIn, clientOut, upstream, clientSocket, true, readTimeoutMs),
        RelayCopyPool.executor());
    CompletableFuture.allOf(upFuture, downFuture).whenComplete((unused, ex) -> {
      if (ex != null) {
        log.warning("dexote exit pump failed: " + ex.getMessage());
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
