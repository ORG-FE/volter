package dev.c0redev.volter;

final class ControlRuntime {
  private static volatile ControlStore store;

  static void install(ControlStore s) {
    store = s;
  }

  static void clear(ControlStore s) {
    if (store == s) store = null;
  }

  static boolean verifyManaged(String clientId, String deviceId, String nonce, long tsSec, String sig) {
    ControlStore s = store;
    if (s == null) return true;
    try {
      return s.verifyManaged(clientId, deviceId, nonce, tsSec, sig);
    } catch (Exception e) {
      Log.logger(ControlRuntime.class).warning("managed auth failed: " + e.getMessage());
      return false;
    }
  }

  static void addTraffic(String clientId, long rxBytes, long txBytes) {
    ControlStore s = store;
    if (s == null || clientId == null || clientId.isBlank()) return;
    try {
      s.addTraffic(clientId, rxBytes, txBytes);
    } catch (Exception e) {
      Log.logger(ControlRuntime.class).warning("traffic accounting failed: " + e.getMessage());
    }
  }

  static String startSession(String clientId, String deviceId, String serverId, String path, String remoteAddr) {
    ControlStore s = store;
    if (s == null || clientId == null || clientId.isBlank()) return "";
    try {
      return s.startSession(clientId, deviceId, serverId, path, remoteAddr);
    } catch (Exception e) {
      Log.logger(ControlRuntime.class).warning("session start failed: " + e.getMessage());
      return "";
    }
  }

  static void endSession(String sessionId, long rxBytes, long txBytes) {
    ControlStore s = store;
    if (s == null || sessionId == null || sessionId.isBlank()) return;
    try {
      s.endSession(sessionId, rxBytes, txBytes);
    } catch (Exception e) {
      Log.logger(ControlRuntime.class).warning("session end failed: " + e.getMessage());
    }
  }
}
