package dev.c0redev.volter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

final class DexoteServerKey {

  private final byte[] scalar;
  private final byte[] pub;

  private DexoteServerKey(byte[] scalar, byte[] pub) {
    this.scalar = scalar;
    this.pub = pub;
  }

  byte[] scalar() {
    return scalar;
  }

  byte[] pub() {
    return pub;
  }

  String pubBase64() {
    return Base64.getEncoder().encodeToString(pub);
  }

  static DexoteServerKey loadOrCreate(Path keyFile) throws IOException {
    byte[] scalar = null;
    if (Files.exists(keyFile)) {
      String raw = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
      if (!raw.isEmpty()) {
        try {
          byte[] dec = Base64.getDecoder().decode(raw);
          if (dec.length == Dexote.KEY_LEN) scalar = dec;
        } catch (IllegalArgumentException ignored) {
        }
      }
    }
    if (scalar == null) {
      scalar = new byte[Dexote.KEY_LEN];
      new SecureRandom().nextBytes(scalar);
      writeSecret(keyFile, Base64.getEncoder().encodeToString(scalar));
    }
    byte[] pub = Dexote.pubFromScalar(scalar);
    return new DexoteServerKey(scalar, pub);
  }

  private static void writeSecret(Path file, String content) throws IOException {
    Files.writeString(
        file,
        content + System.lineSeparator(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    try {
      Set<PosixFilePermission> perms =
          EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
      Files.setPosixFilePermissions(file, perms);
    } catch (UnsupportedOperationException | IOException ignored) {

    }
  }
}
