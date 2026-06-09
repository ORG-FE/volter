package dev.c0redev.volter;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;

final class Caps {

  private static final SecureRandom RND = new SecureRandom();

  private Caps() {}

  static byte[] build(Config cfg) {
    try {
      int legacyIpv6 = Ipv6Detect.hasIPv6() ? 1 : 0;
      int transportMask = 0;
      if (cfg.tcpEnabled()) transportMask |= Protocol.TRANSPORT_TCP;
      if (cfg.quicEnabled()) transportMask |= Protocol.TRANSPORT_QUIC;
      int featureBits = legacyIpv6 == 1 ? Protocol.FEAT_IPV6 : 0;
      featureBits |= Protocol.FEAT_POLY_HANDSHAKE;
      featureBits |= Protocol.FEAT_ROUTE_HOP_ACK;
      int obfsProfileId = Protocol.pickObfsProfileId();
      int tcpPortHint = cfg.listenPorts().isEmpty() ? 0 : cfg.listenPorts().get(0);
      byte[] nonce = new byte[8];
      RND.nextBytes(nonce);
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      Protocol.writeServerHelloCaps(bos, new Protocol.ServerHelloCaps(
          Protocol.CAPS_VERSION,
          legacyIpv6,
          transportMask,
          featureBits,
          cfg.quicEnabled() ? cfg.quicListenPort() : 0,
          tcpPortHint,
          obfsProfileId,
          nonce,
          QuicServer.getAdvertisedQuicLeafPin(),
          cfg.peerRelayEnabled() ? 2 : 1,
          2,
          cfg.peerRelayEnabled() ? 1 : 0
      ));
      return bos.toByteArray();
    } catch (Throwable t) {
      return new byte[0];
    }
  }
}
