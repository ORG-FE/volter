package dev.c0redev.volter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TrafficShaperTest {

  @Test
  void disabledIsNoOp() {
    for (String p : new String[] {"", null, "nonexistent"}) {
      TrafficShaper s = TrafficShaper.create(p, 0, 0, 1);
      assertFalse(s.enabled(), "профиль '" + p + "' должен быть no-op");
      TrafficShaper.Decision d = s.next(500);
      assertEquals(0, d.targetLen());
      assertEquals(0, d.delayMs());
    }
  }

  @Test
  void deterministicBySeed() {
    TrafficShaper a = TrafficShaper.create("web", 0, 0, 12345);
    TrafficShaper b = TrafficShaper.create("web", 0, 0, 12345);
    for (int i = 0; i < 1000; i++) {
      int pl = 300 + i % 200;
      TrafficShaper.Decision da = a.next(pl);
      TrafficShaper.Decision db = b.next(pl);
      assertEquals(da.targetLen(), db.targetLen(), "шаг " + i + " targetLen");
      assertEquals(da.delayMs(), db.delayMs(), "шаг " + i + " delay");
    }
  }

  @Test
  void delayCap() {
    int capMs = 50;
    TrafficShaper s = TrafficShaper.create("web", 0, capMs, 7);
    for (int i = 0; i < 5000; i++) {
      TrafficShaper.Decision d = s.next(200);
      assertTrue(d.delayMs() >= 0, "отрицательная задержка");
      assertTrue(d.delayMs() <= capMs, "задержка " + d.delayMs() + " > cap " + capMs);
    }
  }

  @Test
  void overheadCap() {
    int payload = 200;
    int pct = 50;
    TrafficShaper s = TrafficShaper.create("bulk", pct, 0, 9);
    int maxLen = payload + payload * pct / 100;
    for (int i = 0; i < 5000; i++) {
      TrafficShaper.Decision d = s.next(payload);
      if (d.targetLen() == 0) continue;
      assertTrue(d.targetLen() >= payload, "targetLen < payload");
      assertTrue(d.targetLen() <= maxLen, "targetLen " + d.targetLen() + " > cap " + maxLen);
    }
  }

  @Test
  void targetNeverShrinksPayload() {
    TrafficShaper s = TrafficShaper.create("game", 0, 0, 3);
    for (int i = 0; i < 3000; i++) {
      int pl = 100 + i % 1300;
      TrafficShaper.Decision d = s.next(pl);
      if (d.targetLen() != 0) {
        assertTrue(d.targetLen() >= pl, "targetLen " + d.targetLen() + " < payload " + pl);
      }
    }
  }

  @Test
  void allProfilesSane() {
    for (String name : new String[] {"web", "video", "game", "bulk"}) {
      TrafficShaper s = TrafficShaper.create(name, 0, 0, 42);
      assertTrue(s.enabled(), "профиль " + name + " должен быть enabled");
      for (int i = 0; i < 2000; i++) {
        TrafficShaper.Decision d = s.next(300);
        assertTrue(d.targetLen() >= 0, name + ": отрицательный targetLen");
        assertTrue(d.delayMs() >= 0, name + ": отрицательная задержка");
      }
    }
  }

  @Test
  void profilesDifferStatistically() {
    assertTrue(avgDelay("bulk") < avgDelay("web"), "ожидалось bulk < web по средней задержке");
  }

  private static double avgDelay(String name) {
    TrafficShaper s = TrafficShaper.create(name, 0, 0, 100);
    long total = 0;
    int n = 20000;
    for (int i = 0; i < n; i++) {
      total += s.next(400).delayMs();
    }
    return (double) total / n;
  }
}
