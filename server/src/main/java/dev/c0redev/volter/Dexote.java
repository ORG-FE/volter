package dev.c0redev.volter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.digests.Blake2sDigest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.math.ec.rfc7748.X25519;

final class Dexote {

  static final int KEY_LEN = 32;
  static final int MAC_LEN = 16;
  static final int NONCE_LEN = 16;
  static final int TAG_LEN = 16;
  static final byte VERSION = 1;

  static final String INFO_MASK = "dexote-mask-v1";
  static final String INFO_C2S = "dexote-c2s-v1";
  static final String INFO_S2C = "dexote-s2c-v1";
  static final String INFO_SESSION = "dexote-session-v1";

  private static final int MAX_CT = 8192;
  private static final long REPLAY_WINDOW = 90;

  private static final SecureRandom RND = new SecureRandom();

  // Cipher.getInstance делает дорогой JCA-lookup провайдера; на горячем пути
  // (seal/open на каждый пакет) кэшируем инстанс per-thread, init() сбрасывает состояние
  private static final ThreadLocal<Cipher> CHACHA = ThreadLocal.withInitial(() -> {
    try {
      return Cipher.getInstance("ChaCha20-Poly1305");
    } catch (Exception e) {
      throw new IllegalStateException("ChaCha20-Poly1305 unavailable", e);
    }
  });

  static byte[] slotBytes(long slot) {
    byte[] b = new byte[8];
    for (int i = 7; i >= 0; i--) {
      b[i] = (byte) (slot & 0xff);
      slot >>>= 8;
    }
    return b;
  }

  static final long SLOT_SEC = 120;

  static long timeSlot(long nowSec) {
    return nowSec / SLOT_SEC;
  }

  static long effectiveSlot(long nowSec, int churnEpochSec) {
    long s = timeSlot(nowSec);
    if (churnEpochSec <= 0) return s;
    long c = nowSec / churnEpochSec;
    return s + c * 13;
  }

  static long[] candidateSlots(long nowSec, int churnEpochSec) {
    long cur = effectiveSlot(nowSec, churnEpochSec);
    long prev = effectiveSlot(nowSec - SLOT_SEC, churnEpochSec);
    long next = effectiveSlot(nowSec + SLOT_SEC, churnEpochSec);
    if (prev == cur && next == cur) return new long[] {cur};
    return new long[] {cur, prev, next};
  }

  static byte[] hkdf(byte[] ikm, byte[] salt, String info, int n) {
    HKDFBytesGenerator g = new HKDFBytesGenerator(new SHA256Digest());
    g.init(new HKDFParameters(ikm, salt, info.getBytes(StandardCharsets.UTF_8)));
    byte[] out = new byte[n];
    g.generateBytes(out, 0, n);
    return out;
  }

  static byte[] x25519(byte[] scalar, byte[] point) {
    byte[] out = new byte[KEY_LEN];
    X25519.scalarMult(scalar, 0, point, 0, out, 0);
    return out;
  }

  static byte[] pubFromScalar(byte[] scalar) {
    byte[] out = new byte[KEY_LEN];
    X25519.scalarMultBase(scalar, 0, out, 0);
    return out;
  }

  static byte[] maskStream(byte[] serverPub, long slot) {
    return hkdf(serverPub, slotBytes(slot), INFO_MASK, KEY_LEN);
  }

  static byte[] maskPub(byte[] pub, byte[] serverPub, long slot) {
    byte[] ks = maskStream(serverPub, slot);
    byte[] out = new byte[KEY_LEN];
    for (int i = 0; i < KEY_LEN; i++) out[i] = (byte) (pub[i] ^ ks[i]);
    return out;
  }

  static byte[] mac(byte[] serverPub, byte[] data) {
    Blake2sDigest d = new Blake2sDigest(serverPub, MAC_LEN, null, null);
    d.update(data, 0, data.length);
    byte[] out = new byte[MAC_LEN];
    d.doFinal(out, 0);
    return out;
  }

