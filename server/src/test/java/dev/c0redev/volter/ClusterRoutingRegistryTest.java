package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClusterRoutingRegistryTest {
  @Test
  void preparedExitIsOneShotAndExpires() {
    ClusterRoutingRegistry r = new ClusterRoutingRegistry();
    long now = 1_000L;
    ClusterRoutingRegistry.PreparedExit p =
        r.prepareExit("route-a", "peer-a", "8.8.8.8", 53, now + 1_000);
    assertNotNull(p);
    assertNotNull(r.consumePreparedExit(p.id(), "peer-a", now));
    assertNull(r.consumePreparedExit(p.id(), "peer-a", now));

    ClusterRoutingRegistry.PreparedExit expired =
        r.prepareExit("route-b", "peer-b", "1.1.1.1", 443, now + 1_000);
    assertNull(r.consumePreparedExit(expired.id(), "peer-b", now + 2_000));
    assertNull(r.consumePreparedExit(expired.id(), "peer-c", now));
  }
}
