package dev.c0redev.volter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RelayRegistryTest {
  @Test
  void enforcesPerRemoteLimit() {
    RelayRegistry r = new RelayRegistry(64, 2048);
    int ok = 0;
    for (int i = 0; i < 70; i++) {
      if (r.tryAcquire("a")) ok++;
    }
    assertEquals(64, ok);
    assertFalse(r.tryAcquire("a"));
    r.release("a");
    assertTrue(r.tryAcquire("a"));
  }
}

