package dev.c0redev.volter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlStoreTest {
  @TempDir Path tmp;

  @Test
  void verifyManagedChecksHmac() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("control.sqlite"))) {
      String secret = "client-secret";
      String salt = "salt-1";
      String secretHash = sha256(salt + ":" + secret);
      var c = store.createClient("test", "guest", 0, "", secret, salt, secretHash);
      long ts = System.currentTimeMillis() / 1000L;
      String sig = managedSig(secretHash, c.id(), "linux:box", "nonce-1", ts);

      assertTrue(store.verifyManaged(c.id(), "linux:box", "nonce-1", ts, sig));
      assertFalse(store.verifyManaged(c.id(), "linux:box", "nonce-1", ts, sig));
      assertFalse(store.verifyManaged(c.id(), "linux:box", "nonce-1", ts, "bad"));
      assertFalse(store.verifyManaged(c.id(), "linux:box", "nonce-2", ts, sig));
    }
  }

  @Test
  void rotatedClientAcceptsPreviousSecretDuringGrace() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("rotate.sqlite"))) {
      String oldSecret = "old-secret";
      String oldSalt = "old-salt";
      String oldHash = sha256(oldSalt + ":" + oldSecret);
      var c = store.createClient("test", "guest", 0, "", oldSecret, oldSalt, oldHash);
      long now = System.currentTimeMillis() / 1000L;

      String newSecret = "new-secret";
      String newSalt = "new-salt";
      String newHash = sha256(newSalt + ":" + newSecret);
      store.rotateClient(c.id(), newSecret, newSalt, newHash, now + 60);

      assertTrue(store.verifyManaged(c.id(), "linux:box", "old-nonce", now, managedSig(oldHash, c.id(), "linux:box", "old-nonce", now)));
      assertTrue(store.verifyManaged(c.id(), "linux:box", "new-nonce", now, managedSig(newHash, c.id(), "linux:box", "new-nonce", now)));
    }
  }

  @Test
  void rotatedClientRejectsPreviousSecretWithoutGrace() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("rotate-no-grace.sqlite"))) {
      String oldSecret = "old-secret";
      String oldSalt = "old-salt";
      String oldHash = sha256(oldSalt + ":" + oldSecret);
      var c = store.createClient("test", "guest", 0, "", oldSecret, oldSalt, oldHash);
      long now = System.currentTimeMillis() / 1000L;

      String newSecret = "new-secret";
      String newSalt = "new-salt";
      String newHash = sha256(newSalt + ":" + newSecret);
      store.rotateClient(c.id(), newSecret, newSalt, newHash, 0);

      assertFalse(store.verifyManaged(c.id(), "linux:box", "old-nonce", now, managedSig(oldHash, c.id(), "linux:box", "old-nonce", now)));
      assertTrue(store.verifyManaged(c.id(), "linux:box", "new-nonce", now, managedSig(newHash, c.id(), "linux:box", "new-nonce", now)));
    }
  }

  @Test
  void deviceBindingEnforcesLimitAndRevoke() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("devices.sqlite"))) {
      String secret = "client-secret";
      String salt = "salt-1";
      String secretHash = sha256(salt + ":" + secret);
      var c = store.createClient("test", "guest", 0, "", secret, salt, secretHash);
      long ts = System.currentTimeMillis() / 1000L;

      assertTrue(store.updateDevicePolicy(c.id(), "multi", 2));
      assertTrue(verify(store, c.id(), secretHash, "linux:one", "n1", ts));
      assertTrue(verify(store, c.id(), secretHash, "linux:two", "n2", ts));
      assertFalse(verify(store, c.id(), secretHash, "linux:three", "n3", ts));

      assertTrue(store.revokeDevice(c.id(), "linux:one"));
      assertFalse(verify(store, c.id(), secretHash, "linux:one", "n4", ts));
      assertTrue(verify(store, c.id(), secretHash, "linux:three", "n5", ts));
    }
  }

  @Test
  void deviceBindingSingleAndUnlimitedModes() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("device-modes.sqlite"))) {
      String secret = "client-secret";
      String salt = "salt-1";
      String secretHash = sha256(salt + ":" + secret);
      var single = store.createClient("single", "guest", 0, "", secret, salt, secretHash);
      long ts = System.currentTimeMillis() / 1000L;

      assertTrue(store.updateDevicePolicy(single.id(), "single", 1));
      assertTrue(verify(store, single.id(), secretHash, "linux:one", "s1", ts));
      assertFalse(verify(store, single.id(), secretHash, "linux:two", "s2", ts));

      var unlimited = store.createClient("unlimited", "guest", 0, "", secret, salt, secretHash);
      assertTrue(store.updateDevicePolicy(unlimited.id(), "unlimited", 1));
      assertTrue(verify(store, unlimited.id(), secretHash, "linux:one", "u1", ts));
      assertTrue(verify(store, unlimited.id(), secretHash, "linux:two", "u2", ts));
      assertTrue(verify(store, unlimited.id(), secretHash, "linux:three", "u3", ts));
    }
  }

  @Test
  void effectivePolicyMergesGroupAndClientOverrides() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("policy.sqlite"))) {
      var c = store.createClient("test", "user", 0, "", "secret", "salt", sha256("salt:secret"));
      assertTrue(store.updateGroupPolicy("user", new JSONObject()
          .put("dnsServers", new JSONArray().put("1.1.1.1"))
          .put("speedLimitKbps", 1024)
          .put("meshAllowed", false)));
      assertTrue(store.updateClientPolicy(c.id(), new JSONObject()
          .put("speedLimitKbps", 2048)
          .put("relayAllowed", false)));

      JSONObject policy = store.effectivePolicyJson(c.id());
      assertEquals("1.1.1.1", policy.getJSONArray("dnsServers").getString(0));
      assertEquals(2048, policy.getLong("speedLimitKbps"));
      assertFalse(policy.getBoolean("meshAllowed"));
      assertFalse(policy.getBoolean("relayAllowed"));
      assertEquals(c.id(), policy.getString("clientId"));
      assertEquals("user", policy.getString("groupId"));
    }
  }

  @Test
  void trafficSummaryListsCreatedClientTotals() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("traffic.sqlite"))) {
      var c = store.createClient("traffic", "guest", 0, "", "secret", "salt", sha256("salt:secret"));
      store.addTraffic(c.id(), 100, 40);
      store.addTraffic(c.id(), 2, 3);

      JSONObject traffic = store.trafficSummaryJson();
      assertEquals(102, traffic.getLong("rxBytes"));
      assertEquals(43, traffic.getLong("txBytes"));
      assertEquals(c.id(), traffic.getJSONArray("clients").getJSONObject(0).getString("clientId"));
    }
  }

  @Test
  void revokedClientIsHiddenFromDefaultList() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("revoked-list.sqlite"))) {
      var c = store.createClient("revoked", "user", 0, "", "secret", "salt", sha256("salt:secret"));

      assertEquals(1, store.listClientsJson(false).length());
      assertTrue(store.revokeClient(c.id()));
      assertEquals(0, store.listClientsJson(false).length());
      assertEquals(1, store.listClientsJson(true).length());
    }
  }

  @Test
  void activeSessionsStartAndEnd() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("sessions.sqlite"))) {
      var c = store.createClient("sessions", "guest", 0, "", "secret", "salt", sha256("salt:secret"));
      String id = store.startSession(c.id(), "linux:box", "node-1", "tcp", "127.0.0.1");

      JSONArray active = store.activeSessionsJson();
      assertEquals(1, active.length());
      assertEquals(id, active.getJSONObject(0).getString("id"));

      store.endSession(id, 10, 20);
      assertEquals(0, store.activeSessionsJson().length());
    }
  }

  @Test
  void registrySaveAndLoad() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("registry.sqlite"))) {
      JSONObject payload = new JSONObject().put("v", 1).put("nodes", new JSONArray().put(new JSONObject().put("id", "node-1")));
      store.saveRegistry(7, payload, "sig", "pub");

      JSONObject registry = store.registryJson();
      assertEquals(7, registry.getLong("version"));
      assertEquals("sig", registry.getString("sig"));
      assertEquals("node-1", registry.getJSONObject("payload").getJSONArray("nodes").getJSONObject(0).getString("id"));
    }
  }

  @Test
  void clientTrafficReturnsDataForValidBearer() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("cli-traffic.sqlite"))) {
      String secret = "my-secret";
      String salt = "my-salt";
      String secretHash = sha256(salt + ":" + secret);
      var c = store.createClient("traffic-client", "guest", 0, "", secret, salt, secretHash);

      store.addTraffic(c.id(), 1000, 500);

      JSONObject traffic = store.clientTrafficJson(c.id(), secret);
      assertEquals(1000, traffic.getLong("rxBytes"));
      assertEquals(500, traffic.getLong("txBytes"));
    }
  }

  @Test
  void clientTrafficReturnsNullForWrongBearer() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("cli-traffic-bad.sqlite"))) {
      String secret = "my-secret";
      String salt = "my-salt";
      String secretHash = sha256(salt + ":" + secret);
      var c = store.createClient("traffic-client-2", "guest", 0, "", secret, salt, secretHash);

      store.addTraffic(c.id(), 100, 50);

      assertNull(store.clientTrafficJson(c.id(), "wrong-secret"));
    }
  }

  @Test
  void clientTrafficReturnsZerosForClientWithoutTraffic() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("cli-traffic-zero.sqlite"))) {
      String secret = "my-secret";
      String salt = "my-salt";
      String secretHash = sha256(salt + ":" + secret);
      var c = store.createClient("traffic-client-3", "guest", 0, "", secret, salt, secretHash);

      JSONObject traffic = store.clientTrafficJson(c.id(), secret);
      assertEquals(0, traffic.getLong("rxBytes"));
      assertEquals(0, traffic.getLong("txBytes"));
    }
  }

  @Test
  void clientTrafficReturnsNullForNonexistentClient() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("cli-traffic-nonexist.sqlite"))) {
      assertNull(store.clientTrafficJson("nonexistent-id", "any-secret"));
    }
  }

  @Test
  void updateClientGroupChangesGroup() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("group-change.sqlite"))) {
      var c = store.createClient("group-client", "user", 0, "", "secret", "salt", sha256("salt:secret"));
      assertTrue(store.updateClientGroup(c.id(), "volunteer"));

      JSONObject policy = store.effectivePolicyJson(c.id());
      assertEquals("volunteer", policy.getString("groupId"));
    }
  }

  @Test
  void updateClientGroupRejectsNonexistentGroup() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("group-bad.sqlite"))) {
      var c = store.createClient("bad-group", "user", 0, "", "secret", "salt", sha256("salt:secret"));
      assertFalse(store.updateClientGroup(c.id(), "nonexistent-group"));
    }
  }

  @Test
  void dnsLogsSaveListAndCleanup() throws Exception {
    try (ControlStore store = ControlStore.open(tmp.resolve("dns.sqlite"))) {
      store.logDns("cli_1", "dev_1", "Example.COM", "allow", "1.1.1.1", 60);

      JSONArray logs = store.dnsLogsJson(10);
      assertEquals(1, logs.length());
      assertEquals("example.com", logs.getJSONObject(0).getString("domain"));
      assertEquals("allow", logs.getJSONObject(0).getString("action"));
      assertEquals(0, store.cleanupDnsLogs(30));
    }
  }

  private static boolean verify(ControlStore store, String clientId, String secretHash, String deviceId, String nonce, long ts) throws Exception {
    return store.verifyManaged(clientId, deviceId, nonce, ts, managedSig(secretHash, clientId, deviceId, nonce, ts));
  }

  private static String managedSig(String secretHash, String clientId, String deviceId, String nonce, long ts) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secretHash.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String msg = clientId + "|" + deviceId + "|" + nonce + "|" + ts;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
  }

  private static String sha256(String s) throws Exception {
    byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder(b.length * 2);
    for (byte x : b) sb.append(String.format("%02x", x & 0xff));
    return sb.toString();
  }
}
