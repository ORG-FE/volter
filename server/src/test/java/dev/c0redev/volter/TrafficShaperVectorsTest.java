package dev.c0redev.volter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TrafficShaperVectorsTest {

  @Test
  void prngNext() {
    long[] want = {
      0x22118258a9d111a0L,
      0x346edce5f713f8edL,
      0x1e9a57bc80e6721dL,
      0x2d160e7e5c3f42caL,
      0x81c2e6dc980d78ebL,
    };
    long[] got = TrafficShaper.testPrngNext(12345L, want.length);
    for (int i = 0; i < want.length; i++) {
      assertEquals(want[i], got[i], "next()[" + i + "]");
    }
  }

  @Test
  void prngFloat64() {
    double[] want = {
      0.1330796686614273,
      0.20481663336165912,
      0.11954258300911547,
      0.17611780724496118,
      0.506880215507456,
    };
    double[] got = TrafficShaper.testPrngFloat(12345L, want.length);
    for (int i = 0; i < want.length; i++) {
      assertEquals(want[i], got[i], 0.0, "float64()[" + i + "]");
    }
  }

  @Test
  void hashSeed() {
    assertEquals(0x5b37348f357dadffL, TrafficShaper.testHashSeed("web", 100, 200));
  }
}
