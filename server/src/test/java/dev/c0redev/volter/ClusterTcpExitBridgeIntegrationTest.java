package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTcpExitBridgeIntegrationTest {

  @Test
  void testClusterPreferredServerPassedThroughRelay() {
    // симулируем клиента, который подключается к de-2 с clusterPreferredServer=ru-1
    String clientJson = "{\"relayHop\":1,\"relayMaxHop\":2,\"sessionId\":\"s-client123\",\"resumeToken\":\"token-abc\",\"clusterPreferredServer\":\"ru-1.example:443\"}";
    
    var clientOpts = Protocol.ClientOptions.parse(clientJson);
    assertTrue(clientOpts.isPresent(), "client options should parse");
    
    assertEquals("ru-1.example:443", clientOpts.get().clusterPreferredServer(),
        "client should have clusterPreferredServer");
    
    // симулируем relay: de-2 формирует JSON для отправки на ru-1
    String relayJson = clientOpts.get().toJsonForClusterRelay();
    
    assertTrue(relayJson.contains("\"clusterPreferredServer\":\"ru-1.example:443\""),
        "relay JSON should contain clusterPreferredServer: " + relayJson);
    
    // симулируем ru-1: парсим JSON от de-2
    var relayOpts = Protocol.ClientOptions.parse(relayJson);
    assertTrue(relayOpts.isPresent(), "relay options should parse");
    
    assertEquals("ru-1.example:443", relayOpts.get().clusterPreferredServer(),
        "ru-1 should receive clusterPreferredServer from de-2");
    
    // проверяем, что все важные поля сохранились
    assertEquals(clientOpts.get().sessionId(), relayOpts.get().sessionId(),
        "sessionId should be preserved");
    assertEquals(clientOpts.get().resumeToken(), relayOpts.get().resumeToken(),
        "resumeToken should be preserved");
    assertEquals(clientOpts.get().relayHop(), relayOpts.get().relayHop(),
        "relayHop should be preserved");
    assertEquals(clientOpts.get().relayMaxHop(), relayOpts.get().relayMaxHop(),
        "relayMaxHop should be preserved");
  }

  @Test
  void testMultiHopRelayPreservesClusterPreferredServer() {
    // клиент -> de-1 -> de-2 -> ru-1
    // клиент указывает clusterPreferredServer=ru-1, hopIndex=0
    String clientJson = "{\"relayHop\":2,\"relayMaxHop\":3,\"sessionId\":\"s-multihop\",\"routeId\":\"route-123\",\"hopIndex\":0,\"clusterPreferredServer\":\"ru-1.example:443\"}";
    
    var hop0 = Protocol.ClientOptions.parse(clientJson);
    assertTrue(hop0.isPresent());
    assertEquals("ru-1.example:443", hop0.get().clusterPreferredServer());
    assertEquals(0, hop0.get().hopIndex());
    
    // de-1 relay на de-2 (hopIndex=1)
    String hop1Json = hop0.get().toJsonForClusterRelay();
    var hop1 = Protocol.ClientOptions.parse(hop1Json);
    assertTrue(hop1.isPresent());
    assertEquals("ru-1.example:443", hop1.get().clusterPreferredServer(),
        "clusterPreferredServer should survive hop 1");
    
    // de-2 relay на ru-1 (hopIndex=2)
    String hop2Json = hop1.get().toJsonForClusterRelay();
    var hop2 = Protocol.ClientOptions.parse(hop2Json);
    assertTrue(hop2.isPresent());
    assertEquals("ru-1.example:443", hop2.get().clusterPreferredServer(),
        "clusterPreferredServer should survive hop 2");
    
    // проверяем, что sessionId и routeId сохранились через все хопы
    assertEquals("s-multihop", hop2.get().sessionId());
    assertEquals("route-123", hop2.get().routeId());
  }

  @Test
  void testEmptyClusterPreferredServerNotInRelayJson() {
    // клиент без clusterPreferredServer (обычный relay через peer)
    String clientJson = "{\"relayHop\":1,\"relayMaxHop\":2,\"peerId\":\"p-abc123\",\"relayNonce\":\"nonce-xyz\",\"relaySig\":\"sig-def\",\"sessionId\":\"s-peer\"}";
    
    var clientOpts = Protocol.ClientOptions.parse(clientJson);
    assertTrue(clientOpts.isPresent());
    assertEquals("", clientOpts.get().clusterPreferredServer());
    
    String relayJson = clientOpts.get().toJsonForClusterRelay();
    
    assertFalse(relayJson.contains("clusterPreferredServer"),
        "relay JSON should not contain clusterPreferredServer when empty: " + relayJson);
    
    // но другие поля должны быть
    assertTrue(relayJson.contains("\"sessionId\":\"s-peer\""));
    assertTrue(relayJson.contains("\"relayHop\":1"));
  }
}
