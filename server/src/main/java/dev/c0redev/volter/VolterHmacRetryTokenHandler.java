package dev.c0redev.volter;

import io.netty.buffer.ByteBuf;
import io.netty.incubator.codec.quic.QuicTokenHandler;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.logging.Logger;

final class VolterHmacRetryTokenHandler implements QuicTokenHandler {

  private static final Logger TK = Logger.getLogger("dev.c0redev.volter.quic.token");
  private static final byte VERSION = 1;
  private static final long TTL_MS = 120_000;

  private final byte[] hmacKey;

  VolterHmacRetryTokenHandler(String sharedToken) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      this.hmacKey = md.digest(sharedToken.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public boolean writeToken(ByteBuf out, ByteBuf dcid, InetSocketAddress address) {
    try {
      long exp = System.currentTimeMillis() + TTL_MS;
      byte[] dcidBytes = new byte[dcid.readableBytes()];
      dcid.getBytes(dcid.readerIndex(), dcidBytes);

      byte[] payload = new byte[1 + 8 + dcidBytes.length];
      payload[0] = VERSION;
      for (int i = 0; i < 8; i++) {
        payload[1 + i] = (byte) ((exp >> (56 - 8 * i)) & 0xff);
      }
      System.arraycopy(dcidBytes, 0, payload, 9, dcidBytes.length);

      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
      byte[] sig = mac.doFinal(payload);

      if (out.writableBytes() < payload.length + sig.length) {
        return false;
      }
      out.writeBytes(payload);
      out.writeBytes(sig);
      if (Log.quicTrace()) {
        TK.info("[quic-trace] retry token written exp=" + exp + " peer=" + address);
      }
      return true;
    } catch (Exception e) {
      TK.warning("retry token write failed: " + e.getMessage());
      return false;
    }
  }

  @Override
  public int validateToken(ByteBuf token, InetSocketAddress address) {
    try {
      int start = token.readerIndex();
      int total = token.readableBytes();
      if (total < 9 + 32) {
        return 0;
      }
      byte ver = token.readByte();
      if (ver != VERSION) {
        token.readerIndex(start);
        return 0;
      }
      long exp = token.readLong();
      int rest = token.readableBytes();
      if (rest < 32) {
        token.readerIndex(start);
        return 0;
      }
      byte[] dcidBytes = new byte[rest - 32];
      token.readBytes(dcidBytes);
      byte[] sig = new byte[32];
      token.readBytes(sig);

      if (System.currentTimeMillis() > exp) {
        token.readerIndex(start);
        return 0;
      }

      byte[] payload = new byte[1 + 8 + dcidBytes.length];
      payload[0] = VERSION;
      for (int i = 0; i < 8; i++) {
        payload[1 + i] = (byte) ((exp >> (56 - 8 * i)) & 0xff);
      }
      System.arraycopy(dcidBytes, 0, payload, 9, dcidBytes.length);

      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
      byte[] want = mac.doFinal(payload);
      if (!MessageDigest.isEqual(want, sig)) {
        token.readerIndex(start);
        return 0;
      }
      return total;
    } catch (Exception e) {
      return 0;
    }
  }

  @Override
  public int maxTokenLength() {
    return 256;
  }
}
