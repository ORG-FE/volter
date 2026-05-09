package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

class PeerRelayGuardTest {
  @Test
  void allowValidSignedIdentity() {
    PeerRelayGuard g = new PeerRelayGuard();
    String sig = ProtocolTest.hmacSig("token", "peer-a", "n-1");
    Protocol.ClientOptions opt =
        new Protocol.ClientOptions(32, 1, 1, 2, 256, "peer-a", "n-1", sig, "", "", "r-1", 1, "", "", "");
    assertTrue(g.allow(opt, "token"));
  }

  @Test
  void rejectBadSignature() {
    PeerRelayGuard g = new PeerRelayGuard();
    Protocol.ClientOptions opt =
        new Protocol.ClientOptions(32, 1, 1, 2, 256, "peer-a", "n-1", "bad", "", "", "r-1", 1, "", "", "");
    assertFalse(g.allow(opt, "token"));
  }

  @Test
  void allowStandardBase64SignedIdentity() {
    PeerRelayGuard g = new PeerRelayGuard();
    String sig = standardHmacSig("token", "peer-a", "n-1");
    Protocol.ClientOptions opt =
        new Protocol.ClientOptions(32, 1, 1, 2, 256, "peer-a", "n-1", sig, "", "", "r-1", 1, "", "", "");
    assertTrue(g.allow(opt, "token"));
  }

  private static String standardHmacSig(String token, String peerId, String nonce) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(peerId.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) '|');
      mac.update(nonce.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().withoutPadding().encodeToString(mac.doFinal());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
