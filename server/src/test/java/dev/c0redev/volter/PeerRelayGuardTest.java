package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PeerRelayGuardTest {
  @Test
  void allowValidSignedIdentity() {
    PeerRelayGuard g = new PeerRelayGuard();
    String sig = ProtocolTest.hmacSig("token", "peer-a", "n-1");
    Protocol.ClientOptions opt =
        new Protocol.ClientOptions(32, 1, 1, 2, 256, "peer-a", "n-1", sig, "", "", "r-1", 1, java.util.List.of(), "", "", "");
    assertTrue(g.allow(opt, "token"));
  }

  @Test
  void rejectBadSignature() {
    PeerRelayGuard g = new PeerRelayGuard();
    Protocol.ClientOptions opt =
        new Protocol.ClientOptions(32, 1, 1, 2, 256, "peer-a", "n-1", "bad", "", "", "r-1", 1, java.util.List.of(), "", "", "");
    assertFalse(g.allow(opt, "token"));
  }
}
