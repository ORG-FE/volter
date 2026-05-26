package dev.c0redev.volter;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

final class Protocol {
  static final byte[] MAGIC = new byte[] {'V', 'O', 'L', 'T', 1};
  static final byte VERSION = 1;
  static final byte ROLE_UDP = 1;
  static final byte ROLE_TCP = 2;
  static final byte ROLE_RELAY_TCP = 3;
  static final byte MSG_UDP = 1;
  static final byte ADDR_V4 = 4;
  static final byte ADDR_V6 = 6;
  static final int MAGIC_LEN = 5;
  static final int MAX_TOKEN = 4096;
  static final int MAX_FRAME = 64 * 1024 + 64;
  static final int MAX_PAD = 32;
  static final int MAX_OPTS = 2048;
  static final int MAX_HELLO_CAPS_NONCE = 32;
  static final int CAPS_VERSION = 1;
  static final int TRANSPORT_TCP = 1;
  static final int TRANSPORT_QUIC = 1 << 1;
  static final int FEAT_IPV6 = 1;
  static final int FEAT_POLY_HANDSHAKE = 1 << 1;
  static final int FEAT_RELAY_SERVER = 1 << 2;
  static final int FEAT_RELAY_PEER = 1 << 3;
  static final int FEAT_ROUTE_HOP_ACK = 1 << 4;
  static final int HOP_ACK_MAGIC = 0xA7;
  static final int HOP_ACK_V1 = 1;
  static final int OBFS_PROFILE_MIN = 1;
  static final int OBFS_PROFILE_MAX = 4;

  static record HandshakeResult(Handshake handshake, Optional<ClientOptions> opts) {}

  static int pickObfsProfileId() {
    return OBFS_PROFILE_MIN + RND.nextInt(OBFS_PROFILE_MAX - OBFS_PROFILE_MIN + 1);
  }

  static int normalizeObfsProfile(int profileId) {
    if (profileId < OBFS_PROFILE_MIN || profileId > OBFS_PROFILE_MAX) return 0;
    return profileId;
  }

  static final int MAX_MAGIC_SCAN_BYTES = 512 * 1024;

  static HandshakeResult readHandshake(InputStream in) throws IOException {
    skipUntilMagic(in);
    Handshake hs = readHandshakeBody(in);
    Optional<ClientOptions> opts = readClientOptions(in);
    return new HandshakeResult(hs, opts);
  }

static Optional<ClientOptions> readClientOptions(InputStream in) throws IOException {
        int optsLen = readU16(in);
        if (optsLen == 0) return Optional.empty();
        if (optsLen > MAX_OPTS) throw new IOException("bad opts len");
        byte[] buf = readN(in, optsLen);
        String raw = new String(buf, StandardCharsets.UTF_8);
        var log = Log.logger(Protocol.class);
        boolean hasClusterPreferred = raw.contains("clusterPreferredServer");
        boolean directRoute = raw.contains("\"routeMode\":\"direct\"");
        if (hasClusterPreferred) {
            log.info("RAW opts CONTAINS clusterPreferredServer");
            int idx = raw.indexOf("clusterPreferredServer");
            log.info("RAW opts snippet around cPS: " + raw.substring(Math.max(0, idx - 30), Math.min(raw.length(), idx + 60)));
        } else if (!directRoute) {
            log.warning("RAW opts MISSING clusterPreferredServer! Full length=" + optsLen);
            log.info("RAW opts last 200 chars: " + raw.substring(Math.max(0, raw.length() - 200)));
        }
        Optional<ClientOptions> parsed = ClientOptions.parse(raw);
        if (parsed.isEmpty()) {
            log.warning("RAW opts parse FAILED: " + raw.substring(0, Math.min(raw.length(), 200)));
            throw new IOException("bad client options json");
        }
        return parsed;
    }

  static void skipUntilMagic(InputStream in) throws IOException {
    byte[] buf = new byte[5];
    int n = 0;
    while (true) {
      if (n >= MAX_MAGIC_SCAN_BYTES) {
        throw new IOException("volter magic not found within " + MAX_MAGIC_SCAN_BYTES + " bytes");
      }
      int b = in.read();
      if (b == -1) throw new EOFException();
      System.arraycopy(buf, 1, buf, 0, 4);
      buf[4] = (byte) b;
      n++;
      if (n >= 5
          && buf[0] == MAGIC[0]
          && buf[1] == MAGIC[1]
          && buf[2] == MAGIC[2]
          && buf[3] == MAGIC[3]
          && buf[4] == MAGIC[4]) {
        return;
      }
    }
  }

