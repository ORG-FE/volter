package dev.c0redev.volter;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class RelayCopyPool {
  private static final int SIZE = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
  private static final ExecutorService POOL = Executors.newFixedThreadPool(SIZE, new Factory());

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
