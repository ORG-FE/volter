package dev.c0redev.volter;

import java.nio.charset.StandardCharsets;
import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

final class Poly {

  private final ChaCha7539Engine engine = new ChaCha7539Engine();
  private final byte[] zero8 = new byte[8];

  Poly(byte[] secret, long slot, String info) {
    byte[] key = Dexote.hkdf(secret, Dexote.slotBytes(slot), "dexote-poly-" + info, 32);
    byte[] nonce = new byte[12];
    engine.init(true, new ParametersWithIV(new KeyParameter(key), nonce));
  }

  long next64() {
    byte[] out = new byte[8];
    engine.processBytes(zero8, 0, 8, out, 0);
    long v = 0;
    for (int i = 0; i < 8; i++) v = (v << 8) | (out[i] & 0xff);
    return v;
  }

  int intRange(int min, int max) {
    if (max <= min) return min;
    long span = (long) (max - min) + 1;
    return min + (int) (Long.remainderUnsigned(next64(), span));
  }

  int padLen(int maxPad) {
    if (maxPad <= 0) return 0;
    return intRange(0, maxPad);
  }

  private static byte[] info(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
