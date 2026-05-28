package dev.c0redev.volter;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class RelayCopyPool {
  private static final int SIZE = Math.min(256, Math.max(64, Runtime.getRuntime().availableProcessors() * 16));
  private static final int QUEUE_SIZE = SIZE * 64;
  private static final ExecutorService POOL = new ThreadPoolExecutor(
      SIZE,
      SIZE,
      0L,
      TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(QUEUE_SIZE),
      new Factory(),
      new ThreadPoolExecutor.CallerRunsPolicy());

  private RelayCopyPool() {}

  static Executor executor() {
    return POOL;
  }

  static Future<?> submit(Runnable task, String name) {
    return POOL.submit(() -> {
      Thread.currentThread().setName(name);
      task.run();
    });
  }

  private static final class Factory implements ThreadFactory {
    private final AtomicInteger n = new AtomicInteger();

    @Override
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r, "relay-copy-" + n.incrementAndGet());
      t.setDaemon(true);
      return t;
    }
  }
}