  static Handshake readHandshakeBody(InputStream in) throws IOException {
    int ver = readU8(in);
    if (ver != VERSION) throw new IOException("bad version");
    byte role = (byte) readU8(in);
    int tokenLen = readU16(in);
    if (tokenLen < 0 || tokenLen > MAX_TOKEN) throw new IOException("bad token len");
    String token = new String(readN(in, tokenLen), StandardCharsets.UTF_8);
    int channelId = -1;
    if (role == ROLE_UDP) channelId = readU8(in);
    return new Handshake(role, channelId, token);
  }

  static TcpConnect readTcpConnect(InputStream in) throws IOException {
    return readTcpConnectBody(in);
  }

  static void writeHopAck(OutputStream out, HopAck ack) throws IOException {
    out.write(HOP_ACK_MAGIC);
    out.write(HOP_ACK_V1);
    out.write(ack.status() & 0xff);
    out.write(ack.hopIdx() & 0xff);
    writeStr16(out, ack.routeId());
    writeStr16(out, ack.nodeId());
    writeStr16(out, ack.reason());
    long ts = ack.tsMs();
    out.write((int) ((ts >>> 56) & 0xff));
    out.write((int) ((ts >>> 48) & 0xff));
    out.write((int) ((ts >>> 40) & 0xff));
    out.write((int) ((ts >>> 32) & 0xff));
    out.write((int) ((ts >>> 24) & 0xff));
    out.write((int) ((ts >>> 16) & 0xff));
    out.write((int) ((ts >>> 8) & 0xff));
    out.write((int) (ts & 0xff));
    out.flush();
  }

  static TcpConnect readTcpConnectBody(InputStream in) throws IOException {
    int at = readU8(in);
    InetAddress ip = readAddr(in, (byte) at);
    int port = readU16(in);
    return new TcpConnect((byte) at, ip, port);
  }

  static UdpFrame readUdpFrame(InputStream in) throws IOException {
    int flen = readU32(in);
    if (flen < 2 || flen > MAX_FRAME) throw new IOException("bad frame len");
    byte[] buf = readN(in, flen);
    if (buf[0] != MSG_UDP) throw new IOException("bad msg");
    byte at = buf[1];
    int off = 2;
    if (buf.length < off + 2) throw new IOException("short frame");
    int srcPort = ((buf[off] & 0xff) << 8) | (buf[off + 1] & 0xff);
    off += 2;
    int ipLen = at == ADDR_V6 ? 16 : 4;
    if (at != ADDR_V4 && at != ADDR_V6) throw new IOException("bad addr type");
    if (buf.length < off + ipLen + 2) throw new IOException("short frame");
    byte[] ipb = new byte[ipLen];
    System.arraycopy(buf, off, ipb, 0, ipLen);
    off += ipLen;
    InetAddress dst = InetAddress.getByAddress(ipb);
    int dstPort = ((buf[off] & 0xff) << 8) | (buf[off + 1] & 0xff);
    off += 2;
    int padLen = buf[buf.length - 1] & 0xff;
    if (padLen > 64 || buf.length - 1 - padLen < off) throw new IOException("bad pad len");
    int payEnd = buf.length - 1 - padLen;
    byte[] payload = new byte[payEnd - off];
    System.arraycopy(buf, off, payload, 0, payload.length);
    return new UdpFrame(at, srcPort, dst, dstPort, payload);
  }

  private static final SecureRandom RND = new SecureRandom();

  static void writeUdpFrame(OutputStream out, UdpFrame f) throws IOException {
    writeUdpFrame(out, f, MAX_PAD);
  }

  static void writeUdpFrame(OutputStream out, UdpFrame f, int maxPad) throws IOException {
    if (maxPad <= 0 || maxPad > 64) maxPad = MAX_PAD;
    int ipLen = f.addrType() == ADDR_V6 ? 16 : 4;
    int padLen = RND.nextInt(maxPad + 1);
    int payLen = f.payload().length;
    int flen = 1 + 1 + 2 + ipLen + 2 + payLen + padLen + 1;
    writeU32(out, flen);
    out.write(MSG_UDP);
    out.write(f.addrType());
    writeU16(out, f.srcPort());
    out.write(f.dst().getAddress());
    writeU16(out, f.dstPort());
    out.write(f.payload());
    byte[] pad = new byte[padLen];
    RND.nextBytes(pad);
    out.write(pad);
    out.write(padLen & 0xff);
    out.flush();
  }

