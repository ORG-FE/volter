package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ClusterRuntimeExitTest {

  private static Config testConfig() throws Exception {
    Path f = Files.createTempFile("volter-cluster", ".properties");
    Files.writeString(f, """
        listenPorts=18080
        token=test-token
        udpChannels=4
        serverMode=tcp-only
        publicHost=127.0.0.1
        cluster.nodeId=self-node
        """, StandardCharsets.UTF_8);
    return Config.load(f);
  }

  private static String validPubBase64(byte seed) {
    byte[] scalar = new byte[Dexote.KEY_LEN];
    for (int i = 0; i < scalar.length; i++) {
      scalar[i] = (byte) (seed + i);
    }
    return Base64.getEncoder().encodeToString(Dexote.pubFromScalar(scalar));
  }

  @Test
  void resolveClusterExitReturnsTargetPubFromMap() throws Exception {
    ClusterRuntime rt = ClusterRuntime.get();
    rt.setSelfDexotePub(validPubBase64((byte) 1));
    rt.start(testConfig());

    String exitPub = validPubBase64((byte) 50);
    String map = "{\"v\":1,\"nodes\":[{\"id\":\"exit-node\","
        + "\"endpoint\":\"http://203.0.113.9:25565\","
        + "\"dexotePub\":\"" + exitPub + "\",\"ts\":9999999999999,\"alive\":true}]}";
    rt.mergeFromJson(map);

    Optional<ClusterRuntime.ClusterExitTarget> t = rt.resolveClusterExit("exit-node");
    assertTrue(t.isPresent(), "exit with dexotePub in map must resolve");
    assertEquals(25565, t.get().addr.getPort());
    assertArrayEquals(Base64.getDecoder().decode(exitPub), t.get().dexotePub);
  }

  @Test
  void resolveClusterExitSkipsNodeWithoutDexotePub() throws Exception {
    ClusterRuntime rt = ClusterRuntime.get();
    rt.setSelfDexotePub(validPubBase64((byte) 1));
    rt.start(testConfig());

    String map = "{\"v\":1,\"nodes\":[{\"id\":\"nopub-node\","
        + "\"endpoint\":\"http://203.0.113.10:25566\","
        + "\"ts\":9999999999999,\"alive\":true}]}";
    rt.mergeFromJson(map);

    Optional<ClusterRuntime.ClusterExitTarget> t = rt.resolveClusterExit("nopub-node");
    assertTrue(t.isEmpty(), "exit without dexotePub must be skipped, not bridged with wrong key");
  }

  @Test
  void clusterMapJsonPublishesSelfDexotePub() throws Exception {
    ClusterRuntime rt = ClusterRuntime.get();
    String selfPub = validPubBase64((byte) 7);
    rt.setSelfDexotePub(selfPub);
    rt.start(testConfig());

    String json = rt.clusterMapJson();
    assertTrue(json.contains("\"dexotePub\":\"" + selfPub + "\""),
        "self node must publish its dexotePub in cluster map; got " + json);
  }
}
