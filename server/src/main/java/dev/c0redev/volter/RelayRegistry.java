package dev.c0redev.volter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class RelayRegistry {
  private static final int MAX_PER_REMOTE = 64;
  private final Map<String, AtomicInteger> active = new ConcurrentHashMap<>();

  boolean tryAcquire(String remote) {
    AtomicInteger v = active.computeIfAbsent(remote == null ? "?" : remote, k -> new AtomicInteger());
    while (true) {
      int cur = v.get();
      if (cur >= MAX_PER_REMOTE) return false;
      if (v.compareAndSet(cur, cur + 1)) return true;
    }
  }

  void release(String remote) {
    AtomicInteger v = active.get(remote == null ? "?" : remote);
    if (v == null) return;
    while (true) {
      int cur = v.get();
      if (cur <= 0) return;
      if (v.compareAndSet(cur, cur - 1)) return;
    }
  }
}
