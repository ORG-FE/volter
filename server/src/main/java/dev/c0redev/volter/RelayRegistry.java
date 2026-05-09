package dev.c0redev.volter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class RelayRegistry {
  record RelayKey(String ip, String peerId) {}
  record RelayLease(String leaseId, RelayKey key, int budgetKbps, long expiresAtMs) {}

  private final Map<String, RelayLease> leases = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> byIp = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> byPeer = new ConcurrentHashMap<>();
  private final AtomicInteger totalBudget = new AtomicInteger();
  private final int maxPerIp;
  private final int maxPerPeer;
  private final int maxTotal;
  private final int maxKbpsTotal;
  private final int maxKbpsPerPeer;

  RelayRegistry(int maxPerRemote, int maxTotal) {
    this(maxPerRemote, maxPerRemote, maxTotal, Integer.MAX_VALUE, Integer.MAX_VALUE);
  }

  RelayRegistry(int maxPerIp, int maxPerPeer, int maxTotal, int maxKbpsTotal, int maxKbpsPerPeer) {
    this.maxPerIp = Math.max(1, maxPerIp);
    this.maxPerPeer = Math.max(1, maxPerPeer);
    this.maxTotal = Math.max(1, maxTotal);
    this.maxKbpsTotal = Math.max(1, maxKbpsTotal);
    this.maxKbpsPerPeer = Math.max(1, maxKbpsPerPeer);
  }

  boolean tryAcquire(String remote) {
    return tryAcquire(new RelayKey(normalizeIp(remote), ""), 0, System.currentTimeMillis(), 15 * 60_000L) != null;
  }

  synchronized void release(String remote) {
    String ip = normalizeIp(remote);
    for (RelayLease lease : leases.values()) {
      if (lease.key.ip.equals(ip)) {
        release(lease);
        return;
      }
    }
  }

  synchronized RelayLease tryAcquire(RelayKey key, int budgetKbps, long nowMs, long ttlMs) {
    sweepExpired(nowMs);
    RelayKey k = normalizeKey(key);
    int budget = Math.max(0, budgetKbps);
    if (leases.size() >= maxTotal) return null;
    if (count(byIp, k.ip) >= maxPerIp) return null;
    if (!k.peerId.isBlank() && count(byPeer, k.peerId) >= maxPerPeer) return null;
    if (budget > 0 && totalBudget.get() + budget > maxKbpsTotal) return null;
    if (budget > 0 && peerBudget(k.peerId) + budget > maxKbpsPerPeer) return null;

    RelayLease lease = new RelayLease(UUID.randomUUID().toString(), k, budget, nowMs + Math.max(1, ttlMs));
    leases.put(lease.leaseId, lease);
    inc(byIp, k.ip);
    if (!k.peerId.isBlank()) inc(byPeer, k.peerId);
    if (budget > 0) totalBudget.addAndGet(budget);
    return lease;
  }

  synchronized void release(RelayLease lease) {
    if (lease == null) return;
    RelayLease old = leases.remove(lease.leaseId);
    if (old == null) return;
    dec(byIp, old.key.ip);
    if (!old.key.peerId.isBlank()) dec(byPeer, old.key.peerId);
    if (old.budgetKbps > 0) totalBudget.updateAndGet(x -> Math.max(0, x - old.budgetKbps));
  }

  synchronized int sweepExpired(long nowMs) {
    int n = 0;
    for (RelayLease lease : leases.values()) {
      if (lease.expiresAtMs <= nowMs) {
        release(lease);
        n++;
      }
    }
    return n;
  }

  private synchronized int peerBudget(String peerId) {
    if (peerId == null || peerId.isBlank()) return 0;
    int out = 0;
    for (RelayLease lease : leases.values()) {
      if (lease.key.peerId.equals(peerId)) out += lease.budgetKbps;
    }
    return out;
  }

  private static RelayKey normalizeKey(RelayKey key) {
    if (key == null) return new RelayKey("?", "");
    return new RelayKey(normalizeIp(key.ip), key.peerId == null ? "" : key.peerId.trim());
  }

  private static String normalizeIp(String raw) {
    if (raw == null || raw.isBlank()) return "?";
    String s = raw.trim();
    int slash = s.lastIndexOf('/');
    if (slash >= 0) s = s.substring(slash + 1);
    if (s.startsWith("[") && s.contains("]")) {
      return s.substring(1, s.indexOf(']'));
    }
    int colon = s.lastIndexOf(':');
    if (colon > 0 && s.indexOf(':') == colon) {
      return s.substring(0, colon);
    }
    return s;
  }

  private static int count(Map<String, AtomicInteger> m, String key) {
    AtomicInteger v = m.get(key);
    return v == null ? 0 : v.get();
  }

  private static void inc(Map<String, AtomicInteger> m, String key) {
    m.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
  }

  private static void dec(Map<String, AtomicInteger> m, String key) {
    AtomicInteger v = m.get(key);
    if (v == null) return;
    if (v.decrementAndGet() <= 0) m.remove(key, v);
  }
}
