package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientOptionsTest {

  @Test
  void testToJsonForClusterRelay_withClusterPreferredServer() {
    var opts = new Protocol.ClientOptions(
        32, // padS4
        0,  // probeObfsProfileId
        1,  // relayHop
        2,  // relayMaxHop
        0,  // relayBudgetKbps
        "", // peerId
        "", // relayNonce
        "", // relaySig
        "s-test123", // sessionId
        "resume-token", // resumeToken
        "route-1", // routeId
        0, // hopIndex
        "ru-1.example:443", // clusterPreferredServer
        "", // tlsProfileId
        ""  // ja3TargetHash
    );

    String json = opts.toJsonForClusterRelay();
    
    assertTrue(json.contains("\"clusterPreferredServer\":\"ru-1.example:443\""), 
        "JSON should contain clusterPreferredServer: " + json);
    assertTrue(json.contains("\"relayHop\":1"), 
        "JSON should contain relayHop: " + json);
    assertTrue(json.contains("\"relayMaxHop\":2"), 
        "JSON should contain relayMaxHop: " + json);
    assertTrue(json.contains("\"sessionId\":\"s-test123\""), 
        "JSON should contain sessionId: " + json);
    assertTrue(json.contains("\"resumeToken\":\"resume-token\""), 
        "JSON should contain resumeToken: " + json);
    assertTrue(json.contains("\"routeId\":\"route-1\""), 
        "JSON should contain routeId: " + json);
  }

  @Test
  void testToJsonForClusterRelay_withoutClusterPreferredServer() {
    var opts = new Protocol.ClientOptions(
        32, // padS4
        0,  // probeObfsProfileId
        1,  // relayHop
        2,  // relayMaxHop
        0,  // relayBudgetKbps
        "", // peerId
        "", // relayNonce
        "", // relaySig
        "s-test456", // sessionId
        "", // resumeToken
        "", // routeId
        0, // hopIndex
        "", // clusterPreferredServer (empty)
        "", // tlsProfileId
        ""  // ja3TargetHash
    );

    String json = opts.toJsonForClusterRelay();
    
    assertFalse(json.contains("clusterPreferredServer"), 
        "JSON should not contain clusterPreferredServer when empty: " + json);
    assertTrue(json.contains("\"relayHop\":1"), 
        "JSON should contain relayHop: " + json);
    assertTrue(json.contains("\"sessionId\":\"s-test456\""), 
        "JSON should contain sessionId: " + json);
  }

  @Test
  void testParse_withClusterPreferredServer() {
    String json = "{\"relayHop\":1,\"relayMaxHop\":2,\"sessionId\":\"s-abc\",\"clusterPreferredServer\":\"de-2.example:443\"}";
    
    var result = Protocol.ClientOptions.parse(json);
    
    assertTrue(result.isPresent(), "parse should succeed");
    var opts = result.get();
    assertEquals("de-2.example:443", opts.clusterPreferredServer(), 
        "clusterPreferredServer should be parsed");
    assertEquals(1, opts.relayHop(), "relayHop should be parsed");
    assertEquals(2, opts.relayMaxHop(), "relayMaxHop should be parsed");
    assertEquals("s-abc", opts.sessionId(), "sessionId should be parsed");
  }

  @Test
  void testParse_withoutClusterPreferredServer() {
    String json = "{\"relayHop\":1,\"relayMaxHop\":2,\"sessionId\":\"s-xyz\"}";
    
    var result = Protocol.ClientOptions.parse(json);
    
    assertTrue(result.isPresent(), "parse should succeed");
    var opts = result.get();
    assertEquals("", opts.clusterPreferredServer(), 
        "clusterPreferredServer should be empty when not in JSON");
    assertEquals(1, opts.relayHop(), "relayHop should be parsed");
    assertEquals(2, opts.relayMaxHop(), "relayMaxHop should be parsed");
    assertEquals("s-xyz", opts.sessionId(), "sessionId should be parsed");
  }

  @Test
  void testRoundtrip_clusterPreferredServer() {
    var original = new Protocol.ClientOptions(
        32, 0, 1, 2, 0, "", "", "", 
        "s-roundtrip", "token-123", "route-x", 1,
        "ru-1.example:443", "", ""
    );

    String json = original.toJsonForClusterRelay();
    var parsed = Protocol.ClientOptions.parse(json);
    
    assertTrue(parsed.isPresent(), "parse should succeed");
    var restored = parsed.get();
    
    assertEquals(original.clusterPreferredServer(), restored.clusterPreferredServer(),
        "clusterPreferredServer should survive roundtrip");
    assertEquals(original.relayHop(), restored.relayHop(),
        "relayHop should survive roundtrip");
    assertEquals(original.relayMaxHop(), restored.relayMaxHop(),
        "relayMaxHop should survive roundtrip");
    assertEquals(original.sessionId(), restored.sessionId(),
        "sessionId should survive roundtrip");
    assertEquals(original.resumeToken(), restored.resumeToken(),
        "resumeToken should survive roundtrip");
    assertEquals(original.routeId(), restored.routeId(),
        "routeId should survive roundtrip");
    assertEquals(original.hopIndex(), restored.hopIndex(),
        "hopIndex should survive roundtrip");
  }
}
