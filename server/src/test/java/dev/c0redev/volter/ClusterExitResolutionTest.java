package dev.c0redev.volter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterExitResolutionTest {

  @BeforeEach
  void resetRuntime() throws Exception {
    ClusterRuntime rt = ClusterRuntime.get();

    Field running = ClusterRuntime.class.getDeclaredField("running");
    running.setAccessible(true);
    running.set(rt, false);

    Field worker = ClusterRuntime.class.getDeclaredField("worker");
    worker.setAccessible(true);
    Thread t = (Thread) worker.get(rt);
    if (t != null) {
      t.interrupt();
    }
    worker.set(rt, null);

    Field cfg = ClusterRuntime.class.getDeclaredField("cfg");
    cfg.setAccessible(true);
    cfg.set(rt, null);

    Field nodes = ClusterRuntime.class.getDeclaredField("nodes");
    nodes.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, ?> map = (Map<String, ?>) nodes.get(rt);
    map.clear();
  }

  @Test
  void resolveClusterExitDialAddressRejectsLoopbackAdvertise() throws Exception {
    mergeNode("ru-1", "http://127.0.0.1:18080/volter/cluster-map.json");

    ClusterRuntime rt = ClusterRuntime.get();
    assertTrue(rt.resolveClusterExitDialAddress("ru-1").isEmpty());
    assertFalse(rt.isAuthorizedClusterExit("127.0.0.1:18080"));
  }

  @Test
  void tcpBridgeFailsWhenExitNodeHasNoEndpointEvenWithFallbackEnabled() throws Exception {
    mergeNode("ru-1", "");
    Config cfg = loadConfig("");
    Protocol.TcpConnect connect =
        new Protocol.TcpConnect((byte) 4, InetAddress.getByName("1.1.1.1"), 443);

    assertThrows(IOException.class, () ->
        ClusterTcpExitBridge.maybeBridge(
            cfg,
            connect,
            new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream(),
            Optional.of(clientOpts("ru-1"))));
  }

  @Test
  void tcpBridgeFailsWhenExitHintIsInvalidEvenWithFallbackEnabled() throws Exception {
    Config cfg = loadConfig("");
    Protocol.TcpConnect connect =
        new Protocol.TcpConnect((byte) 4, InetAddress.getByName("8.8.8.8"), 53);

    assertThrows(IOException.class, () ->
        ClusterTcpExitBridge.maybeBridge(
            cfg,
            connect,
            new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream(),
            Optional.of(clientOpts("not-a-host"))));
  }

  @Test
  void udpBridgeFailsWhenExitNodeHasNoEndpointEvenWithFallbackEnabled() throws Exception {
    mergeNode("ru-1", "");
    Config cfg = loadConfig("");

    assertThrows(IOException.class, () ->
        ClusterUdpExitBridge.maybeBridge(
            cfg,
            0,
            new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream(),
            Optional.of(clientOpts("ru-1"))));
  }

  @Test
  void redirectHostPortHintDoesNotInventLoopbackWhenPublicHostMissing() throws Exception {
    Config cfg = loadConfig("");
    ConnectionHandler h = new ConnectionHandler(null, cfg, null, null, null, null);
    Method m = ConnectionHandler.class.getDeclaredMethod("redirectHostPortHint");
    m.setAccessible(true);

    String hint = (String) m.invoke(h);
    assertTrue(hint == null || hint.isBlank());
  }

  private static Protocol.ClientOptions clientOpts(String clusterPreferredServer) {
    return new Protocol.ClientOptions(
        32,
        0,
        1,
        2,
        0,
        "",
        "",
        "",
        "s-test",
        "resume-test",
        "",
        0,
        clusterPreferredServer,
        "",
        "");
  }

  private static Config loadConfig(String extra) throws Exception {
    Path f = Files.createTempFile("volter-config", ".properties");
    Files.writeString(f, """
        listenPorts=18080
        token=test-token
        udpChannels=4
        serverMode=tcp-only
        """ + extra, StandardCharsets.UTF_8);
    return Config.load(f);
  }

  private static void mergeNode(String nodeId, String endpoint) throws Exception {
    Method m = ClusterRuntime.class.getDeclaredMethod("mergeFromJson", String.class);
    m.setAccessible(true);
    String json = "{\"v\":1,\"nodeId\":\"test\",\"nodes\":[{\"id\":\"" + json(nodeId)
        + "\",\"endpoint\":\"" + json(endpoint) + "\"}]}";
    m.invoke(ClusterRuntime.get(), json);
  }

  private static String json(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