  static boolean macEqual(byte[] a, byte[] b) {
    if (a.length != b.length) return false;
    int r = 0;
    for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
    return r == 0;
  }

  static byte[] zeroNonce12() {
    return new byte[12];
  }

  static byte[] seal(byte[] key, byte[] nonce, byte[] ad, byte[] pt) throws IOException {
    try {
      Cipher c = CHACHA.get();
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
      if (ad != null) c.updateAAD(ad);
      return c.doFinal(pt);
    } catch (Exception e) {
      throw new IOException("dexote seal", e);
    }
  }

  static byte[] open(byte[] key, byte[] nonce, byte[] ad, byte[] ct) throws IOException {
    try {
      Cipher c = CHACHA.get();
      c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
      if (ad != null) c.updateAAD(ad);
      return c.doFinal(ct);
    } catch (Exception e) {
      throw new IOException("dexote open", e);
    }
  }

  static final class Keys {
    final byte[] txKey, rxKey, txLenKey, rxLenKey, secret;

    Keys(byte[] tx, byte[] rx, byte[] txl, byte[] rxl, byte[] sec) {
      this.txKey = tx;
      this.rxKey = rx;
      this.txLenKey = txl;
      this.rxLenKey = rxl;
      this.secret = sec;
    }
  }

  static Keys sessionKeys(byte[] secret, byte[] cliNonce, byte[] srvNonce, boolean clientSide) {
    byte[] salt = concat(cliNonce, srvNonce);
    byte[] c2s = hkdf(secret, salt, INFO_SESSION + "|c2s|data", KEY_LEN);
    byte[] s2c = hkdf(secret, salt, INFO_SESSION + "|s2c|data", KEY_LEN);
    byte[] c2sLen = hkdf(secret, salt, INFO_SESSION + "|c2s|len", KEY_LEN);
    byte[] s2cLen = hkdf(secret, salt, INFO_SESSION + "|s2c|len", KEY_LEN);
    byte[] root = hkdf(secret, salt, INFO_SESSION + "|root", KEY_LEN);
    if (clientSide) return new Keys(c2s, s2c, c2sLen, s2cLen, root);
    return new Keys(s2c, c2s, s2cLen, c2sLen, root);
  }

  static byte[] deriveSecret(byte[] ss3, byte[] ss1, byte[] cliNonce, byte[] srvNonce) {
    return hkdf(concat(ss3, ss1), concat(cliNonce, srvNonce), INFO_SESSION, KEY_LEN);
  }

  static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  static final class ClientHello {
    final byte role;
    final String token;
    final byte[] opts;
    final byte[] cliNonce;
    final long tsSec;

    ClientHello(byte role, String token, byte[] opts, byte[] cliNonce, long tsSec) {
      this.role = role;
      this.token = token;
      this.opts = opts;
      this.cliNonce = cliNonce;
      this.tsSec = tsSec;
    }
  }

  static final class Accepted {
    final Keys keys;
    final ClientHello hello;
    final long slot;

    Accepted(Keys keys, ClientHello hello, long slot) {
      this.keys = keys;
      this.hello = hello;
      this.slot = slot;
    }
  }

  static final class Connected {
    final Keys keys;
    final byte[] caps;

    Connected(Keys keys, byte[] caps) {
      this.keys = keys;
      this.caps = caps;
    }
  }

