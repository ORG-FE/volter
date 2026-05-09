package dev.c0redev.volter;

final class ServerRelayService {
  private final RelayRegistry registry;
  private final Config cfg;

  ServerRelayService(Config cfg) {
    this.cfg = cfg;
    this.registry = new RelayRegistry(cfg.relayMaxPerRemote(), cfg.relayMaxPerRemote(), cfg.relayMaxTotal(), Integer.MAX_VALUE, cfg.relayMaxBudgetKbps());
  }

  RelayRegistry.RelayLease acquire(String remote, Protocol.ClientOptions opt) {
    String peerId = opt == null ? "" : opt.peerId();
    int requested = opt == null ? 0 : Math.max(0, opt.relayBudgetKbps());
    int budget = requested > 0 ? requested : Math.max(1, cfg.relayMaxBudgetKbps());
    if (budget > cfg.relayMaxBudgetKbps()) {
      return null;
    }
    return registry.tryAcquire(new RelayRegistry.RelayKey(remoteIp(remote), peerId), budget, System.currentTimeMillis(), 15 * 60_000L);
  }

  void release(RelayRegistry.RelayLease lease) {
    registry.release(lease);
  }

  private static String remoteIp(String remote) {
    if (remote == null || remote.isBlank()) return "?";
    String s = remote.trim();
    int slash = s.lastIndexOf('/');
    if (slash >= 0) s = s.substring(slash + 1);
    if (s.startsWith("[") && s.contains("]")) return s.substring(1, s.indexOf(']'));
    int colon = s.lastIndexOf(':');
    if (colon > 0 && s.indexOf(':') == colon) return s.substring(0, colon);
    return s;
  }
}
