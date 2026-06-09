package dev.c0redev.volter;

import java.util.Map;

final class TrafficShaper {

  enum St {
    IDLE,
    BURST,
    SUSTAINED,
    TAIL
  }

  private static final int N = 4;
  private static final int MAX_FRAME_TARGET = 1400;
  private static final double EWMA_ALPHA = 0.2;
  private static final int DEFAULT_MAX_OVERHEAD_PCT = 100;
  private static final int DEFAULT_MAX_DELAY_MS = 200;

  enum DistKind {
    UNIFORM,
    EXP,
    NORMAL
  }

  private record Dist(DistKind kind, double min, double max, double mean, double std) {}

  private record Params(Dist frameLen, Dist delayMs) {}

  private record Profile(String name, double[][] trans, Params[] params, St initial) {}

  private static final Map<String, Profile> BUILTINS =
      Map.of(
          "web",
              new Profile(
                  "web",
                  new double[][] {
                    {0.55, 0.35, 0.08, 0.02},
                    {0.05, 0.45, 0.30, 0.20},
                    {0.10, 0.20, 0.50, 0.20},
                    {0.55, 0.10, 0.15, 0.20},
                  },
                  new Params[] {
                    new Params(
                        new Dist(DistKind.EXP, 40, 300, 80, 0),
                        new Dist(DistKind.EXP, 5, 800, 120, 0)),
                    new Params(
                        new Dist(DistKind.NORMAL, 800, 1400, 1300, 200),
                        new Dist(DistKind.EXP, 0, 20, 2, 0)),
                    new Params(
                        new Dist(DistKind.NORMAL, 400, 1400, 1000, 300),
                        new Dist(DistKind.EXP, 1, 60, 10, 0)),
                    new Params(
                        new Dist(DistKind.EXP, 100, 900, 300, 0),
                        new Dist(DistKind.EXP, 2, 150, 25, 0)),
                  },
                  St.IDLE),
          "video",
              new Profile(
                  "video",
                  new double[][] {
                    {0.30, 0.20, 0.45, 0.05},
                    {0.02, 0.40, 0.53, 0.05},
                    {0.05, 0.25, 0.65, 0.05},
                    {0.40, 0.15, 0.40, 0.05},
                  },
                  new Params[] {
                    new Params(
                        new Dist(DistKind.EXP, 60, 400, 120, 0),
                        new Dist(DistKind.EXP, 5, 500, 80, 0)),
                    new Params(
                        new Dist(DistKind.NORMAL, 1000, 1400, 1350, 120),
                        new Dist(DistKind.UNIFORM, 0, 8, 0, 0)),
                    new Params(
                        new Dist(DistKind.NORMAL, 900, 1400, 1250, 200),
                        new Dist(DistKind.NORMAL, 8, 60, 33, 10)),
                    new Params(
                        new Dist(DistKind.EXP, 200, 1000, 400, 0),
                        new Dist(DistKind.EXP, 5, 120, 30, 0)),
                  },
                  St.SUSTAINED),
          "game",
              new Profile(
                  "game",
                  new double[][] {
                    {0.40, 0.15, 0.43, 0.02},
                    {0.05, 0.35, 0.55, 0.05},
                    {0.08, 0.17, 0.70, 0.05},
                    {0.45, 0.10, 0.40, 0.05},
                  },
                  new Params[] {
                    new Params(
                        new Dist(DistKind.EXP, 30, 200, 60, 0),
                        new Dist(DistKind.EXP, 5, 300, 50, 0)),
                    new Params(
                        new Dist(DistKind.NORMAL, 150, 600, 350, 100),
                        new Dist(DistKind.EXP, 0, 15, 3, 0)),
                    new Params(
                        new Dist(DistKind.NORMAL, 60, 400, 180, 80),
                        new Dist(DistKind.NORMAL, 8, 50, 22, 8)),
                    new Params(
                        new Dist(DistKind.EXP, 40, 250, 90, 0),
                        new Dist(DistKind.EXP, 3, 80, 18, 0)),
                  },
                  St.SUSTAINED),
          "bulk",
              new Profile(
                  "bulk",
                  new double[][] {
                    {0.20, 0.60, 0.18, 0.02},
                    {0.01, 0.80, 0.17, 0.02},
                    {0.03, 0.55, 0.40, 0.02},
                    {0.30, 0.45, 0.20, 0.05},
                  },
                  new Params[] {
                    new Params(
                        new Dist(DistKind.EXP, 100, 800, 300, 0),
                        new Dist(DistKind.EXP, 2, 200, 30, 0)),
                    new Params(
                        new Dist(DistKind.NORMAL, 1200, 1400, 1400, 80),
                        new Dist(DistKind.UNIFORM, 0, 3, 0, 0)),
                    new Params(
                        new Dist(DistKind.NORMAL, 800, 1400, 1200, 200),
                        new Dist(DistKind.EXP, 0, 25, 4, 0)),
                    new Params(
                        new Dist(DistKind.EXP, 200, 1000, 500, 0),
                        new Dist(DistKind.EXP, 1, 60, 12, 0)),
                  },
                  St.BURST));

  record Decision(int targetLen, long delayMs) {}

  private static final Decision NOOP = new Decision(0, 0);

  private final boolean enabled;
  private final Profile prof;
  private final int maxOverheadPct;
  private final int maxDelayMs;
  private final Prng rng;
  private St state;
  private double obsLen;
  private boolean lastSeen;

