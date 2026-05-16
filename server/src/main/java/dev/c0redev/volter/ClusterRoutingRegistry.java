package dev.c0redev.volter;

import java.util.concurrent.ConcurrentHashMap;

final class ClusterRoutingRegistry {

  private static final ClusterRoutingRegistry INSTANCE = new ClusterRoutingRegistry();

  static ClusterRoutingRegistry get() {
    return INSTANCE;
  }

  private final ConcurrentHashMap<String, Long> correlationExpiryMs = new ConcurrentHashMap<>();

  private ClusterRoutingRegistry() {}

  void touchCorrelation(String correlationId, long ttlMs) {
    if (correlationId == null || correlationId.isBlank()) {
      return;
    }
    long until = System.currentTimeMillis() + Math.max(1_000L, ttlMs);
    correlationExpiryMs.put(correlationId.trim(), until);
    sweep();
  }

  boolean isCorrelationFresh(String correlationId) {
    if (correlationId == null || correlationId.isBlank()) {
      return false;
    }
    Long until = correlationExpiryMs.get(correlationId.trim());
    return until != null && until > System.currentTimeMillis();
  }

  private void sweep() {
    long now = System.currentTimeMillis();
    for (var e : correlationExpiryMs.entrySet()) {
      if (e.getValue() != null && e.getValue() < now) {
        correlationExpiryMs.remove(e.getKey(), e.getValue());
      }
    }
  }
}
