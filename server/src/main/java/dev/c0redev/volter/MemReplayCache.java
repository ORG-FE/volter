package dev.c0redev.volter;

import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class MemReplayCache implements Dexote.ReplayCache {

  private final Map<String, Long> seen = new HashMap<>();
  private final long windowSec;

  MemReplayCache() {

    this.windowSec = 90L * 2;
  }

  @Override
  public synchronized boolean add(byte[] nonce, long tsSec) {
    long now = System.currentTimeMillis() / 1000L;
    Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, Long> e = it.next();
      if (e.getValue() < now - windowSec) it.remove();
    }
    String key = Base64.getEncoder().encodeToString(nonce);
    if (seen.containsKey(key)) return false;
    seen.put(key, now);
    return true;
  }
}
