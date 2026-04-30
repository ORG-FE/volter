package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QuicSignalsTest {
  @Test
  void backpressureCounterIncrements() {
    long a = QuicSignals.ingressBackpressureEvents();
    QuicSignals.noteIngressBackpressure();
    long b = QuicSignals.ingressBackpressureEvents();
    assertTrue(b > a);
  }
}