  static InetAddress readAddr(InputStream in, byte addrType) throws IOException {
    return switch (addrType) {
      case ADDR_V4 -> InetAddress.getByAddress(readN(in, 4));
      case ADDR_V6 -> InetAddress.getByAddress(readN(in, 16));
      default -> throw new IOException("bad addr type");
    };
  }

  static byte[] readN(InputStream in, int n) throws IOException {
    byte[] b = new byte[n];
    int off = 0;
    while (off < n) {
      int r = in.read(b, off, n - off);
      if (r == -1) throw new EOFException();
      off += r;
    }
    return b;
  }

  static int readU8(InputStream in) throws IOException {
    int v = in.read();
    if (v == -1) throw new EOFException();
    return v;
  }

  static int readU16(InputStream in) throws IOException {
    int hi = readU8(in);
    int lo = readU8(in);
    return (hi << 8) | lo;
  }

  static int readU32(InputStream in) throws IOException {
    int b1 = readU8(in);
    int b2 = readU8(in);
    int b3 = readU8(in);
    int b4 = readU8(in);
    return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
  }

  static void writeU16(OutputStream out, int v) throws IOException {
    out.write((v >>> 8) & 0xff);
    out.write(v & 0xff);
  }

  static void writeStr16(OutputStream out, String s) throws IOException {
    if (s == null) s = "";
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    if (b.length > 65535) {
      byte[] cut = new byte[65535];
      System.arraycopy(b, 0, cut, 0, cut.length);
      b = cut;
    }
    writeU16(out, b.length);
    if (b.length > 0) out.write(b);
  }

  static void writeU32(OutputStream out, int v) throws IOException {
    out.write((v >>> 24) & 0xff);
    out.write((v >>> 16) & 0xff);
    out.write((v >>> 8) & 0xff);
    out.write(v & 0xff);
  }

  static final int HELLO_CAPS_EXT_QUIC_LEAF_PIN = 1;
  static final int HELLO_CAPS_EXT_RELAY_CLASS = 2;
  static final int HELLO_CAPS_EXT_PATH_TTL = 3;
  static final int HELLO_CAPS_EXT_RELAY_FLAGS = 4;

  static void writeServerHelloCaps(OutputStream out, ServerHelloCaps caps) throws IOException {
    out.write(caps.version() & 0xff);
    out.write(caps.legacyIpv6() & 0xff);
    out.write(caps.transportMask() & 0xff);
    writeU16(out, caps.featureBits());
    writeU16(out, caps.quicPort());
    writeU16(out, caps.tcpPortHint());
    out.write(caps.obfsProfileId() & 0xff);
    int nonceLen = caps.nonce() == null ? 0 : caps.nonce().length;
    if (nonceLen > MAX_HELLO_CAPS_NONCE) throw new IOException("caps nonce too long");
    out.write(nonceLen & 0xff);
    if (nonceLen > 0) out.write(caps.nonce());
    byte[] qp = caps.quicLeafPinSha256();
    if (qp != null && qp.length == 32) {
      writeCapsTlv(out, HELLO_CAPS_EXT_QUIC_LEAF_PIN, qp);
    }
    if (caps.relayClass() > 0) {
      writeCapsTlv(out, HELLO_CAPS_EXT_RELAY_CLASS, new byte[] {(byte) (caps.relayClass() & 0xff)});
    }
    if (caps.pathTtl() > 0) {
      writeCapsTlv(out, HELLO_CAPS_EXT_PATH_TTL, new byte[] {(byte) (caps.pathTtl() & 0xff)});
    }
    if (caps.relayFlags() != 0) {
      writeCapsTlv(out, HELLO_CAPS_EXT_RELAY_FLAGS, new byte[] {
          (byte) ((caps.relayFlags() >>> 8) & 0xff),
          (byte) (caps.relayFlags() & 0xff)
      });
    }
    out.flush();
  }