  static Connected connect(
      InputStream in,
      OutputStream out,
      byte[] serverPub,
      long slot,
      byte role,
      String token,
      byte[] opts)
      throws IOException {
    byte[] scalar = new byte[KEY_LEN];
    RND.nextBytes(scalar);
    byte[] ePub = pubFromScalar(scalar);
    byte[] masked = maskPub(ePub, serverPub, slot);
    byte[] m = mac(serverPub, concat(masked, slotBytes(slot)));

    byte[] ss1 = x25519(scalar, serverPub);
    byte[] k1 = hkdf(ss1, slotBytes(slot), INFO_C2S, KEY_LEN);

    byte[] cliNonce = new byte[NONCE_LEN];
    RND.nextBytes(cliNonce);
    long ts = System.currentTimeMillis() / 1000L;
    byte[] pad = new byte[new Poly(k1, slot, "hspad").intRange(0, 512)];
    byte[] pt = buildClientPlaintext(role, token, opts == null ? new byte[0] : opts, cliNonce, ts, pad);
    byte[] ct = seal(k1, zeroNonce12(), masked, pt);

    out.write(masked);
    out.write(m);
    writeU16(out, ct.length);
    out.write(ct);
    out.flush();

    byte[] maskedS = readN(in, KEY_LEN);
    byte[] sPub = maskPub(maskedS, serverPub, slot);
    byte[] ss3 = x25519(scalar, sPub);
    byte[] k2 = hkdf(ss3, slotBytes(slot), INFO_S2C, KEY_LEN);
    int sctLen = readU16(in);
    if (sctLen > MAX_CT) throw new IOException("server hello too large");
    byte[] sct = readN(in, sctLen);
    byte[] spt = open(k2, zeroNonce12(), maskedS, sct);

    Cur r = new Cur(spt);
    byte[] srvNonce = r.n(NONCE_LEN);
    byte[] caps = r.lenPrefixed();
    r.lenPrefixed();

    byte[] secret = deriveSecret(ss3, ss1, cliNonce, srvNonce);
    return new Connected(sessionKeys(secret, cliNonce, srvNonce, true), caps);
  }

  static byte[] buildClientPlaintext(
      byte role, String token, byte[] opts, byte[] cliNonce, long tsSec, byte[] pad) {
    byte[] tok = token.getBytes(StandardCharsets.UTF_8);
    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
    b.write(VERSION);
    b.write(role);
    b.writeBytes(cliNonce);
    for (int i = 7; i >= 0; i--) b.write((int) ((tsSec >>> (i * 8)) & 0xff));
    b.write((tok.length >> 8) & 0xff);
    b.write(tok.length & 0xff);
    b.writeBytes(tok);
    b.write((opts.length >> 8) & 0xff);
    b.write(opts.length & 0xff);
    b.writeBytes(opts);
    b.write((pad.length >> 8) & 0xff);
    b.write(pad.length & 0xff);
    b.writeBytes(pad);
    return b.toByteArray();
  }

  static Accepted accept(
      InputStream in,
      OutputStream out,
      byte[] serverScalar,
      byte[] serverPub,
      long slot,
      byte[] capsBytes,
      ReplayCache seen)
      throws IOException {
    return accept(in, out, serverScalar, serverPub, new long[] {slot}, capsBytes, seen);
  }

  static Accepted accept(
      InputStream in,
      OutputStream out,
      byte[] serverScalar,
      byte[] serverPub,
      long[] slots,
      byte[] capsBytes,
      ReplayCache seen)
      throws IOException {
    byte[] masked = readN(in, KEY_LEN);
    byte[] gotMac = readN(in, MAC_LEN);
    long matchedSlot = 0;
    boolean ok = false;
    for (long s : slots) {
      if (macEqual(gotMac, mac(serverPub, concat(masked, slotBytes(s))))) {
        matchedSlot = s;
        ok = true;
        break;
      }
    }
    if (!ok) return null;
    long slot = matchedSlot;

    byte[] ePub = maskPub(masked, serverPub, slot);
    byte[] ss1 = x25519(serverScalar, ePub);
    byte[] k1 = hkdf(ss1, slotBytes(slot), INFO_C2S, KEY_LEN);

    int ctLen = readU16(in);
    if (ctLen > MAX_CT) throw new IOException("hello too large");
    byte[] ct = readN(in, ctLen);
    byte[] pt = open(k1, zeroNonce12(), masked, ct);

    ClientHello hello = parseClientPlaintext(pt);
    long now = System.currentTimeMillis() / 1000L;
    if (hello.tsSec < now - REPLAY_WINDOW || hello.tsSec > now + REPLAY_WINDOW) return null;
    if (seen != null && !seen.add(hello.cliNonce, hello.tsSec)) return null;

    byte[] sScalar = new byte[KEY_LEN];
    RND.nextBytes(sScalar);
    byte[] sEPub = pubFromScalar(sScalar);
    byte[] maskedS = maskPub(sEPub, serverPub, slot);
    byte[] ss3 = x25519(sScalar, ePub);
    byte[] k2 = hkdf(ss3, slotBytes(slot), INFO_S2C, KEY_LEN);
    byte[] srvNonce = new byte[NONCE_LEN];
    RND.nextBytes(srvNonce);
    byte[] pad = new byte[new Poly(k2, slot, "hspad").intRange(0, 512)];
    byte[] spt = buildServerPlaintext(srvNonce, capsBytes == null ? new byte[0] : capsBytes, pad);
    byte[] sct = seal(k2, zeroNonce12(), maskedS, spt);

    out.write(maskedS);
    writeU16(out, sct.length);
    out.write(sct);
    out.flush();

    byte[] secret = deriveSecret(ss3, ss1, hello.cliNonce, srvNonce);
    return new Accepted(sessionKeys(secret, hello.cliNonce, srvNonce, false), hello, slot);
  }

