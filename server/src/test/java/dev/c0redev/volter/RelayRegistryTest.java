package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RelayRegistryTest {
  @Test
  void enforcesPerIpPerPeerAndTtl() {
    RelayRegistry r = new RelayRegistry(2, 1, 4, 4096, 2048);
    long now = 1_000L;
    RelayRegistry.RelayLease a = r.tryAcquire(new RelayRegistry.RelayKey("1.1.1.1", "peer-a"), 512, now, 1_000);
    assertNotNull(a);
    assertNull(r.tryAcquire(new RelayRegistry.RelayKey("2.2.2.2", "peer-a"), 512, now, 1_000));

    RelayRegistry.RelayLease b = r.tryAcquire(new RelayRegistry.RelayKey("1.1.1.1", "peer-b"), 512, now, 1_000);
    assertNotNull(b);
    assertNull(r.tryAcquire(new RelayRegistry.RelayKey("1.1.1.1", "peer-c"), 512, now, 1_000));

    r.sweepExpired(now + 2_000);
    assertNotNull(r.tryAcquire(new RelayRegistry.RelayKey("1.1.1.1", "peer-c"), 512, now + 2_000, 1_000));
  }

  @Test
  void concurrentAcquireDoesNotExceedCaps() throws Exception {
    RelayRegistry r = new RelayRegistry(16, 16, 1, 1024, 1024);
    int workers = 32;
    ExecutorService exec = Executors.newFixedThreadPool(workers);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger ok = new AtomicInteger();
    List<Throwable> errors = new ArrayList<>();
    for (int i = 0; i < workers; i++) {
      final int n = i;
      exec.submit(() -> {
        try {
          start.await();
          RelayRegistry.RelayLease lease = r.tryAcquire(new RelayRegistry.RelayKey("10.0.0." + n, "peer-" + n), 128, 1_000, 60_000);
          if (lease != null) ok.incrementAndGet();
        } catch (Throwable t) {
          synchronized (errors) {
            errors.add(t);
          }
        }
      });
    }
    start.countDown();
    exec.shutdown();
    assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
    assertTrue(errors.isEmpty(), errors.toString());
    assertEquals(1, ok.get());
  }
}

