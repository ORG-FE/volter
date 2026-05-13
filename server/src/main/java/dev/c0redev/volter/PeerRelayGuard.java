package dev.c0redev.volter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.logging.Logger;

final class PeerRelayGuard {
  private static final Logger log = Log.logger(PeerRelayGuard.class);

  boolean allow(Protocol.ClientOptions opts, String token) {
    if (opts == null) {
      log.warning("Peer relay rejected: opts is null");
      return false;
    }
    if (opts.peerId() == null || opts.peerId().isBlank() || opts.peerId().length() > 128) {
      log.warning("Peer relay rejected: invalid peerId (blank=" + (opts.peerId() == null || opts.peerId().isBlank()) + 
                  ", len=" + (opts.peerId() == null ? 0 : opts.peerId().length()) + ")");
      return false;
    }
    if (opts.relayNonce() == null || opts.relayNonce().isBlank() || opts.relayNonce().length() > 128) {
      log.warning("Peer relay rejected: invalid relayNonce for peerId=" + opts.peerId());
      return false;
    }
    if (opts.relaySig() == null || opts.relaySig().isBlank() || opts.relaySig().length() > 256) {
      log.warning("Peer relay rejected: invalid relaySig for peerId=" + opts.peerId());
      return false;
    }
    if (token == null || token.isBlank()) {
      log.warning("Peer relay rejected: token is blank");
      return false;
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(opts.peerId().getBytes(StandardCharsets.UTF_8));
      mac.update((byte) '|');
      mac.update(opts.relayNonce().getBytes(StandardCharsets.UTF_8));
      byte[] want = mac.doFinal();
      byte[] got = decodeSig(opts.relaySig());
      boolean valid = MessageDigest.isEqual(want, got);
      if (!valid) {
        log.warning("Peer relay rejected: HMAC signature mismatch for peerId=" + opts.peerId());
      }
      return valid;
    } catch (Exception e) {
      log.warning("Peer relay rejected: exception during verification for peerId=" + 
                  (opts.peerId() != null ? opts.peerId() : "null") + ": " + e.getMessage());
      return false;
    }
  }

  private byte[] decodeSig(String s) {
    try {
      return Base64.getUrlDecoder().decode(s);
    } catch (IllegalArgumentException ignored) {
    }
    return Base64.getDecoder().decode(s);
  }
}
