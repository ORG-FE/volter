package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTcpExitBridgeIntegrationTest {

  @Test
  void testClusterPreferredServerStrippedBeforeExit() {
    // симулируем клиента, который подключается к de-2 с clusterPreferredServer=ru-1
    String clientJson = "{\"relayHop\":1,\"relayMaxHop\":2,\"sessionId\":\"s-client123\",\"resumeToken\":\"token-abc\",\"clusterPreferredServer\":\"ru-1.example:443\"}";
    
    var clientOpts = Protocol.ClientOptions.parse(clientJson);
    assertTrue(clientOpts.isPresent(), "client options should parse");
    
    assertEquals("ru-1.example:443", clientOpts.get().clusterPreferredServer(),
        "client should have clusterPreferredServer");
    
    // симулируем relay: de-2 формирует JSON для отправки на ru-1
    String relayJson = clientOpts.get().toJsonForClusterRelay();
    
    assertFalse(relayJson.contains("clusterPreferredServer"),
        "relay JSON must strip clusterPreferredServer before exit handles the flow: " + relayJson);
    
    // симулируем ru-1: парсим JSON от de-2
    var relayOpts = Protocol.ClientOptions.parse(relayJson);
    assertTrue(relayOpts.isPresent(), "relay options should parse");
    
    assertEquals("", relayOpts.get().clusterPreferredServer(),
        "ru-1 should not receive clusterPreferredServer or it may bridge to itself");
    
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
  void testRelayPayloadKeepsSessionButStripsExitDirective() {
    String clientJson = "{\"relayHop\":2,\"relayMaxHop\":3,\"sessionId\":\"s-multihop\",\"routeId\":\"route-123\",\"hopIndex\":0,\"clusterPreferredServer\":\"ru-1.example:443\"}";
    
    var hop0 = Protocol.ClientOptions.parse(clientJson);
    assertTrue(hop0.isPresent());
    assertEquals("ru-1.example:443", hop0.get().clusterPreferredServer());
    assertEquals(0, hop0.get().hopIndex());
    
    String relayJson = hop0.get().toJsonForClusterRelay();
    var exitOpts = Protocol.ClientOptions.parse(relayJson);
    assertTrue(exitOpts.isPresent());
    assertEquals("", exitOpts.get().clusterPreferredServer(),
        "exit directive must be stripped when the bridge opens the exit connection");

    // проверяем, что sessionId и routeId сохранились
    assertEquals("s-multihop", exitOpts.get().sessionId());
    assertEquals("route-123", exitOpts.get().routeId());
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
