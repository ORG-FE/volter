package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class ClusterPreferredCanonicalTest {

  @Test
  void canonicalLowercasesDnsHost() {
    assertEquals("exit.example.org:443", ClusterPreferredCanonical.canonical("Exit.EXAMPLE.org:443"));
  }

  @Test
  void canonicalNormalizesIpv4() {
    assertEquals("10.0.0.1:443", ClusterPreferredCanonical.canonical("10.0.0.1:443"));
  }

  @Test
  void canonicalExtractsParentheses() {
    assertEquals("10.0.0.2:8443", ClusterPreferredCanonical.canonical("pick (10.0.0.2:8443) region"));
  }

  @Test
  void canonicalLeavesBareNodeId() {
    assertEquals("ru-1", ClusterPreferredCanonical.canonical(" ru-1 "));
  }

  @Test
  void joinCanonicalHostPortIpv6Bracketed() throws Exception {
    InetSocketAddress ref = ClusterTcpExitBridge.parseHostPort("[2001:db8:0:0:0:0:0:1]:443");
    String want =
        "[" + ref.getAddress().getHostAddress() + "]:" + ref.getPort();
    assertEquals(want, ClusterPreferredCanonical.joinCanonicalHostPort("[2001:db8:0:0:0:0:0:1]:443"));
  }
}
