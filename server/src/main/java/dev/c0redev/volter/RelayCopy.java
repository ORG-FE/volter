package dev.c0redev.volter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

final class RelayCopy {

  private static final int BUF = 32 * 1024;
  private static final int DEFAULT_READ_TIMEOUT_MS = 120_000;

  private RelayCopy() {}

  static int relayReadTimeoutMs(int connectTimeoutMs) {
    int base = Math.max(connectTimeoutMs, DEFAULT_READ_TIMEOUT_MS);
    return Math.min(base, 600_000);
  }

  static void applyReadTimeout(Socket socket, int readTimeoutMs) {
    if (socket == null || readTimeoutMs <= 0) {
      return;
    }
    try {
      socket.setSoTimeout(readTimeoutMs);
    } catch (IOException ignored) {
    }
  }

  static void pump(InputStream in, OutputStream out, Socket shutdownOnEnd, boolean shutdownOutput) {
    pump(in, out, null, shutdownOnEnd, shutdownOutput, 0);
  }

  static void pump(
      InputStream in,
      OutputStream out,
      Socket readSocket,
      Socket shutdownOnEnd,
      boolean shutdownOutput,
      int readTimeoutMs
  ) {
    applyReadTimeout(readSocket, readTimeoutMs);
    try {
      byte[] buf = new byte[BUF];
      while (true) {
        int n = in.read(buf);
        if (n < 0) {
          return;
        }
        out.write(buf, 0, n);
        out.flush();
      }
    } catch (IOException ignored) {
    } finally {
      if (shutdownOnEnd != null) {
        try {
          if (shutdownOutput) {
            shutdownOnEnd.shutdownOutput();
          } else {
            shutdownOnEnd.shutdownInput();
          }
        } catch (IOException ignored) {
        }
      }
    }
  }
}
