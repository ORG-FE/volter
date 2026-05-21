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
  private static final int BUF = 32 * 1024;

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
      Future<?> up = RelayCopyPool.submit(() -> copy(quicIn, remoteOut), "quic-pair-up");
      Future<?> down = RelayCopyPool.submit(() -> copy(remoteIn, quicOut), "quic-pair-down");
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

  private static void copy(InputStream in, OutputStream out) {
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
      log.fine("quic relay copy end: " + e.getMessage());
    }
  }
}
