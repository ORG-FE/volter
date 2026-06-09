package dev.c0redev.volter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class AeadStreamTest {

  @Test
  void roundtrip() throws Exception {
    SecureRandom rnd = new SecureRandom();
    byte[] dataKey = new byte[32], lenKey = new byte[32], secret = new byte[32];
    rnd.nextBytes(dataKey);
    rnd.nextBytes(lenKey);
    rnd.nextBytes(secret);

    Dexote.Keys wk = new Dexote.Keys(dataKey, new byte[32], lenKey, new byte[32], secret);
    Dexote.Keys rk = new Dexote.Keys(new byte[32], dataKey, new byte[32], lenKey, secret);

    AeadStream ws = new AeadStream(wk, new Poly(secret, 1, "tx"));
    AeadStream rs = new AeadStream(rk, new Poly(secret, 1, "tx"));

    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    OutputStream out = ws.wrapOutput(sink);

    byte[] msg = new byte[40000];
    rnd.nextBytes(msg);
    out.write(msg);
    out.flush();

    InputStream in = rs.wrapInput(new ByteArrayInputStream(sink.toByteArray()));
    byte[] got = new byte[msg.length];
    int off = 0;
    while (off < got.length) {
      int n = in.read(got, off, got.length - off);
      if (n < 0) break;
      off += n;
    }
    assertArrayEquals(msg, got);
  }
}
