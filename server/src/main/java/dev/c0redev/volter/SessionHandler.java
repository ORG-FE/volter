package dev.c0redev.volter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

final class SessionHandler {
  interface TcpHandler {
    void onTcp(Protocol.TcpConnect connect, InputStream in, Optional<Protocol.ClientOptions> clientOpts)
        throws IOException;
  }

  private static final Logger log = Log.logger(SessionHandler.class);

  private final Config cfg;
  private final UdpSessions udp;
  private final String remote;
  private final Runnable onDone;
  private static final int RELAY_HOP_HARD_LIMIT = 4;
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
    String routeId = hr.opts().map(Protocol.ClientOptions::routeId).orElse("");
    int hopIndex = hr.opts().map(Protocol.ClientOptions::hopIndex).orElse(0);
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
    String pid = hr.opts().map(Protocol.ClientOptions::peerId).orElse("");
    ClusterClientRegistry.get().touch(cfg.clusterNodeId(), remote, pid, hs.role());
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
      tcpHandler.onTcp(c, in, hr.opts());
      return;
    }
    if (hs.role() == Protocol.ROLE_RELAY_TCP) {
      if (!cfg.peerRelayEnabled()) {
        sendHopAck(out, routeId, hopIndex, 0, "peer relay disabled by policy");
        throw new IOException("peer relay disabled by policy");
      }
      RelayRegistry registry = relayRegistry(cfg);
      if (!registry.tryAcquire(remote)) {
        sendHopAck(out, routeId, hopIndex, 0, "relay capacity exceeded");
        throw new IOException("relay capacity exceeded");
      }
      int relayHop = hr.opts().map(Protocol.ClientOptions::relayHop).orElse(0);
      int relayMaxHop = hr.opts().map(Protocol.ClientOptions::relayMaxHop).orElse(RELAY_HOP_HARD_LIMIT);
      int capHop = relayMaxHop;
      if (capHop <= 0 || capHop > RELAY_HOP_HARD_LIMIT) {
        capHop = RELAY_HOP_HARD_LIMIT;
      }
      if (relayHop > capHop) {
        registry.release(remote);
        sendHopAck(out, routeId, hopIndex, 0, "relay hop limit exceeded");
        throw new IOException("relay hop limit exceeded");
      }
      Protocol.ClientOptions opt = hr.opts().orElse(null);
      if (opt != null && opt.relayBudgetKbps() > cfg.relayMaxBudgetKbps()) {
        registry.release(remote);
        sendHopAck(out, routeId, hopIndex, 0, "relay budget too high");
        throw new IOException("relay budget too high");
      }
      if (!PEER_GUARD.allow(opt, cfg.token())) {
        registry.release(remote);
        sendHopAck(out, routeId, hopIndex, 0, "relay identity rejected");
        throw new IOException("relay identity rejected");
      }
      Protocol.TcpConnect c = Protocol.readTcpConnect(in);
      sendHopAck(out, routeId, hopIndex, 1, "");
      try {
        if (PeerRelayForward.hasNextHop(opt)) {
          PeerRelayForward.forward(cfg, c, in, out, opt);
        } else {
          tcpHandler.onTcp(c, in, hr.opts());
        }
      } finally {
        registry.release(remote);
      }
      return;
    }
    throw new IOException("bad role");
  }

  private void sendHopAck(OutputStream out, String routeId, int hopIndex, int status, String reason) {
    try {
      Protocol.writeHopAck(out, new Protocol.HopAck(
          routeId == null ? "" : routeId,
          Math.max(0, Math.min(hopIndex, 255)),
          cfg.clusterNodeId() == null ? "" : cfg.clusterNodeId(),
          status,
          reason == null ? "" : reason,
          System.currentTimeMillis()
      ));
    } catch (IOException ignored) {}
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
