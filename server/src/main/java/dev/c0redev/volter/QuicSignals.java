package dev.c0redev.volter;

import java.util.concurrent.atomic.AtomicLong;

final class QuicSignals {

  private static final AtomicLong INGRESS_BACKPRESSURE = new AtomicLong();

  private QuicSignals() {}

  static void noteIngressBackpressure() {
    INGRESS_BACKPRESSURE.incrementAndGet();
  }

  static long ingressBackpressureEvents() {
    return INGRESS_BACKPRESSURE.get();
  }
}
