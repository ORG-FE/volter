package dev.c0redev.volter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class PeerRelayForward {

  private PeerRelayForward() {}

  static boolean hasNextHop(Protocol.ClientOptions opt) {
    if (opt == null) {
      return false;
    }
    List<String> hops = opt.relayRouteHops();
    if (hops == null || hops.isEmpty()) {
      return false;
    }
    int idx = opt.hopIndex();
    if (idx < 1) {
      idx = 1;
    }
    return idx < hops.size();
  }

  static void forward(
      Config cfg,
      Protocol.TcpConnect target,
      InputStream clientIn,
      OutputStream clientOut,
      Protocol.ClientOptions opt,
      Socket clientSocket)
      throws IOException {
    if (opt == null || !hasNextHop(opt)) {
      throw new IOException("peer relay forward: no next hop");
    }
    int idx = Math.max(1, opt.hopIndex());
    String raw = opt.relayRouteHops().get(idx);
    String[] ka = parseHop(raw);
    if (ka == null) {
      throw new IOException("peer relay forward: bad hop " + raw);
    }
    String kind = ka[0];
    String addr = ka[1];
    if (!"peer_tcp".equalsIgnoreCase(kind)) {
      throw new IOException("peer relay forward: unsupported hop kind " + kind);
    }
    int timeoutMs = Math.max(1_000, cfg.quicTcpConnectTimeoutMs());
    int readTimeoutMs = RelayCopy.relayReadTimeoutMs(timeoutMs);
    Socket upstream = new Socket();
    try {
      upstream.connect(ClusterTcpExitBridge.parseHostPort(addr), timeoutMs);
      upstream.setTcpNoDelay(true);
      RelayCopy.applyReadTimeout(clientSocket, readTimeoutMs);
    } catch (IOException e) {
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
      throw e;
    }
    XorStream xor = new XorStream(XorStream.keyFromToken(cfg.token()));
    OutputStream upXorOut = xor.wrapOutput(upstream.getOutputStream());
    InputStream upXorIn = xor.wrapInput(upstream.getInputStream());
    byte[] fwdOpts = forwardOptsJson(opt, cfg.token());
    try {
      Protocol.writeVolterClientHandshake(upXorOut, Protocol.ROLE_RELAY_TCP, cfg.token(), fwdOpts);
      Protocol.writeTcpConnectFrame(upXorOut, target);
    } catch (IOException e) {
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
      throw e;
    }
    Log.logger(PeerRelayForward.class).info("peer relay chain -> " + addr + " dst=" + target.ip().getHostAddress() + ":" + target.port());
    var up = RelayCopyPool.submit(
        () -> RelayCopy.pump(clientIn, upXorOut, clientSocket, upstream, true, readTimeoutMs),
        "peer-fwd-up");
    var down = RelayCopyPool.submit(
        () -> RelayCopy.pump(upXorIn, clientOut, upstream, clientSocket, true, readTimeoutMs),
        "peer-fwd-down");
    try {
      up.get();
      down.get();
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      up.cancel(true);
      down.cancel(true);
      throw new IOException(e);
    } finally {
      try {
        upstream.close();
      } catch (IOException ignored) {
      }
      if (clientSocket != null) {
        try {
          clientSocket.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  private static byte[] forwardOptsJson(Protocol.ClientOptions opt, String token) {
    int hop = opt.hopIndex() + 1;
    int relayHop = opt.relayHop() + 1;
    StringBuilder sb = new StringBuilder(256);
    sb.append('{');
    appendInt(sb, "relayHop", relayHop, true);
    appendInt(sb, "relayMaxHop", opt.relayMaxHop(), false);
    appendInt(sb, "hopIndex", hop, false);
    appendStr(sb, "peerId", opt.peerId(), false);
    appendStr(sb, "relayNonce", opt.relayNonce(), false);
    appendStr(sb, "relaySig", opt.relaySig(), false);
    appendStr(sb, "routeId", opt.routeId(), false);
    if (opt.relayRouteHops() != null && !opt.relayRouteHops().isEmpty()) {
      sb.append(",\"relayRouteHops\":[");
      for (int i = 0; i < opt.relayRouteHops().size(); i++) {
        if (i > 0) sb.append(',');
        sb.append('"').append(json(opt.relayRouteHops().get(i))).append('"');
      }
      sb.append(']');
    }
    sb.append('}');
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void appendInt(StringBuilder sb, String k, int v, boolean first) {
    if (!first) sb.append(',');
    sb.append('"').append(k).append("\":").append(v);
  }

  private static void appendStr(StringBuilder sb, String k, String v, boolean first) {
    if (!first) sb.append(',');
    sb.append('"').append(k).append("\":\"").append(json(v == null ? "" : v)).append('"');
  }

  private static String json(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String[] parseHop(String raw) {
    if (raw == null) return null;
    int i = raw.indexOf(':');
    if (i <= 0 || i >= raw.length() - 1) return null;
    return new String[] {raw.substring(0, i), raw.substring(i + 1)};
  }
}
