package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {
  @Test
  void defaultCamouflageValuesAreEnabled() throws Exception {
    Path f = Files.createTempFile("volter-config", ".properties");
    Files.writeString(f, """
        listenPorts=18080
        token=test-token
        udpChannels=4
        serverMode=tcp-only
        """, StandardCharsets.UTF_8);
    Config c = Config.load(f);
    assertTrue(c.camouflageTcpEnabled());
    assertEquals("127.0.0.1", c.camouflageTcpProxyHost());
    assertEquals(443, c.camouflageTcpProxyPort());
    assertEquals("nginx", c.camouflageHttpServerName());
    assertTrue(c.peerRelayEnabled());
    assertFalse(c.quicRetryTokens());
    assertEquals("", c.relayIndexFile());
    assertEquals("/volter/relay-index.json", c.relayIndexPath());
    assertEquals("", c.opsHintsPath());
    assertEquals("", c.gossipIndexFile());
    assertEquals("/volter/gossip-nodes.json", c.gossipIndexPath());
    assertEquals("/volter/dht/find", c.dhtFindPath());
    assertEquals("", c.dhtRpcListenUdp());
    assertEquals("test-token", c.dhtRpcSecret());
  }

  @Test
  void explicitCamouflageOverrideWorks() throws Exception {
    Path f = Files.createTempFile("volter-config", ".properties");
    Files.writeString(f, """
        listenPorts=18080
        token=test-token
        udpChannels=4
        serverMode=tcp-only
        camouflageTcpEnabled=false
        camouflageTcpProxyHost=10.0.0.2
        camouflageTcpProxyPort=8443
        camouflageHttpServerName=caddy
        """, StandardCharsets.UTF_8);
    Config c = Config.load(f);
    assertFalse(c.camouflageTcpEnabled());
    assertEquals("10.0.0.2", c.camouflageTcpProxyHost());
    assertEquals(8443, c.camouflageTcpProxyPort());
    assertEquals("caddy", c.camouflageHttpServerName());
  }

  @Test
  void peerRelayAndQuicRetryFlags() throws Exception {
    Path f = Files.createTempFile("volter-config", ".properties");
    Files.writeString(f, """
        listenPorts=18080
        token=test-token
        udpChannels=4
        serverMode=tcp-only
        peerRelayEnabled=false
        quicRetryTokens=true
        """, StandardCharsets.UTF_8);
    Config c = Config.load(f);
    assertFalse(c.peerRelayEnabled());
    assertTrue(c.quicRetryTokens());
  }
}

