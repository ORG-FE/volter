package dev.c0redev.volter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Logger;

final class QuicTcpRelay {
  private static final Logger log = Log.logger(QuicTcpRelay.class);
  private QuicTcpRelay() {}

  static void run(
      Protocol.TcpConnect c,
      InputStream quicIn,
      OutputStream quicOut,
      int connectTimeoutMs
  ) throws IOException {
    try (Socket remote = new Socket()) {
      remote.connect(new InetSocketAddress(c.ip(), c.port()), connectTimeoutMs);
      remote.setTcpNoDelay(true);
      var remoteIn = remote.getInputStream();
      var remoteOut = remote.getOutputStream();
      Future<?> up = RelayCopyPool.submit(
          () -> RelayCopy.pump(quicIn, remoteOut, remote, true),
          "quic-pair-up");
      Future<?> down = RelayCopyPool.submit(
          () -> RelayCopy.pump(remoteIn, quicOut, null, false),
          "quic-pair-down");
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
      }
    }
  }

}