  static ServerHelloCaps readServerHelloCaps(InputStream in) throws IOException {
    int version = readU8(in);
    int legacyIpv6 = readU8(in);
    int transportMask = readU8(in);
    int featureBits = readU16(in);
    int quicPort = readU16(in);
    int tcpPortHint = readU16(in);
    int obfsProfileId = readU8(in);
    int nonceLen = readU8(in);
    if (nonceLen > MAX_HELLO_CAPS_NONCE) throw new IOException("caps nonce too long");
    byte[] nonce = nonceLen == 0 ? new byte[0] : readN(in, nonceLen);
    byte[] qpin = null;
    int relayClass = 0;
    int pathTtl = 0;
    int relayFlags = 0;
    while (true) {
      int ext = in.read();
      if (ext < 0) {
        break;
      }
      int ln = readU8(in);
      byte[] val = readN(in, ln);
      if (ext == HELLO_CAPS_EXT_QUIC_LEAF_PIN) {
        if (ln != 32) throw new IOException("bad quic pin len");
        qpin = val;
      } else if (ext == HELLO_CAPS_EXT_RELAY_CLASS) {
        if (ln != 1) throw new IOException("bad relay class len");
        relayClass = val[0] & 0xff;
      } else if (ext == HELLO_CAPS_EXT_PATH_TTL) {
        if (ln != 1) throw new IOException("bad path ttl len");
        pathTtl = val[0] & 0xff;
      } else if (ext == HELLO_CAPS_EXT_RELAY_FLAGS) {
        if (ln != 2) throw new IOException("bad relay flags len");
        relayFlags = ((val[0] & 0xff) << 8) | (val[1] & 0xff);
      } else {
        throw new IOException("bad caps extension tag: " + ext);
      }
    }
    return new ServerHelloCaps(
        version,
        legacyIpv6,
        transportMask,
        featureBits,
        quicPort,
        tcpPortHint,
        obfsProfileId,
        nonce,
        qpin,
        relayClass,
        pathTtl,
        relayFlags);
  }

  static void writeCapsTlv(OutputStream out, int tag, byte[] val) throws IOException {
    int ln = val == null ? 0 : Math.min(255, val.length);
    out.write(tag & 0xff);
    out.write(ln & 0xff);
    if (ln > 0) out.write(val, 0, ln);
  }

  static void writeVolterClientHandshake(OutputStream xorOut, byte role, String token, byte[] optsUtf8)
      throws IOException {
    byte[] junk = new byte[384 + RND.nextInt(1024)];
    RND.nextBytes(junk);
    xorOut.write(junk);
    xorOut.write(MAGIC);
    xorOut.write(VERSION);
    xorOut.write(role & 0xff);
    byte[] tok = token.getBytes(StandardCharsets.UTF_8);
    if (tok.length > MAX_TOKEN) {
      throw new IOException("token too long");
    }
    writeU16(xorOut, tok.length);
    xorOut.write(tok);
    if (role == ROLE_UDP) {
      xorOut.write(0);
    }
    int ol = optsUtf8 == null ? 0 : optsUtf8.length;
    if (ol > MAX_OPTS) {
      throw new IOException("opts too long");
    }
    writeU16(xorOut, ol);
    if (ol > 0) {
      xorOut.write(optsUtf8);
    }
    xorOut.flush();
  }

  static void writeTcpConnectFrame(OutputStream xorOut, TcpConnect c) throws IOException {
    xorOut.write(c.addrType() & 0xff);
    xorOut.write(c.ip().getAddress());
    writeU16(xorOut, c.port());
    xorOut.flush();
  }

  record Handshake(byte role, int channelId, String token) {}
  record TcpConnect(byte addrType, InetAddress ip, int port) {}
  record UdpFrame(byte addrType, int srcPort, InetAddress dst, int dstPort, byte[] payload) {}
  record ServerHelloCaps(
      int version,
      int legacyIpv6,
      int transportMask,
      int featureBits,
      int quicPort,
      int tcpPortHint,
      int obfsProfileId,
      byte[] nonce,
      byte[] quicLeafPinSha256,
      int relayClass,
      int pathTtl,
      int relayFlags) {}
  record HopAck(String routeId, int hopIdx, String nodeId, int status, String reason, long tsMs) {}

