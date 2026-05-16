package dev.c0redev.volter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

final class PeerRelayGuard {
  boolean allow(Protocol.ClientOptions opts, String token) {
    if (opts == null) {
      return false;
    }
    if (opts.peerId() == null || opts.peerId().isBlank() || opts.peerId().length() > 128) {
      return false;
    }
    if (opts.relayNonce() == null || opts.relayNonce().isBlank() || opts.relayNonce().length() > 128) {
      return false;
    }
    if (opts.relaySig() == null || opts.relaySig().isBlank() || opts.relaySig().length() > 256) {
      return false;
    }
    if (token == null || token.isBlank()) {
      return false;
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(opts.peerId().getBytes(StandardCharsets.UTF_8));
      mac.update((byte) '|');
      mac.update(opts.relayNonce().getBytes(StandardCharsets.UTF_8));
      byte[] want = mac.doFinal();
      byte[] got = Base64.getUrlDecoder().decode(opts.relaySig());
      return MessageDigest.isEqual(want, got);
    } catch (Exception e) {
      return false;
    }
  }
}
