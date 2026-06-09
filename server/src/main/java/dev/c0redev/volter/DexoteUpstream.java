package dev.c0redev.volter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

final class DexoteUpstream {

  private DexoteUpstream() {}

  private static volatile byte[] nodeDexotePub;

  static void setNodeDexotePub(byte[] pub) {
    nodeDexotePub = pub;
  }

  static byte[] upstreamPub() {
    return nodeDexotePub;
  }

  static final class Streams {
    final InputStream in;
    final OutputStream out;

    Streams(InputStream in, OutputStream out) {
      this.in = in;
      this.out = out;
    }
  }

  static Streams dial(
      Socket upstream,
      byte[] upstreamPub,
      long slot,
      byte role,
      String token,
      byte[] opts,
      int handshakeTimeoutMs)
      throws IOException {
    if (upstreamPub == null || upstreamPub.length != 32) {
      throw new IOException("dexote upstream: bad pubkey");
    }
    try {
      upstream.setSoTimeout(Math.max(1_000, handshakeTimeoutMs));
    } catch (IOException ignored) {
    }
    Dexote.Connected con = Dexote.connect(
        upstream.getInputStream(),
        upstream.getOutputStream(),
        upstreamPub,
        slot,
        role,
        token,
        opts);
    AeadStream aead = new AeadStream(con.keys, new Poly(con.keys.secret, slot, "tx"));
    OutputStream out = aead.wrapOutput(upstream.getOutputStream());
    InputStream in = aead.wrapInput(upstream.getInputStream());
    try {
      upstream.setSoTimeout(0);
    } catch (IOException ignored) {
    }
    return new Streams(in, out);
  }

  static void writeTcpConnect(OutputStream aeadOut, Protocol.TcpConnect c) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    b.write(c.addrType() & 0xff);
    b.write(c.ip().getAddress());
    b.write((c.port() >> 8) & 0xff);
    b.write(c.port() & 0xff);
    aeadOut.write(b.toByteArray());
    aeadOut.flush();
  }
}