  record ClientOptions(
      int padS4,
      int probeObfsProfileId,
      int relayHop,
      int relayMaxHop,
      int relayBudgetKbps,
      String peerId,
      String relayNonce,
      String relaySig,
      String sessionId,
      String resumeToken,
      String routeId,
      int hopIndex,
      List<String> relayRouteHops,
      String clusterPreferredServer,
      String tlsProfileId,
      String ja3TargetHash) {
    static Optional<ClientOptions> parse(String json) {
      try {
        int padS4 = 32;
        int probeObfsProfileId = 0;
        int relayHop = 0;
        int relayMaxHop = 2;
        int relayBudgetKbps = 0;
        String peerId = "";
        String relayNonce = "";
        String relaySig = "";
        String sessionId = "";
        String resumeToken = "";
        String routeId = "";
        int hopIndex = 0;
        String clusterPreferredServer = "";
        if (json.contains("\"padS4\"")) {
          int i = json.indexOf("\"padS4\"");
          int start = json.indexOf(":", i) + 1;
          int end = json.indexOf(",", start);
          if (end < 0) end = json.indexOf("}", start);
          if (end < 0) end = json.length();
          padS4 = Integer.parseInt(json.substring(start, end).trim());
          if (padS4 < 0 || padS4 > 64) padS4 = 32;
        }
        if (json.contains("\"probeObfsProfileID\"")) {
          int i = json.indexOf("\"probeObfsProfileID\"");
          int start = json.indexOf(":", i) + 1;
          int end = json.indexOf(",", start);
          if (end < 0) end = json.indexOf("}", start);
          if (end < 0) end = json.length();
          probeObfsProfileId = Integer.parseInt(json.substring(start, end).trim());
          probeObfsProfileId = normalizeObfsProfile(probeObfsProfileId);
        }
        if (json.contains("\"relayHop\"")) {
          int i = json.indexOf("\"relayHop\"");
          int start = json.indexOf(":", i) + 1;
          int end = json.indexOf(",", start);
          if (end < 0) end = json.indexOf("}", start);
          if (end < 0) end = json.length();
          relayHop = Integer.parseInt(json.substring(start, end).trim());
          if (relayHop < 0) relayHop = 0;
          if (relayHop > 8) relayHop = 8;
        }
        if (json.contains("\"relayMaxHop\"")) {
          int i = json.indexOf("\"relayMaxHop\"");
          int start = json.indexOf(":", i) + 1;
          int end = json.indexOf(",", start);
          if (end < 0) end = json.indexOf("}", start);
          if (end < 0) end = json.length();
          relayMaxHop = Integer.parseInt(json.substring(start, end).trim());
          if (relayMaxHop < 1) relayMaxHop = 1;
          if (relayMaxHop > 8) relayMaxHop = 8;
        }
        if (json.contains("\"relayBudgetKbps\"")) {
          int i = json.indexOf("\"relayBudgetKbps\"");
          int start = json.indexOf(":", i) + 1;
          int end = json.indexOf(",", start);
          if (end < 0) end = json.indexOf("}", start);
          if (end < 0) end = json.length();
          relayBudgetKbps = Integer.parseInt(json.substring(start, end).trim());
          if (relayBudgetKbps < 0) relayBudgetKbps = 0;
        }
        if (json.contains("\"peerId\"")) {
          int i = json.indexOf("\"peerId\"");
          int start = json.indexOf(":", i) + 1;
          int q1 = json.indexOf("\"", start);
          int q2 = q1 >= 0 ? json.indexOf("\"", q1 + 1) : -1;
          if (q1 >= 0 && q2 > q1) peerId = json.substring(q1 + 1, q2).trim();
        }
        if (json.contains("\"relayNonce\"")) {
          int i = json.indexOf("\"relayNonce\"");
          int start = json.indexOf(":", i) + 1;
          int q1 = json.indexOf("\"", start);
          int q2 = q1 >= 0 ? json.indexOf("\"", q1 + 1) : -1;
          if (q1 >= 0 && q2 > q1) relayNonce = json.substring(q1 + 1, q2).trim();
        }
        if (json.contains("\"relaySig\"")) {
          int i = json.indexOf("\"relaySig\"");
          int start = json.indexOf(":", i) + 1;
          int q1 = json.indexOf("\"", start);
          int q2 = q1 >= 0 ? json.indexOf("\"", q1 + 1) : -1;
          if (q1 >= 0 && q2 > q1) relaySig = json.substring(q1 + 1, q2).trim();
        }
        if (json.contains("\"sessionId\"")) {
          int i = json.indexOf("\"sessionId\"");
          int start = json.indexOf(":", i) + 1;
          int q1 = json.indexOf("\"", start);
          int q2 = q1 >= 0 ? json.indexOf("\"", q1 + 1) : -1;
          if (q1 >= 0 && q2 > q1) sessionId = json.substring(q1 + 1, q2).trim();
        }
        if (json.contains("\"resumeToken\"")) {
          int i = json.indexOf("\"resumeToken\"");
          int start = json.indexOf(":", i) + 1;
          int q1 = json.indexOf("\"", start);
          int q2 = q1 >= 0 ? json.indexOf("\"", q1 + 1) : -1;
          if (q1 >= 0 && q2 > q1) resumeToken = json.substring(q1 + 1, q2).trim();
        }
        if (json.contains("\"routeId\"")) {
          int i = json.indexOf("\"routeId\"");
          int start = json.indexOf(":", i) + 1;
          int q1 = json.indexOf("\"", start);
          int q2 = q1 >= 0 ? json.indexOf("\"", q1 + 1) : -1;
          if (q1 >= 0 && q2 > q1) routeId = json.substring(q1 + 1, q2).trim();
        }
        if (json.contains("\"hopIndex\"")) {
          int i = json.indexOf("\"hopIndex\"");
          int start = json.indexOf(":", i) + 1;
          int end = json.indexOf(",", start);
          if (end < 0) end = json.indexOf("}", start);
          if (end < 0) end = json.length();
          hopIndex = Integer.parseInt(json.substring(start, end).trim());
          if (hopIndex < 0) hopIndex = 0;
          if (hopIndex > 16) hopIndex = 16;
        }
        if (json.contains("\"clusterPreferredServer\"")) {
          int i = json.indexOf("\"clusterPreferredServer\"");
          int start = json.indexOf(":", i) + 1;
          int q1 = json.indexOf("\"", start);
          int q2 = q1 >= 0 ? json.indexOf("\"", q1 + 1) : -1;
          if (q1 >= 0 && q2 > q1) clusterPreferredServer = json.substring(q1 + 1, q2).trim();
        }
        String tlsProfileId = "";
        String ja3TargetHash = "";
        if (json.contains("\"tlsProfileId\"")) {
          int i = json.indexOf("\"tlsProfileId\"");
          int start = json.indexOf(":", i) + 1;
          int q1 = json.indexOf("\"", start);
          int q2 = q1 >= 0 ? json.indexOf("\"", q1 + 1) : -1;
          if (q1 >= 0 && q2 > q1) tlsProfileId = json.substring(q1 + 1, q2).trim();
        }
        if (json.contains("\"ja3TargetHash\"")) {
          int i = json.indexOf("\"ja3TargetHash\"");
          int start = json.indexOf(":", i) + 1;
          int q1 = json.indexOf("\"", start);
          int q2 = q1 >= 0 ? json.indexOf("\"", q1 + 1) : -1;
          if (q1 >= 0 && q2 > q1) ja3TargetHash = json.substring(q1 + 1, q2).trim();
        }
        if (tlsProfileId.length() > 128) tlsProfileId = tlsProfileId.substring(0, 128);
        if (ja3TargetHash.length() > 128) ja3TargetHash = ja3TargetHash.substring(0, 128);
        List<String> relayRouteHops = parseStringArray(json, "relayRouteHops");
        return Optional.of(new ClientOptions(
            padS4,
            probeObfsProfileId,
            relayHop,
            relayMaxHop,
            relayBudgetKbps,
            peerId,
            relayNonce,
            relaySig,
            sessionId,
            resumeToken,
            routeId,
            hopIndex,
            relayRouteHops,
            clusterPreferredServer,
            tlsProfileId,
            ja3TargetHash));
      } catch (Exception e) {
        return Optional.empty();
      }
    }
  }

  private static List<String> parseStringArray(String json, String key) {
    String needle = "\"" + key + "\"";
    int i = json.indexOf(needle);
    if (i < 0) return List.of();
    int l = json.indexOf('[', i);
    int r = json.indexOf(']', l + 1);
    if (l < 0 || r <= l) return List.of();
    String arr = json.substring(l + 1, r);
    if (arr.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    int q = 0;
    while (q < arr.length()) {
      int q1 = arr.indexOf('"', q);
      if (q1 < 0) break;
      int q2 = arr.indexOf('"', q1 + 1);
      if (q2 < 0) break;
      String s = arr.substring(q1 + 1, q2).trim();
      if (!s.isEmpty()) out.add(s);
      q = q2 + 1;
    }
    return Collections.unmodifiableList(out);
  }
}
