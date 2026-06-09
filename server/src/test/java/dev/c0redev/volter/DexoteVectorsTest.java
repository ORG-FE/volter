package dev.c0redev.volter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DexoteVectorsTest {

  static byte[] hex(String s) {
    byte[] b = new byte[s.length() / 2];
    for (int i = 0; i < b.length; i++) {
      b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    }
    return b;
  }

  static String hex(byte[] b) {
    StringBuilder sb = new StringBuilder();
    for (byte x : b) sb.append(String.format("%02x", x));
    return sb.toString();
  }

  private static final byte[] SERVER_PUB =
      hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c");
  private static final byte[] SS1 =
      hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742");
  private static final long SLOT = 424242;

  @Test
  void maskStream() {
    assertEquals(
        "e4058dc0c47d82864ec7c404b5e258122cdbe89e63e766def2b9e90e636ba5b3",
        hex(Dexote.maskStream(SERVER_PUB, SLOT)));
  }

  @Test
  void k1C2S() {
    assertEquals(
        "53d38ea8a1386990d38a442e92053482b7723a0e6fe7391d2e55a0a69f358a3c",
        hex(Dexote.hkdf(SS1, Dexote.slotBytes(SLOT), Dexote.INFO_C2S, 32)));
  }

  @Test
  void macVector() {
    byte[] data = Dexote.concat(SERVER_PUB, Dexote.slotBytes(SLOT));
    assertEquals("4c206cae518fa71e9175f7260fbc664d", hex(Dexote.mac(SERVER_PUB, data)));
  }

  @Test
  void aeadSeal() throws Exception {
    byte[] key = hex("0001020304050607000102030405060700010203040506070001020304050607");
    byte[] ad = hex("aabbccdd");
    byte[] pt = "dexote-aead-test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] ct = Dexote.seal(key, Dexote.zeroNonce12(), ad, pt);
    assertEquals(
        "27a431df25a042a5f91c9f4a399bcfa9657c2bee272c95ae567e80cec3ee6bf1", hex(ct));
  }

  @Test
  void polySeq() {
    byte[] secret = new byte[32];
    for (int i = 0; i < 32; i++) secret[i] = (byte) i;
    Poly p = new Poly(secret, 99, "vec");
    int[] want = {32247, 24580, 17521, 57491, 15039};
    for (int i = 0; i < want.length; i++) {
      assertEquals(want[i], p.intRange(0, 65535), "poly_seq[" + i + "]");
    }
  }
}
