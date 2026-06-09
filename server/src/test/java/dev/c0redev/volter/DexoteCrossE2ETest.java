package dev.c0redev.volter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DexoteCrossE2ETest {

  @Test
  void goClientToJavaServer() throws Exception {
    byte[] scalar = new byte[32];
    for (int i = 0; i < 32; i++) scalar[i] = (byte) (i * 7 + 1);
    byte[] pub = Dexote.pubFromScalar(scalar);
    String pubB64 = Base64.getEncoder().encodeToString(pub);
    long slot = 777;
    String token = "e2e-token";

    File repoRoot = new File(System.getProperty("user.dir")).getParentFile();

    if (!goAvailable(repoRoot)) {
      System.out.println("go toolchain unavailable, skipping cross E2E");
      return;
    }

    AtomicReference<String> serverErr = new AtomicReference<>();
    try (ServerSocket ss = new ServerSocket(0)) {
      ss.setSoTimeout(15000);
      int port = ss.getLocalPort();

      Thread srv =
          new Thread(
              () -> {
                try (Socket c = ss.accept()) {
                  InputStream rawIn = c.getInputStream();
                  OutputStream rawOut = c.getOutputStream();
                  Dexote.Accepted acc =
                      Dexote.accept(
                          rawIn, rawOut, scalar, pub, slot, new byte[] {1, 2, 3}, (n, ts) -> true);
                  if (acc == null) {
                    serverErr.set("accept rejected");
                    return;
                  }
                  if (!acc.hello.token.equals(token)) {
                    serverErr.set("token mismatch: " + acc.hello.token);
                    return;
                  }
                  AeadStream as = new AeadStream(acc.keys, new Poly(acc.keys.secret, slot, "x"));
                  InputStream in = as.wrapInput(rawIn);
                  OutputStream out = as.wrapOutput(rawOut);
                  byte[] buf = new byte[4096];
                  int n = in.read(buf);
                  if (n > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                  }
                  Thread.sleep(200);
                } catch (Exception e) {
                  serverErr.set("server: " + e);
                }
              });
      srv.setDaemon(true);
      srv.start();

      ProcessBuilder pb =
          new ProcessBuilder(
              "go",
              "run",
              "./cmd/dexote-e2e",
              "127.0.0.1:" + port,
              pubB64,
              Long.toString(slot),
              token);
      pb.directory(repoRoot);
      pb.redirectErrorStream(true);
      Process p = pb.start();
      String output = new String(p.getInputStream().readAllBytes());
      boolean done = p.waitFor(60, TimeUnit.SECONDS);
      srv.join(2000);

      assertTrue(done, "go client timed out");
      assertEquals(0, p.exitValue(), "go client failed: " + output);
      assertTrue(output.contains("OK"), "missing OK: " + output);
      assertEquals(null, serverErr.get(), "server side error");
    }
  }

  private static boolean goAvailable(File dir) {
    try {
      Process p = new ProcessBuilder("go", "version").directory(dir).start();
      return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  void goUdpRoleChannelIdInOptsZero() throws Exception {
    byte[] scalar = new byte[32];
    for (int i = 0; i < 32; i++) scalar[i] = (byte) (i * 3 + 5);
    byte[] pub = Dexote.pubFromScalar(scalar);
    String pubB64 = Base64.getEncoder().encodeToString(pub);
    long slot = 4242;
    String token = "udp-e2e-token";
    int channelId = 7;

    File repoRoot = new File(System.getProperty("user.dir")).getParentFile();
    if (!goAvailable(repoRoot)) {
      System.out.println("go toolchain unavailable, skipping udp cross E2E");
      return;
    }

    AtomicReference<String> serverErr = new AtomicReference<>();
    AtomicReference<Integer> gotRole = new AtomicReference<>();
    AtomicReference<Integer> gotChannel = new AtomicReference<>();
    try (ServerSocket ss = new ServerSocket(0)) {
      ss.setSoTimeout(15000);
      int port = ss.getLocalPort();

      Thread srv = new Thread(() -> {
        try (Socket c = ss.accept()) {
          InputStream rawIn = c.getInputStream();
          OutputStream rawOut = c.getOutputStream();
          Dexote.Accepted acc = Dexote.accept(
              rawIn, rawOut, scalar, pub, slot, new byte[] {1, 2, 3}, (n, ts) -> true);
          if (acc == null) {
            serverErr.set("accept rejected");
            return;
          }
          gotRole.set((int) acc.hello.role);
          byte[] opts = acc.hello.opts == null ? new byte[0] : acc.hello.opts;
          if (acc.hello.role == Protocol.ROLE_UDP && opts.length >= 1) {
            gotChannel.set(opts[0] & 0xff);
          }
          AeadStream as = new AeadStream(acc.keys, new Poly(acc.keys.secret, slot, "x"));
          InputStream in = as.wrapInput(rawIn);
          OutputStream out = as.wrapOutput(rawOut);
          byte[] buf = new byte[4096];
          int n = in.read(buf);
          if (n > 0) {
            out.write(buf, 0, n);
            out.flush();
          }
          Thread.sleep(200);
        } catch (Exception e) {
          serverErr.set("server: " + e);
        }
      });
      srv.setDaemon(true);
      srv.start();

      ProcessBuilder pb = new ProcessBuilder(
          "go", "run", "./cmd/dexote-e2e",
          "127.0.0.1:" + port, pubB64, Long.toString(slot), token,
          "1", Integer.toString(channelId));
      pb.directory(repoRoot);
      pb.redirectErrorStream(true);
      Process p = pb.start();
      String output = new String(p.getInputStream().readAllBytes());
      boolean done = p.waitFor(60, TimeUnit.SECONDS);
      srv.join(2000);

      assertTrue(done, "go client timed out");
      assertEquals(0, p.exitValue(), "go client failed: " + output);
      assertTrue(output.contains("OK"), "missing OK: " + output);
      assertEquals(null, serverErr.get(), "server side error");
      assertEquals(Integer.valueOf(Protocol.ROLE_UDP & 0xff), gotRole.get(), "role should be UDP");
      assertEquals(Integer.valueOf(channelId), gotChannel.get(), "channelId from opts[0]");
    }
  }
}
