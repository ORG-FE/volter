package dev.c0redev.volter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DexoteReverseE2ETest {

  @Test
  void javaClientToGoServer() throws Exception {
    File repoRoot = new File(System.getProperty("user.dir")).getParentFile();
    if (!goAvailable(repoRoot)) {
      System.out.println("go toolchain unavailable, skipping reverse E2E");
      return;
    }

    byte[] scalar = new byte[32];
    for (int i = 0; i < 32; i++) scalar[i] = (byte) (i * 3 + 5);
    String scalarB64 = Base64.getEncoder().encodeToString(scalar);
    byte[] pub = Dexote.pubFromScalar(scalar);
    long slot = 555;
    String token = "rev-token";

    ProcessBuilder pb =
        new ProcessBuilder(
            "go", "run", "./cmd/dexote-e2e-server", scalarB64, Long.toString(slot), token);
    pb.directory(repoRoot);
    Process p = pb.start();
    try {
      BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
      int port = -1;
      long deadline = System.currentTimeMillis() + 60000;
      String line;
      while (System.currentTimeMillis() < deadline && (line = br.readLine()) != null) {
        if (line.startsWith("READY ")) {
          port = Integer.parseInt(line.substring(6).trim());
          break;
        }
      }
      assertTrue(port > 0, "go server did not become ready");

      try (Socket s = new Socket("127.0.0.1", port)) {
        InputStream rawIn = s.getInputStream();
        OutputStream rawOut = s.getOutputStream();
        Dexote.Connected con =
            Dexote.connect(rawIn, rawOut, pub, slot, (byte) 2, token, new byte[0]);

        AeadStream as = new AeadStream(con.keys, new Poly(con.keys.secret, slot, "x"));
        OutputStream out = as.wrapOutput(rawOut);
        InputStream in = as.wrapInput(rawIn);

        byte[] msg = "hello-from-java-relay".getBytes();
        out.write(msg);
        out.flush();
        byte[] got = new byte[msg.length];
        int off = 0;
        while (off < got.length) {
          int n = in.read(got, off, got.length - off);
          if (n < 0) break;
          off += n;
        }
        assertArrayEquals(msg, got);
      }
    } finally {
      p.destroy();
      p.waitFor(5, TimeUnit.SECONDS);
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
}
