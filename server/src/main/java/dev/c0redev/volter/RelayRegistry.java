package dev.c0redev.volter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class RelayRegistry {
  private final Map<String, AtomicInteger> active = new ConcurrentHashMap<>();
  private final AtomicInteger total = new AtomicInteger();
  private final int maxPerRemote;
  private final int maxTotal;

  RelayRegistry(int maxPerRemote, int maxTotal) {
    this.maxPerRemote = Math.max(1, maxPerRemote);
    this.maxTotal = Math.max(1, maxTotal);
  }

  boolean tryAcquire(String remote) {
    AtomicInteger v = active.computeIfAbsent(remote == null ? "?" : remote, k -> new AtomicInteger());
    while (true) {
      int curT = total.get();
      if (curT >= maxTotal) return false;
      if (total.compareAndSet(curT, curT + 1)) break;
    }
    while (true) {
      int cur = v.get();
      if (cur >= maxPerRemote) {
        total.decrementAndGet();
        return false;
      }
      if (v.compareAndSet(cur, cur + 1)) return true;
    }
  }

  void release(String remote) {
    AtomicInteger v = active.get(remote == null ? "?" : remote);
    total.updateAndGet(x -> x <= 0 ? 0 : x - 1);
    if (v == null) return;
    while (true) {
      int cur = v.get();
      if (cur <= 0) return;
      if (v.compareAndSet(cur, cur - 1)) return;
    }
  }
}
