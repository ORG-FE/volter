package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PeerRelayForwardTest {

  @Test
  void hasNextHopWhenMorePeersInRoute() {
    String sig = ProtocolTest.hmacSig("token", "p1", "n1");
    var opt =
        new Protocol.ClientOptions(
            32, 0, 1, 2, 0, "p1", "n1", sig, "", "", "r-1", 1,
            List.of("peer_tcp:1.1.1.1:1", "peer_tcp:2.2.2.2:2"),
            "", "", "");
    assertTrue(PeerRelayForward.hasNextHop(opt));
  }

  @Test
  void noNextHopOnLastPeer() {
    String sig = ProtocolTest.hmacSig("token", "p1", "n1");
    var opt =
        new Protocol.ClientOptions(
            32, 0, 2, 2, 0, "p1", "n1", sig, "", "", "r-1", 2,
            List.of("peer_tcp:1.1.1.1:1", "peer_tcp:2.2.2.2:2"),
            "", "", "");
    assertFalse(PeerRelayForward.hasNextHop(opt));
  }

  @Test
  void noNextHopWithoutRoute() {
    var opt =
        new Protocol.ClientOptions(
            32, 0, 1, 2, 0, "p1", "n1", "sig", "", "", "", 1,
            List.of(), "", "", "");
    assertFalse(PeerRelayForward.hasNextHop(opt));
  }
}
