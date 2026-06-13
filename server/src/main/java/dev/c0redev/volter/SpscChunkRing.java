package dev.c0redev.volter;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

final class SpscChunkRing {
  private final ArrayBlockingQueue<byte[]> q;
  private final AtomicLong bytes = new AtomicLong();
  private final long highWatermarkBytes;
  private final long lowWatermarkBytes;

  SpscChunkRing(int capacity) {
    this(capacity, Math.max(256 * 1024L, (long) Math.max(2, capacity) * 4096L));
  }

  SpscChunkRing(int capacity, long maxBytes) {
    q = new ArrayBlockingQueue<>(Math.max(2, capacity));
    this.highWatermarkBytes = Math.max(64 * 1024L, maxBytes);
    this.lowWatermarkBytes = this.highWatermarkBytes / 2;
  }

  boolean offer(byte[] chunk) {
    int len = chunk != null ? chunk.length : 0;
    // байтовый лимит: отвергаем при переполнении, но пустую очередь всегда принимаем,
    // чтобы один крупный чанк не вызвал дедлок
    if (!q.isEmpty() && bytes.get() + len > highWatermarkBytes) {
      return false;
    }
    if (!q.offer(chunk)) {
      return false;
    }
    bytes.addAndGet(len);
    return true;
  }

  void put(byte[] chunk) throws InterruptedException {
    q.put(chunk);
    bytes.addAndGet(chunk != null ? chunk.length : 0);
  }

  byte[] take() throws InterruptedException {
    byte[] chunk = q.take();
    bytes.addAndGet(-(chunk != null ? chunk.length : 0));
    return chunk;
  }

  boolean belowLowWatermark() {
    return bytes.get() <= lowWatermarkBytes;
  }

  void clear() {
    q.clear();
    bytes.set(0);
  }

  int size() {
    return q.size();
  }
}
