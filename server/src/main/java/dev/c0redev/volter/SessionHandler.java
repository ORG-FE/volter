package dev.c0redev.volter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

final class SessionHandler {
  interface TcpHandler {
    void onTcp(Protocol.TcpConnect connect, InputStream in) throws IOException;
  }

  private static final Logger log = Log.logger(SessionHandler.class);

  private final Config cfg;
  private final UdpSessions udp;
  private final String remote;
  private final Runnable onDone;
  private static final int RELAY_HOP_HARD_LIMIT = 2;
  private static final PeerRelayGuard PEER_GUARD = new PeerRelayGuard();
  private static volatile RelayRegistry relayRegistry;

  SessionHandler(Config cfg, UdpSessions udp, String remote, Runnable onDone) {
    this.cfg = cfg;
    this.udp = udp;
    this.remote = remote;
    this.onDone = onDone;
  }

  
  void handle(
      Protocol.Handshake hs,
      Protocol.HandshakeResult hr,
      InputStream in,
      OutputStream out,
      TcpHandler tcpHandler,
      ExecutorService udpOffloadExecutor
  ) throws IOException {
    int requestedObfs = hr.opts().map(Protocol.ClientOptions::probeObfsProfileId).orElse(0);
    int agreedObfs = Protocol.normalizeObfsProfile(requestedObfs);
    if (hr.opts().isPresent()) {
      Protocol.ClientOptions o = hr.opts().get();
      if (!o.sessionId().isBlank() && !o.resumeToken().isBlank()) {
        boolean ok = SessionResumeRegistry.get().accept(o.sessionId(), o.resumeToken(), remote, cfg.clusterNodeId());
        if (!ok) {
          throw new IOException("resume token mismatch");
        }
      }
    }
    log.info("Accepted role=" + hs.role() + " from " + remote + " obfsProfile=" + agreedObfs);
    if (hs.role() == Protocol.ROLE_UDP) {
      Runnable r = () -> {
        try {
          handleUdp(hs.channelId(), in, out, hr.opts());
        } catch (IOException e) {
          log.fine("udp role ended: " + e.getMessage());
        } finally {
          onDone.run();
        }
      };
      if (udpOffloadExecutor != null) {
        udpOffloadExecutor.submit(r);
        return;
      }
      r.run();
      return;
    }
    if (hs.role() == Protocol.ROLE_TCP) {
      Protocol.TcpConnect c = Protocol.readTcpConnect(in);
      tcpHandler.onTcp(c, in);
      return;
    }
    if (hs.role() == Protocol.ROLE_RELAY_TCP) {
      if (!cfg.peerRelayEnabled()) {
        throw new IOException("peer relay disabled by policy");
      }
      RelayRegistry registry = relayRegistry(cfg);
      if (!registry.tryAcquire(remote)) {
        throw new IOException("relay capacity exceeded");
      }
      int relayHop = hr.opts().map(Protocol.ClientOptions::relayHop).orElse(0);
      int relayMaxHop = hr.opts().map(Protocol.ClientOptions::relayMaxHop).orElse(RELAY_HOP_HARD_LIMIT);
      int capHop = Math.min(RELAY_HOP_HARD_LIMIT, relayMaxHop);
      if (relayHop >= capHop) {
        registry.release(remote);
        throw new IOException("relay hop limit exceeded");
      }
      Protocol.ClientOptions opt = hr.opts().orElse(null);
      if (opt != null && opt.relayBudgetKbps() > cfg.relayMaxBudgetKbps()) {
        registry.release(remote);
        throw new IOException("relay budget too high");
      }
      if (!PEER_GUARD.allow(opt, cfg.token())) {
        registry.release(remote);
        throw new IOException("relay identity rejected");
      }
      Protocol.TcpConnect c = Protocol.readTcpConnect(in);
      try {
        tcpHandler.onTcp(c, in);
      } finally {
        registry.release(remote);
      }
      return;
    }
    throw new IOException("bad role");
  }

  private void handleUdp(int channelId, InputStream in, OutputStream out, Optional<Protocol.ClientOptions> opts)
      throws IOException {
    UdpSessions.UdpChannelWriter writer = udp.createWriter(out, opts.orElse(null));
    try {
      if (channelId < 0 || channelId >= cfg.udpChannels()) throw new IOException("bad udp channel");
      log.info("UDP channel " + channelId + " registered from " + remote);
      while (true) {
        Protocol.UdpFrame f = Protocol.readUdpFrame(in);
        udp.onFrame(writer, channelId, f);
      }
    } finally {
      udp.removeWriter(writer);
    }
  }

  private static RelayRegistry relayRegistry(Config cfg) {
    RelayRegistry r = relayRegistry;
    if (r != null) return r;
    synchronized (SessionHandler.class) {
      if (relayRegistry == null) {
        relayRegistry = new RelayRegistry(cfg.relayMaxPerRemote(), cfg.relayMaxTotal());
      }
      return relayRegistry;
    }
  }
}
