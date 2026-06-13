package dev.c0redev.volter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ControlAuth {
  private static final SecureRandom RND = new SecureRandom();
  private static final long SESSION_TTL_MS = 6L * 60L * 60L * 1000L;
  private static final String PREFIX = "sha256:";

  private final Path keyFile;
  private final String keyHash;
  private final Map<String, Long> sessions = new ConcurrentHashMap<>();

  private ControlAuth(Path keyFile, String keyHash) {
    this.keyFile = keyFile;
    this.keyHash = keyHash;
  }

  static ControlAuth load(Path keyFile) throws IOException {
    Path parent = keyFile.toAbsolutePath().normalize().getParent();
    if (parent != null) Files.createDirectories(parent);
    if (!Files.exists(keyFile)) {
      String key = randomSecret(32);
      String hash = hashKey(key);
      Files.writeString(keyFile, PREFIX + hash + System.lineSeparator(), StandardCharsets.UTF_8);
      Log.logger(ControlAuth.class).warning("Generated control Doxh key. Save it now: " + key);
      return new ControlAuth(keyFile, hash);
    }
    String raw = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
    if (raw.startsWith(PREFIX)) {
      String h = raw.substring(PREFIX.length()).trim();
      if (h.length() != 64) throw new IOException("bad control Doxh hash");
      return new ControlAuth(keyFile, h);
    }
    if (!strongKey(raw)) {
      throw new IOException("weak control Doxh key, use random 32+ chars secret");
    }
    String hash = hashKey(raw);
    Files.writeString(keyFile, PREFIX + hash + System.lineSeparator(), StandardCharsets.UTF_8);
    return new ControlAuth(keyFile, hash);
  }

  boolean verifyKey(String key) {
    if (!strongKey(key)) return false;
    String h = hashKey(key.trim());
    return MessageDigest.isEqual(h.getBytes(StandardCharsets.UTF_8), keyHash.getBytes(StandardCharsets.UTF_8));
  }

  String createSession() {
    String sid = randomSecret(32);
    sessions.put(sid, System.currentTimeMillis() + SESSION_TTL_MS);
    return sid;
  }

  boolean validSession(String sid) {
    if (sid == null || sid.isBlank()) return false;
    Long exp = sessions.get(sid);
    if (exp == null) return false;
    if (exp < System.currentTimeMillis()) {
      sessions.remove(sid);
      return false;
    }
    return true;
  }

  void removeSession(String sid) {
    if (sid != null) sessions.remove(sid);
  }

  Path keyFile() { return keyFile; }

  static boolean strongKey(String raw) {
    if (raw == null) return false;
    String s = raw.trim();
    if (s.length() < 32) return false;
    String low = s.toLowerCase(Locale.ROOT);
    if (low.equals("admin") || low.equals("adminadmin") || low.equals("admin:admin") ||
        low.equals("password") || low.equals("123456") || low.equals("qwerty") ||
        low.equals("volter") || low.equals("doxh")) return false;
    int classes = 0;
    if (s.chars().anyMatch(Character::isLowerCase)) classes++;
    if (s.chars().anyMatch(Character::isUpperCase)) classes++;
    if (s.chars().anyMatch(Character::isDigit)) classes++;
    if (s.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) classes++;
    long distinct = s.chars().distinct().count();
    return classes >= 2 && distinct >= 12;
  }

  private static String hashKey(String key) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] b = md.digest(key.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(b.length * 2);
      for (byte x : b) sb.append(String.format("%02x", x & 0xff));
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String randomSecret(int bytes) {
    byte[] b = new byte[bytes];
    RND.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }
}
