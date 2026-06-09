package dev.c0redev.volter;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class AeadStream {

  private static final int TAG = 16;
  private static final int LEN_HDR = 2 + TAG;
  private static final int MAX_FRAME = 16384;
  private static final int MAX_PAD = 1024;
  private static final int MAX_CHUNK = 2 + MAX_FRAME + MAX_PAD + TAG;

  private final Dexote.Keys keys;
  private final Poly txPoly;
  private final TrafficShaper shaper;
  private long txND, txNL, rxND, rxNL;

  AeadStream(Dexote.Keys keys, Poly txPoly) {
    this(keys, txPoly, null);
  }

  AeadStream(Dexote.Keys keys, Poly txPoly, TrafficShaper shaper) {
    this.keys = keys;
    this.txPoly = txPoly;
    this.shaper = shaper;
  }

  private static byte[] nonce12(long ctr) {
    byte[] n = new byte[12];
    for (int i = 0; i < 8; i++) {
      n[4 + i] = (byte) (ctr & 0xff);
      ctr >>>= 8;
    }
    return n;
  }

  OutputStream wrapOutput(OutputStream out) {
    return new FilterOutputStream(out) {
      @Override
      public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override
      public synchronized void write(byte[] b, int off, int len) throws IOException {
        while (len > 0) {
          int n = Math.min(len, MAX_FRAME);
          writeFrame(out, b, off, n);
          off += n;
          len -= n;
        }
      }
    };
  }

  private synchronized void writeFrame(OutputStream out, byte[] b, int off, int n)
      throws IOException {
    int shapePad = 0;
    if (shaper != null && shaper.enabled()) {
      TrafficShaper.Decision d = shaper.next(n);
      if (d.delayMs() > 0) {
        try {
          Thread.sleep(d.delayMs());
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
      if (d.targetLen() > n) {
        shapePad = d.targetLen() - n;
      }
    }
    int pad = txPoly.padLen(MAX_PAD) + shapePad;
    if (pad > MAX_PAD) {
      pad = MAX_PAD;
    }
    byte[] pt = new byte[2 + n + pad];
    pt[0] = (byte) (n >> 8);
    pt[1] = (byte) n;
    System.arraycopy(b, off, pt, 2, n);

    byte[] encData = Dexote.seal(keys.txKey, nonce12(txND++), null, pt);
    byte[] lp = new byte[] {(byte) (encData.length >> 8), (byte) encData.length};
    byte[] encLen = Dexote.seal(keys.txLenKey, nonce12(txNL++), null, lp);

    out.write(encLen);
    out.write(encData);
    out.flush();
  }

  InputStream wrapInput(InputStream in) {
    return new FilterInputStream(in) {
      private byte[] buf = new byte[0];
      private int bpos;

      @Override
      public int read() throws IOException {
        byte[] one = new byte[1];
        int r = read(one, 0, 1);
        return r == -1 ? -1 : one[0] & 0xff;
      }

      @Override
      public synchronized int read(byte[] b, int off, int len) throws IOException {
        if (bpos >= buf.length) {
          buf = readFrame(in);
          bpos = 0;
          if (buf == null) return -1;
        }
        int n = Math.min(len, buf.length - bpos);
        System.arraycopy(buf, bpos, b, off, n);
        bpos += n;
        return n;
      }
    };
  }

  private synchronized byte[] readFrame(InputStream in) throws IOException {
    int first = in.read();
    if (first == -1) return null;
    byte[] encLen = new byte[LEN_HDR];
    encLen[0] = (byte) first;
    byte[] rest = Dexote.readN(in, LEN_HDR - 1);
    System.arraycopy(rest, 0, encLen, 1, LEN_HDR - 1);
    byte[] lp = open(keys.rxLenKey, nonce12(rxNL++), encLen);
    int dataLen = ((lp[0] & 0xff) << 8) | (lp[1] & 0xff);
    if (dataLen < 2 + TAG || dataLen > MAX_CHUNK) throw new IOException("bad aead frame");
    byte[] encData = Dexote.readN(in, dataLen);
    byte[] pt = open(keys.rxKey, nonce12(rxND++), encData);
    if (pt.length < 2) throw new IOException("bad aead frame");
    int real = ((pt[0] & 0xff) << 8) | (pt[1] & 0xff);
    if (2 + real > pt.length) throw new IOException("bad aead frame");
    byte[] out = new byte[real];
    System.arraycopy(pt, 2, out, 0, real);
    return out;
  }

  private static byte[] open(byte[] key, byte[] nonce, byte[] ct) throws IOException {
    return Dexote.open(key, nonce, null, ct);
  }
}
