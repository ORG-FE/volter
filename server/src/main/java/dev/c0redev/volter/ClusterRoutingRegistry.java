package dev.c0redev.volter;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ClusterRoutingRegistry {
  record PreparedExit(String id, String routeId, String peerId, String dstHost, int dstPort, long expiresAtMs) {}

  private static final ClusterRoutingRegistry INSTANCE = new ClusterRoutingRegistry();

  static ClusterRoutingRegistry get() {
    return INSTANCE;
  }

  private final ConcurrentHashMap<String, Long> correlationExpiryMs = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, PreparedExit> preparedExits = new ConcurrentHashMap<>();

  ClusterRoutingRegistry() {}

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

  PreparedExit prepareExit(String routeId, String peerId, String dstHost, int dstPort, long expiresAtMs) {
    if (routeId == null || routeId.isBlank() || peerId == null || peerId.isBlank() ||
        dstHost == null || dstHost.isBlank() || dstPort <= 0 || expiresAtMs <= 0) {
      return null;
    }
    PreparedExit p = new PreparedExit(
        "pe-" + UUID.randomUUID(),
        routeId.trim(),
        peerId.trim(),
        dstHost.trim(),
        dstPort,
        expiresAtMs);
    preparedExits.put(p.id, p);
    return p;
  }

  PreparedExit consumePreparedExit(String id, String peerId, long nowMs) {
    if (id == null || id.isBlank() || peerId == null || peerId.isBlank()) {
      return null;
    }
    PreparedExit p = preparedExits.remove(id.trim());
    if (p == null) {
      return null;
    }
    if (p.expiresAtMs <= nowMs || !p.peerId.equals(peerId.trim())) {
      return null;
    }
    return p;
  }

  private void sweep() {
    long now = System.currentTimeMillis();
    for (var e : correlationExpiryMs.entrySet()) {
      if (e.getValue() != null && e.getValue() < now) {
        correlationExpiryMs.remove(e.getKey(), e.getValue());
      }
    }
    sweepPrepared(now);
  }

  private void sweepPrepared(long now) {
    for (var e : preparedExits.entrySet()) {
      PreparedExit p = e.getValue();
      if (p == null || p.expiresAtMs <= now) {
        preparedExits.remove(e.getKey(), p);
      }
    }
  }
}
