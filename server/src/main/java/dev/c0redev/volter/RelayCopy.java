package dev.c0redev.volter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

final class RelayCopy {

  private static final int BUF = 32 * 1024;

  private RelayCopy() {}

  static void pump(InputStream in, OutputStream out, Socket shutdownOnEnd, boolean shutdownOutput) {
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