  static ClientHello parseClientPlaintext(byte[] pt) throws IOException {
    Cur r = new Cur(pt);
    int ver = r.u8();
    byte role = (byte) r.u8();
    if (ver != VERSION) throw new IOException("bad version");
    byte[] cliNonce = r.n(NONCE_LEN);
    long tsSec = r.u64();
    byte[] tok = r.lenPrefixed();
    byte[] opts = r.lenPrefixed();
    r.lenPrefixed();
    return new ClientHello(role, new String(tok, StandardCharsets.UTF_8), opts, cliNonce, tsSec);
  }

  static byte[] buildServerPlaintext(byte[] srvNonce, byte[] caps, byte[] pad) {
    byte[] out = new byte[NONCE_LEN + 2 + caps.length + 2 + pad.length];
    int o = 0;
    System.arraycopy(srvNonce, 0, out, o, NONCE_LEN);
    o += NONCE_LEN;
    out[o++] = (byte) (caps.length >> 8);
    out[o++] = (byte) caps.length;
    System.arraycopy(caps, 0, out, o, caps.length);
    o += caps.length;
    out[o++] = (byte) (pad.length >> 8);
    out[o++] = (byte) pad.length;
    System.arraycopy(pad, 0, out, o, pad.length);
    return out;
  }

  static byte[] readN(InputStream in, int n) throws IOException {
    byte[] b = new byte[n];
    int off = 0;
    while (off < n) {
      int r = in.read(b, off, n - off);
      if (r == -1) throw new IOException("eof");
      off += r;
    }
    return b;
  }

  static int readU16(InputStream in) throws IOException {
    int hi = in.read();
    int lo = in.read();
    if (hi == -1 || lo == -1) throw new IOException("eof");
    return (hi << 8) | lo;
  }

  static void writeU16(OutputStream out, int v) throws IOException {
    out.write((v >>> 8) & 0xff);
    out.write(v & 0xff);
  }

  static final class Cur {
    final byte[] b;
    int off;

    Cur(byte[] b) {
      this.b = b;
    }

    int u8() throws IOException {
      if (off >= b.length) throw new IOException("short");
      return b[off++] & 0xff;
    }

    byte[] n(int n) throws IOException {
      if (off + n > b.length) throw new IOException("short");
      byte[] v = Arrays.copyOfRange(b, off, off + n);
      off += n;
      return v;
    }

    long u64() throws IOException {
      byte[] x = n(8);
      long v = 0;
      for (int i = 0; i < 8; i++) v = (v << 8) | (x[i] & 0xff);
      return v;
    }

    byte[] lenPrefixed() throws IOException {
      int n = (u8() << 8) | u8();
      return n(n);
    }
  }

  interface ReplayCache {
    boolean add(byte[] nonce, long tsSec);
  }
}