  private TrafficShaper(Profile prof, int maxOverheadPct, int maxDelayMs, long seed) {
    this.enabled = prof != null;
    this.prof = prof;
    this.maxOverheadPct = maxOverheadPct;
    this.maxDelayMs = maxDelayMs;
    this.rng = new Prng(seed);
    this.state = prof != null ? prof.initial() : St.IDLE;
  }

  static TrafficShaper create(String profile, int maxOverheadPct, int maxDelayMs, long seed) {
    if (profile == null || profile.isEmpty()) {
      return new TrafficShaper(null, 0, 0, seed);
    }
    Profile p = BUILTINS.get(profile);
    if (p == null) {
      return new TrafficShaper(null, 0, 0, seed);
    }
    int ovhd = maxOverheadPct <= 0 ? DEFAULT_MAX_OVERHEAD_PCT : maxOverheadPct;
    int delay = maxDelayMs <= 0 ? DEFAULT_MAX_DELAY_MS : maxDelayMs;
    long s = seed == 0 ? hashSeed(profile, ovhd, delay) : seed;
    return new TrafficShaper(p, ovhd, delay, s);
  }

  boolean enabled() {
    return enabled;
  }

  Decision next(int payloadLen) {
    if (!enabled) {
      return NOOP;
    }
    observe(payloadLen);
    advance();
    Params pr = prof.params()[state.ordinal()];
    int target = (int) sample(pr.frameLen());
    double delay = sample(pr.delayMs());
    target = adaptTarget(target, payloadLen);
    target = capTarget(target, payloadLen);
    return new Decision(target, (long) capDelay(delay));
  }

  private void advance() {
    double[] row = prof.trans()[state.ordinal()];
    double u = rng.nextDouble();
    double acc = 0;
    for (int next = 0; next < N; next++) {
      acc += row[next];
      if (u <= acc) {
        state = St.values()[next];
        return;
      }
    }
    state = St.values()[N - 1];
  }

  private void observe(int payloadLen) {
    double pl = payloadLen;
    if (!lastSeen) {
      obsLen = pl;
      lastSeen = true;
      return;
    }
    obsLen = EWMA_ALPHA * pl + (1 - EWMA_ALPHA) * obsLen;
  }

  private int adaptTarget(int profileTarget, int payloadLen) {
    if (obsLen <= 0) {
      return profileTarget;
    }
    double mixed = 0.7 * profileTarget + 0.3 * obsLen;
    int t = (int) Math.round(mixed);
    if (t < payloadLen) {
      t = payloadLen;
    }
    return t;
  }

  private int capTarget(int target, int payloadLen) {
    if (target <= payloadLen) {
      return 0;
    }
    int maxAdd = payloadLen * maxOverheadPct / 100;
    if (maxAdd < 0) {
      maxAdd = 0;
    }
    if (target - payloadLen > maxAdd) {
      target = payloadLen + maxAdd;
    }
    if (target > MAX_FRAME_TARGET && payloadLen <= MAX_FRAME_TARGET) {
      target = MAX_FRAME_TARGET;
    }
    if (target <= payloadLen) {
      return 0;
    }
    return target;
  }

  private double capDelay(double ms) {
    if (ms < 0) {
      return 0;
    }
    if (ms > maxDelayMs) {
      return maxDelayMs;
    }
    return ms;
  }

  private double sample(Dist d) {
    if (d.max() <= d.min()) {
      return d.min();
    }
    double v;
    switch (d.kind()) {
      case UNIFORM:
        v = d.min() + rng.nextDouble() * (d.max() - d.min());
        break;
      case EXP:
        {
          double u = rng.nextDouble();
          if (u < 1e-12) u = 1e-12;
          double mean = d.mean() > 0 ? d.mean() : (d.max() - d.min()) / 2;
          v = d.min() + (-Math.log(u)) * mean;
          break;
        }
      case NORMAL:
        {
          double std = d.std() > 0 ? d.std() : (d.max() - d.min()) / 6;
          v = d.mean() + rng.normDouble() * std;
          break;
        }
      default:
        v = d.min();
    }
    if (v < d.min()) v = d.min();
    if (v > d.max()) v = d.max();
    return v;
  }

  private static long hashSeed(String profile, int a, int b) {
    long h = 1469598103934665603L;
    for (int i = 0; i < profile.length(); i++) {
      h ^= profile.charAt(i);
      h *= 1099511628211L;
    }
    h ^= a;
    h *= 1099511628211L;
    h ^= b;
    h *= 1099511628211L;
    return h == 0 ? 1 : h;
  }

  static long testHashSeed(String profile, int a, int b) {
    return hashSeed(profile, a, b);
  }

  static long[] testPrngNext(long seed, int count) {
    Prng p = new Prng(seed);
    long[] out = new long[count];
    for (int i = 0; i < count; i++) {
      out[i] = p.next();
    }
    return out;
  }

  static double[] testPrngFloat(long seed, int count) {
    Prng p = new Prng(seed);
    double[] out = new double[count];
    for (int i = 0; i < count; i++) {
      out[i] = p.nextDouble();
    }
    return out;
  }

  private static final class Prng {
    private long s;

    Prng(long seed) {
      this.s = seed == 0 ? 0x9e3779b97f4a7c15L : seed;
    }

    long next() {
      s += 0x9e3779b97f4a7c15L;
      long z = s;
      z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
      z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
      return z ^ (z >>> 31);
    }

    double nextDouble() {
      return (next() >>> 11) / (double) (1L << 53);
    }

    double normDouble() {
      double u1 = nextDouble();
      if (u1 < 1e-12) u1 = 1e-12;
      double u2 = nextDouble();
      return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }
  }
}
