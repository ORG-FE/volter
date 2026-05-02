package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTcpExitBridgeTest {

  @Test
  void parseHostPortIpv6Bracketed() throws Exception {
    InetSocketAddress a = ClusterTcpExitBridge.parseHostPort("[2001:db8::1]:8443");
    assertEquals(8443, a.getPort());
    assertEquals(InetAddress.getByName("2001:db8::1"), a.getAddress());
  }

  @Test
  void parseHostPortIpv4() throws Exception {
    InetSocketAddress a = ClusterTcpExitBridge.parseHostPort("127.0.0.1:18080");
    assertEquals(18080, a.getPort());
    assertEquals(InetAddress.getByName("127.0.0.1"), a.getAddress());
  }

  @Test
  void parseHostPortRejectsEmptyAndMissingPort() {
    assertThrows(java.io.IOException.class, () -> ClusterTcpExitBridge.parseHostPort(""));
    assertThrows(java.io.IOException.class, () -> ClusterTcpExitBridge.parseHostPort("host-only"));
    assertThrows(java.io.IOException.class, () -> ClusterTcpExitBridge.parseHostPort(":"));
  }
}
