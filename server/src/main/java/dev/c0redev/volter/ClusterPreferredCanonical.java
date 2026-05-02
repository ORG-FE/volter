package dev.c0redev.volter;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;

final class ClusterPreferredCanonical {

  private ClusterPreferredCanonical() {}

  static String canonical(String raw) {
    if (raw == null) {
      return "";
    }
    String s = raw.trim();
    if (s.isEmpty()) {
      return "";
    }
    int op = s.indexOf('(');
    int cp = s.lastIndexOf(')');
    if (op >= 0 && cp > op) {
      String inner = s.substring(op + 1, cp).trim();
      String c = canonical(inner);
      if (!c.isEmpty()) {
        return c;
      }
    }
    String hp = joinCanonicalHostPort(s);
    if (!hp.isEmpty()) {
      return hp;
    }
    try {
      if (s.contains("://")) {
        URI uri = URI.create(s);
        String host = uri.getHost();
        if (host != null && !host.isBlank()) {
          int port = uri.getPort();
          if (port <= 0) {
            String sch = uri.getScheme();
            port = "https".equalsIgnoreCase(sch) ? 443 : 80;
          }
          return joinCanonicalHostPort(host + ":" + port);
        }
      }
    } catch (Exception ignored) {
    }
    return s;
  }

  static String joinCanonicalHostPort(String h) {
    h = h.trim();
    if (h.isEmpty()) {
      return "";
    }
    if (h.startsWith("[")) {
      try {
        InetSocketAddress a = ClusterTcpExitBridge.parseHostPort(h);
        InetAddress addr = a.getAddress();
        int port = a.getPort();
        return "[" + addr.getHostAddress() + "]:" + port;
      } catch (Exception e) {
        return "";
      }
    }
    int colon = h.lastIndexOf(':');
    if (colon <= 0 || colon >= h.length() - 1) {
      return "";
    }
    String host = h.substring(0, colon).trim();
    String portStr = h.substring(colon + 1).trim();
    int port;
    try {
      port = Integer.parseInt(portStr);
    } catch (NumberFormatException e) {
      return "";
    }
    if (port <= 0 || port > 65535) {
      return "";
    }
    if (ipv4Octets(host)) {
      try {
        InetAddress ia = InetAddress.getByName(host);
        return ia.getHostAddress() + ":" + port;
      } catch (Exception e) {
        return "";
      }
    }
    return host.toLowerCase() + ":" + portStr;
  }

  private static boolean ipv4Octets(String host) {
    String[] p = host.split("\\.", -1);
    if (p.length != 4) {
      return false;
    }
    for (String x : p) {
      if (x.isEmpty() || x.length() > 3) {
        return false;
      }
      int n;
      try {
        n = Integer.parseInt(x);
      } catch (NumberFormatException e) {
        return false;
      }
      if (n < 0 || n > 255) {
        return false;
      }
    }
    return true;
  }
}
