package dev.c0redev.volter;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

final class ControlStore implements Closeable {
  private static final Logger log = Log.logger(ControlStore.class);

  private final Connection db;

  private ControlStore(Connection db) {
    this.db = db;
  }

  static ControlStore open(Path path) throws IOException {
    try {
      Path parent = path.toAbsolutePath().normalize().getParent();
      if (parent != null) Files.createDirectories(parent);
      Connection db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath().normalize());
      ControlStore store = new ControlStore(db);
      store.migrate();
      return store;
    } catch (SQLException e) {
      throw new IOException("control db open failed: " + e.getMessage(), e);
    }
  }

  void audit(String type, String subject, String detail) {
    try (var ps = db.prepareStatement("insert into audit_log(ts,type,subject,detail) values(?,?,?,?)")) {
      ps.setLong(1, Instant.now().getEpochSecond());
      ps.setString(2, type != null ? type : "");
      ps.setString(3, subject != null ? subject : "");
      ps.setString(4, detail != null ? detail : "");
      ps.executeUpdate();
    } catch (SQLException e) {
      log.warning("control audit failed: " + e.getMessage());
    }
  }

  List<ControlClient> listClients(boolean includeRevoked) throws SQLException {
    List<ControlClient> out = new ArrayList<>();
    String sql = "select c.id,c.user_id,c.group_id,c.name,c.enabled,c.created_at,c.expires_at,c.revoked_at,c.note,c.device_mode,c.device_limit,t.rx_bytes,t.tx_bytes from clients c left join traffic_total t on t.client_id=c.id";
    if (!includeRevoked) sql += " where c.enabled=1 and c.revoked_at is null";
    sql += " order by c.created_at desc";
    try (var ps = db.prepareStatement(sql)) {
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.add(ControlClient.from(rs));
      }
    }
    return out;
  }

  JSONArray listClientsJson(boolean includeRevoked) throws SQLException {
    JSONArray arr = new JSONArray();
    for (ControlClient c : listClients(includeRevoked)) arr.put(c.toJson());
    return arr;
  }

  JSONArray listGroupsJson() throws SQLException {
    JSONArray arr = new JSONArray();
    try (var ps = db.prepareStatement("select id,name,description,priority,created_at from client_groups order by priority desc,name asc")) {
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          arr.put(new JSONObject()
              .put("id", rs.getString("id"))
              .put("name", rs.getString("name"))
              .put("description", rs.getString("description"))
              .put("priority", rs.getInt("priority"))
              .put("createdAt", rs.getLong("created_at")));
        }
      }
    }
    return arr;
  }

  CreatedClient createClient(String name, String groupId, long expiresAt, String note, String secret, String salt, String secretHash) throws SQLException {
    long now = Instant.now().getEpochSecond();
    String id = "cli_" + UUID.randomUUID().toString().replace("-", "");
    String userId = "usr_" + UUID.randomUUID().toString().replace("-", "");
    if (groupId == null || groupId.isBlank()) groupId = "user";
    if (name == null || name.isBlank()) name = id;
    try (var ps = db.prepareStatement("insert into clients(id,user_id,group_id,name,enabled,created_at,expires_at,note,device_mode,device_limit) values(?,?,?,?,?,?,?,?,?,?)")) {
      ps.setString(1, id);
      ps.setString(2, userId);
      ps.setString(3, groupId);
      ps.setString(4, name.trim());
      ps.setInt(5, 1);
      ps.setLong(6, now);
      if (expiresAt > 0) ps.setLong(7, expiresAt); else ps.setObject(7, null);
      ps.setString(8, note != null ? note : "");
      ps.setString(9, "multi");
      ps.setInt(10, 3);
      ps.executeUpdate();
    }
    try (var ps = db.prepareStatement("insert into client_credentials(client_id,secret_hash,salt,version,created_at) values(?,?,?,?,?)")) {
      ps.setString(1, id);
      ps.setString(2, secretHash);
      ps.setString(3, salt);
      ps.setInt(4, 2);
      ps.setLong(5, now);
      ps.executeUpdate();
    }
    try (var ps = db.prepareStatement("insert or ignore into traffic_total(client_id,rx_bytes,tx_bytes,updated_at) values(?,0,0,?)")) {
      ps.setString(1, id);
      ps.setLong(2, now);
      ps.executeUpdate();
    }
    return new CreatedClient(id, userId, name.trim(), groupId, expiresAt, secret, salt, now);
  }

  boolean revokeClient(String id) throws SQLException {
    long now = Instant.now().getEpochSecond();
    try (var ps = db.prepareStatement("update clients set enabled=0,revoked_at=? where id=?")) {
      ps.setLong(1, now);
      ps.setString(2, id);
      int n = ps.executeUpdate();
      if (n > 0) {
        try (var r = db.prepareStatement("insert or ignore into revoked_keys(id,client_id,revoked_at,reason) values(?,?,?,?)")) {
          r.setString(1, "rev_" + id);
          r.setString(2, id);
          r.setLong(3, now);
          r.setString(4, "manual revoke");
          r.executeUpdate();
        }
      }
      return n > 0;
    }
  }

  CreatedClient rotateClient(String id, String secret, String salt, String secretHash, long graceUntil) throws SQLException {
    long now = Instant.now().getEpochSecond();
    ControlClient c = findClient(id);
    if (c == null) return null;
    String prevHash = null;
    String prevSalt = null;
    try (var old = db.prepareStatement("select secret_hash,salt from client_credentials where client_id=?")) {
      old.setString(1, id);
      try (ResultSet rs = old.executeQuery()) {
        if (rs.next() && graceUntil > now) {
          prevHash = rs.getString("secret_hash");
          prevSalt = rs.getString("salt");
        }
      }
    }
    try (var ps = db.prepareStatement("update client_credentials set secret_hash=?,salt=?,previous_secret_hash=?,previous_salt=?,rotated_at=?,grace_until=? where client_id=?")) {
      ps.setString(1, secretHash);
      ps.setString(2, salt);
      ps.setString(3, prevHash);
      ps.setString(4, prevSalt);
      ps.setLong(5, now);
      if (graceUntil > 0) ps.setLong(6, graceUntil); else ps.setObject(6, null);
      ps.setString(7, id);
      ps.executeUpdate();
    }
    return new CreatedClient(c.id, c.userId, c.name, c.groupId, c.expiresAt, secret, salt, now);
  }

  boolean verifyManaged(String clientId, String deviceId, String nonce, long tsSec, String sig) throws SQLException {
    if (clientId == null || clientId.isBlank() || deviceId == null || deviceId.isBlank() || nonce == null || nonce.isBlank() || sig == null || sig.isBlank()) {
      return false;
    }
    long now = Instant.now().getEpochSecond();
    if (Math.abs(now - tsSec) > 300) return false;
    String deviceMode;
    int deviceLimit;
    try (var ps = db.prepareStatement("select c.enabled,c.expires_at,c.revoked_at,c.device_mode,c.device_limit,cc.secret_hash,cc.salt,cc.previous_secret_hash,cc.grace_until from clients c join client_credentials cc on cc.client_id=c.id where c.id=?")) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return false;
        if (rs.getInt("enabled") == 0) return false;
        long exp = rs.getLong("expires_at");
        if (exp > 0 && exp < now) return false;
        long revoked = rs.getLong("revoked_at");
        if (revoked > 0) return false;
        String salt = rs.getString("salt");
        String secretHash = rs.getString("secret_hash");
        if (secretHash == null || secretHash.isBlank() || salt == null) return false;
        boolean ok = validManagedSig(secretHash, clientId, deviceId, nonce, tsSec, sig);
        String previousHash = rs.getString("previous_secret_hash");
        long graceUntil = rs.getLong("grace_until");
        if (!ok && previousHash != null && !previousHash.isBlank() && graceUntil >= now) {
          ok = validManagedSig(previousHash, clientId, deviceId, nonce, tsSec, sig);
        }
        if (!ok) return false;
        deviceMode = rs.getString("device_mode");
        deviceLimit = rs.getInt("device_limit");
      }
    }
    if (!rememberManagedNonce(clientId, nonce, now)) return false;
    if (!allowDevice(clientId, deviceId, deviceMode, deviceLimit)) return false;
    upsertDevice(clientId, deviceId);
    return true;
  }

  private boolean rememberManagedNonce(String clientId, String nonce, long now) throws SQLException {
    try (var cleanup = db.prepareStatement("delete from managed_nonces where ts<?")) {
      cleanup.setLong(1, now - 600);
      cleanup.executeUpdate();
    }
    try (var ps = db.prepareStatement("insert or ignore into managed_nonces(client_id,nonce,ts) values(?,?,?)")) {
      ps.setString(1, clientId);
      ps.setString(2, nonce);
      ps.setLong(3, now);
      return ps.executeUpdate() == 1;
    }
  }

  private void upsertDevice(String clientId, String deviceId) throws SQLException {
    long now = Instant.now().getEpochSecond();
    String id = "dev_" + sha256(clientId + ":" + deviceId).substring(0, 24);
    try (var ps = db.prepareStatement("insert into client_devices(id,client_id,device_id,first_seen,last_seen,enabled) values(?,?,?,?,?,1) on conflict(client_id,device_id) do update set last_seen=excluded.last_seen")) {
      ps.setString(1, id);
      ps.setString(2, clientId);
      ps.setString(3, deviceId);
      ps.setLong(4, now);
      ps.setLong(5, now);
      ps.executeUpdate();
    }
  }

  private boolean allowDevice(String clientId, String deviceId, String mode, int limit) throws SQLException {
    if (mode == null || mode.isBlank()) mode = "multi";
    if (limit <= 0) limit = 3;
    try (var ps = db.prepareStatement("select enabled from client_devices where client_id=? and device_id=?")) {
      ps.setString(1, clientId);
      ps.setString(2, deviceId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getInt("enabled") != 0;
      }
    }
    if ("unlimited".equalsIgnoreCase(mode)) return true;
    int max = "single".equalsIgnoreCase(mode) ? 1 : limit;
    try (var ps = db.prepareStatement("select count(*) from client_devices where client_id=? and enabled=1")) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getInt(1) < max;
      }
    }
  }

  JSONArray listDevicesJson(String clientId) throws SQLException {
    JSONArray arr = new JSONArray();
    try (var ps = db.prepareStatement("select id,device_id,platform,app_version,first_seen,last_seen,enabled from client_devices where client_id=? order by last_seen desc")) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          arr.put(new JSONObject()
              .put("id", rs.getString("id"))
              .put("deviceId", rs.getString("device_id"))
              .put("platform", rs.getString("platform"))
              .put("appVersion", rs.getString("app_version"))
              .put("firstSeen", rs.getLong("first_seen"))
              .put("lastSeen", rs.getLong("last_seen"))
              .put("enabled", rs.getInt("enabled") != 0));
        }
      }
    }
    return arr;
  }

  boolean revokeDevice(String clientId, String deviceId) throws SQLException {
    try (var ps = db.prepareStatement("update client_devices set enabled=0 where client_id=? and device_id=?")) {
      ps.setString(1, clientId);
      ps.setString(2, deviceId);
      return ps.executeUpdate() > 0;
    }
  }

  boolean updateClientGroup(String clientId, String groupId) throws SQLException {
    try (var check = db.prepareStatement("select 1 from client_groups where id=?")) {
      check.setString(1, groupId);
      try (ResultSet rs = check.executeQuery()) {
        if (!rs.next()) return false;
      }
    }
    try (var ps = db.prepareStatement("update clients set group_id=? where id=?")) {
      ps.setString(1, groupId);
      ps.setString(2, clientId);
      return ps.executeUpdate() > 0;
    }
  }

  boolean updateDevicePolicy(String clientId, String mode, int limit) throws SQLException {
    if (mode == null || mode.isBlank()) mode = "multi";
    mode = mode.trim().toLowerCase();
    if (!mode.equals("single") && !mode.equals("multi") && !mode.equals("unlimited")) return false;
    if (limit <= 0) limit = mode.equals("single") ? 1 : 3;
    try (var ps = db.prepareStatement("update clients set device_mode=?,device_limit=? where id=?")) {
      ps.setString(1, mode);
      ps.setInt(2, limit);
      ps.setString(3, clientId);
      return ps.executeUpdate() > 0;
    }
  }

  JSONObject effectivePolicyJson(String clientId) throws SQLException {
    ControlClient client = findClient(clientId);
    if (client == null) return null;
    JSONObject out = defaultPolicy(client.expiresAt);
    mergePolicy(out, loadPolicy("group_policy", "group_id", client.groupId));
    mergePolicy(out, loadPolicy("client_policy", "client_id", client.id));
    out.put("clientId", client.id).put("groupId", client.groupId);
    return out;
  }

  boolean updateClientPolicy(String clientId, JSONObject policy) throws SQLException {
    if (findClient(clientId) == null) return false;
    upsertPolicy("client_policy", "client_id", clientId, policy);
    return true;
  }

  boolean updateGroupPolicy(String groupId, JSONObject policy) throws SQLException {
    try (var check = db.prepareStatement("select 1 from client_groups where id=?")) {
      check.setString(1, groupId);
      try (ResultSet rs = check.executeQuery()) {
        if (!rs.next()) return false;
      }
    }
    upsertPolicy("group_policy", "group_id", groupId, policy);
    return true;
  }

  JSONObject trafficSummaryJson() throws SQLException {
    JSONObject out = new JSONObject().put("rxBytes", 0L).put("txBytes", 0L).put("clients", new JSONArray());
    try (var ps = db.prepareStatement("select coalesce(sum(rx_bytes),0) rx,coalesce(sum(tx_bytes),0) tx from traffic_total")) {
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          out.put("rxBytes", rs.getLong("rx"));
          out.put("txBytes", rs.getLong("tx"));
        }
      }
    }
    JSONArray clients = new JSONArray();
    try (var ps = db.prepareStatement("select c.id,c.name,t.rx_bytes,t.tx_bytes,t.updated_at from traffic_total t join clients c on c.id=t.client_id order by (t.rx_bytes+t.tx_bytes) desc limit 50")) {
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          clients.put(new JSONObject()
              .put("clientId", rs.getString("id"))
              .put("name", rs.getString("name"))
              .put("rxBytes", rs.getLong("rx_bytes"))
              .put("txBytes", rs.getLong("tx_bytes"))
              .put("updatedAt", rs.getLong("updated_at")));
        }
      }
    }
    out.put("clients", clients);
    return out;
  }

  JSONObject clientTrafficJson(String clientId, String bearerSecret) throws SQLException {
    try (var ps = db.prepareStatement("select cc.salt,cc.secret_hash from client_credentials cc where cc.client_id=?")) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return null;
        String salt = rs.getString("salt");
        String storedHash = rs.getString("secret_hash");
        if (salt == null || storedHash == null || storedHash.isBlank()) return null;
        String computedHash = sha256(salt + ":" + bearerSecret);
        if (!MessageDigest.isEqual(computedHash.getBytes(StandardCharsets.UTF_8), storedHash.getBytes(StandardCharsets.UTF_8))) {
          return null;
        }
      }
    }
    try (var ps = db.prepareStatement("select coalesce(rx_bytes,0) rx,coalesce(tx_bytes,0) tx from traffic_total where client_id=?")) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return new JSONObject()
              .put("rxBytes", rs.getLong("rx"))
              .put("txBytes", rs.getLong("tx"));
        }
      }
    }
    return new JSONObject().put("rxBytes", 0L).put("txBytes", 0L);
  }

  void addTraffic(String clientId, long rxBytes, long txBytes) throws SQLException {
    if (clientId == null || clientId.isBlank() || (rxBytes <= 0 && txBytes <= 0)) return;
    long now = Instant.now().getEpochSecond();
    String day = LocalDate.now(ZoneOffset.UTC).toString();
    String month = day.substring(0, 7);
    try (var ps = db.prepareStatement("insert into traffic_total(client_id,rx_bytes,tx_bytes,updated_at) values(?,?,?,?) on conflict(client_id) do update set rx_bytes=rx_bytes+excluded.rx_bytes,tx_bytes=tx_bytes+excluded.tx_bytes,updated_at=excluded.updated_at")) {
      ps.setString(1, clientId);
      ps.setLong(2, Math.max(0, rxBytes));
      ps.setLong(3, Math.max(0, txBytes));
      ps.setLong(4, now);
      ps.executeUpdate();
    }
    try (var ps = db.prepareStatement("insert into traffic_daily(client_id,day,rx_bytes,tx_bytes) values(?,?,?,?) on conflict(client_id,day) do update set rx_bytes=rx_bytes+excluded.rx_bytes,tx_bytes=tx_bytes+excluded.tx_bytes")) {
      ps.setString(1, clientId);
      ps.setString(2, day);
      ps.setLong(3, Math.max(0, rxBytes));
      ps.setLong(4, Math.max(0, txBytes));
      ps.executeUpdate();
    }
    try (var ps = db.prepareStatement("insert into traffic_monthly(client_id,month,rx_bytes,tx_bytes) values(?,?,?,?) on conflict(client_id,month) do update set rx_bytes=rx_bytes+excluded.rx_bytes,tx_bytes=tx_bytes+excluded.tx_bytes")) {
      ps.setString(1, clientId);
      ps.setString(2, month);
      ps.setLong(3, Math.max(0, rxBytes));
      ps.setLong(4, Math.max(0, txBytes));
      ps.executeUpdate();
    }
  }

  String startSession(String clientId, String deviceId, String serverId, String path, String remoteAddr) throws SQLException {
    if (clientId == null || clientId.isBlank()) return "";
    String id = "ses_" + UUID.randomUUID().toString().replace("-", "");
    long now = Instant.now().getEpochSecond();
    try (var ps = db.prepareStatement("insert into sessions(id,client_id,device_id,server_id,started_at,rx_bytes,tx_bytes,path,remote_addr) values(?,?,?,?,?,0,0,?,?)")) {
      ps.setString(1, id);
      ps.setString(2, clientId);
      ps.setString(3, deviceId != null ? deviceId : "");
      ps.setString(4, serverId != null ? serverId : "");
      ps.setLong(5, now);
      ps.setString(6, path != null ? path : "");
      ps.setString(7, remoteAddr != null ? remoteAddr : "");
      ps.executeUpdate();
    }
    return id;
  }

  void endSession(String sessionId, long rxBytes, long txBytes) throws SQLException {
    if (sessionId == null || sessionId.isBlank()) return;
    long now = Instant.now().getEpochSecond();
    try (var ps = db.prepareStatement("update sessions set ended_at=?,rx_bytes=?,tx_bytes=? where id=?")) {
      ps.setLong(1, now);
      ps.setLong(2, Math.max(0, rxBytes));
      ps.setLong(3, Math.max(0, txBytes));
      ps.setString(4, sessionId);
      ps.executeUpdate();
    }
  }

  JSONArray activeSessionsJson() throws SQLException {
    JSONArray arr = new JSONArray();
    try (var ps = db.prepareStatement("select id,client_id,device_id,server_id,started_at,rx_bytes,tx_bytes,path,remote_addr from sessions where ended_at is null order by started_at desc limit 200")) {
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          arr.put(new JSONObject()
              .put("id", rs.getString("id"))
              .put("clientId", rs.getString("client_id"))
              .put("deviceId", rs.getString("device_id"))
              .put("serverId", rs.getString("server_id"))
              .put("startedAt", rs.getLong("started_at"))
              .put("rxBytes", rs.getLong("rx_bytes"))
              .put("txBytes", rs.getLong("tx_bytes"))
              .put("path", rs.getString("path"))
              .put("remoteAddr", rs.getString("remote_addr")));
        }
      }
    }
    return arr;
  }

  JSONObject registryJson() throws SQLException {
    try (var ps = db.prepareStatement("select version,payload,sig,pub,updated_at from cluster_registry where id=1")) {
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return null;
        return new JSONObject()
            .put("version", rs.getLong("version"))
            .put("payload", new JSONObject(rs.getString("payload")))
            .put("sig", rs.getString("sig"))
            .put("pub", rs.getString("pub"))
            .put("updatedAt", rs.getLong("updated_at"));
      }
    }
  }

  void saveRegistry(long version, JSONObject payload, String sig, String pub) throws SQLException {
    long now = Instant.now().getEpochSecond();
    try (var ps = db.prepareStatement("insert into cluster_registry(id,version,payload,sig,pub,updated_at) values(1,?,?,?,?,?) on conflict(id) do update set version=excluded.version,payload=excluded.payload,sig=excluded.sig,pub=excluded.pub,updated_at=excluded.updated_at")) {
      ps.setLong(1, version);
      ps.setString(2, payload.toString());
      ps.setString(3, sig != null ? sig : "");
      ps.setString(4, pub != null ? pub : "");
      ps.setLong(5, now);
      ps.executeUpdate();
    }
  }

  void logDns(String clientId, String deviceId, String domain, String action, String resolver, int ttl) throws SQLException {
    if (domain == null || domain.isBlank()) return;
    try (var ps = db.prepareStatement("insert into dns_logs(client_id,device_id,ts,domain,action,resolver,ttl) values(?,?,?,?,?,?,?)")) {
      ps.setString(1, clientId != null ? clientId : "");
      ps.setString(2, deviceId != null ? deviceId : "");
      ps.setLong(3, Instant.now().getEpochSecond());
      ps.setString(4, domain.trim().toLowerCase());
      ps.setString(5, action != null && !action.isBlank() ? action.trim() : "allow");
      ps.setString(6, resolver != null ? resolver : "");
      ps.setInt(7, Math.max(0, ttl));
      ps.executeUpdate();
    }
  }

  JSONArray dnsLogsJson(int limit) throws SQLException {
    if (limit <= 0 || limit > 1000) limit = 200;
    JSONArray arr = new JSONArray();
    try (var ps = db.prepareStatement("select id,client_id,device_id,ts,domain,action,resolver,ttl from dns_logs order by ts desc,id desc limit ?")) {
      ps.setInt(1, limit);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          arr.put(new JSONObject()
              .put("id", rs.getLong("id"))
              .put("clientId", rs.getString("client_id"))
              .put("deviceId", rs.getString("device_id"))
              .put("ts", rs.getLong("ts"))
              .put("domain", rs.getString("domain"))
              .put("action", rs.getString("action"))
              .put("resolver", rs.getString("resolver"))
              .put("ttl", rs.getInt("ttl")));
        }
      }
    }
    return arr;
  }

  int cleanupDnsLogs(int retentionDays) throws SQLException {
    if (retentionDays <= 0) retentionDays = 30;
    long cutoff = Instant.now().getEpochSecond() - retentionDays * 86_400L;
    try (var ps = db.prepareStatement("delete from dns_logs where ts<?")) {
      ps.setLong(1, cutoff);
      return ps.executeUpdate();
    }
  }

  private JSONObject defaultPolicy(long clientExpiresAt) {
    return new JSONObject()
        .put("dnsServers", new JSONArray())
        .put("routes", new JSONArray())
        .put("excludes", new JSONArray())
        .put("trafficCapBytes", JSONObject.NULL)
        .put("speedLimitKbps", JSONObject.NULL)
        .put("allowedServers", new JSONArray())
        .put("allowedCountries", new JSONArray())
        .put("meshAllowed", true)
        .put("relayAllowed", true)
        .put("expiresAt", clientExpiresAt > 0 ? clientExpiresAt : JSONObject.NULL);
  }

  private JSONObject loadPolicy(String table, String keyColumn, String id) throws SQLException {
    if (id == null || id.isBlank()) return null;
    String sql = "select dns_servers,routes,excludes,traffic_cap_bytes,speed_limit_kbps,allowed_servers,allowed_countries,mesh_allowed,relay_allowed,expires_at from " + table + " where " + keyColumn + "=?";
    try (var ps = db.prepareStatement(sql)) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return null;
        JSONObject p = new JSONObject();
        putJsonArray(p, "dnsServers", rs.getString("dns_servers"));
        putJsonArray(p, "routes", rs.getString("routes"));
        putJsonArray(p, "excludes", rs.getString("excludes"));
        putLongIfPresent(p, "trafficCapBytes", rs, "traffic_cap_bytes");
        putLongIfPresent(p, "speedLimitKbps", rs, "speed_limit_kbps");
        putJsonArray(p, "allowedServers", rs.getString("allowed_servers"));
        putJsonArray(p, "allowedCountries", rs.getString("allowed_countries"));
        putBoolIfPresent(p, "meshAllowed", rs, "mesh_allowed");
        putBoolIfPresent(p, "relayAllowed", rs, "relay_allowed");
        putLongIfPresent(p, "expiresAt", rs, "expires_at");
        return p;
      }
    }
  }

  private void upsertPolicy(String table, String keyColumn, String id, JSONObject p) throws SQLException {
    String sql = "insert into " + table + "(" + keyColumn + ",dns_servers,routes,excludes,traffic_cap_bytes,speed_limit_kbps,allowed_servers,allowed_countries,mesh_allowed,relay_allowed,expires_at) values(?,?,?,?,?,?,?,?,?,?,?) "
        + "on conflict(" + keyColumn + ") do update set dns_servers=excluded.dns_servers,routes=excluded.routes,excludes=excluded.excludes,traffic_cap_bytes=excluded.traffic_cap_bytes,speed_limit_kbps=excluded.speed_limit_kbps,allowed_servers=excluded.allowed_servers,allowed_countries=excluded.allowed_countries,mesh_allowed=excluded.mesh_allowed,relay_allowed=excluded.relay_allowed,expires_at=excluded.expires_at";
    try (var ps = db.prepareStatement(sql)) {
      ps.setString(1, id);
      setJsonArray(ps, 2, p, "dnsServers");
      setJsonArray(ps, 3, p, "routes");
      setJsonArray(ps, 4, p, "excludes");
      setLong(ps, 5, p, "trafficCapBytes");
      setLong(ps, 6, p, "speedLimitKbps");
      setJsonArray(ps, 7, p, "allowedServers");
      setJsonArray(ps, 8, p, "allowedCountries");
      setBool(ps, 9, p, "meshAllowed");
      setBool(ps, 10, p, "relayAllowed");
      setLong(ps, 11, p, "expiresAt");
      ps.executeUpdate();
    }
  }

  private static void mergePolicy(JSONObject target, JSONObject src) {
    if (src == null) return;
    for (String key : src.keySet()) {
      Object v = src.get(key);
      if (v != JSONObject.NULL) target.put(key, v);
    }
  }

  private static void putJsonArray(JSONObject obj, String key, String raw) {
    if (raw == null || raw.isBlank()) return;
    obj.put(key, new JSONArray(raw));
  }

  private static void putLongIfPresent(JSONObject obj, String key, ResultSet rs, String col) throws SQLException {
    long v = rs.getLong(col);
    if (!rs.wasNull()) obj.put(key, v);
  }

  private static void putBoolIfPresent(JSONObject obj, String key, ResultSet rs, String col) throws SQLException {
    int v = rs.getInt(col);
    if (!rs.wasNull()) obj.put(key, v != 0);
  }

  private static void setJsonArray(java.sql.PreparedStatement ps, int idx, JSONObject p, String key) throws SQLException {
    if (!p.has(key) || p.isNull(key)) ps.setObject(idx, null); else ps.setString(idx, p.getJSONArray(key).toString());
  }

  private static void setLong(java.sql.PreparedStatement ps, int idx, JSONObject p, String key) throws SQLException {
    if (!p.has(key) || p.isNull(key)) ps.setObject(idx, null); else ps.setLong(idx, p.getLong(key));
  }

  private static void setBool(java.sql.PreparedStatement ps, int idx, JSONObject p, String key) throws SQLException {
    if (!p.has(key) || p.isNull(key)) ps.setObject(idx, null); else ps.setInt(idx, p.getBoolean(key) ? 1 : 0);
  }

  private static String sha256(String s) {
    try {
      byte[] b = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(b.length * 2);
      for (byte x : b) sb.append(String.format("%02x", x & 0xff));
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean validManagedSig(String secretHash, String clientId, String deviceId, String nonce, long tsSec, String sig) {
    try {
      String msg = clientId + "|" + deviceId + "|" + nonce + "|" + tsSec;
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secretHash.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] expected = mac.doFinal(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      byte[] got = Base64.getUrlDecoder().decode(sig);
      return java.security.MessageDigest.isEqual(expected, got);
    } catch (Exception e) {
      return false;
    }
  }

  private ControlClient findClient(String id) throws SQLException {
    try (var ps = db.prepareStatement("select c.id,c.user_id,c.group_id,c.name,c.enabled,c.created_at,c.expires_at,c.revoked_at,c.note,c.device_mode,c.device_limit,t.rx_bytes,t.tx_bytes from clients c left join traffic_total t on t.client_id=c.id where c.id=?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return ControlClient.from(rs);
      }
    }
    return null;
  }

  private void migrate() throws SQLException {
    try (Statement st = db.createStatement()) {
      st.execute("pragma journal_mode=WAL");
      st.execute("pragma busy_timeout=5000");
      st.execute("create table if not exists schema_migrations(version integer primary key, applied_at integer not null)");
      st.execute("create table if not exists users(id text primary key, name text not null, created_at integer not null)");
      st.execute("create table if not exists client_groups(id text primary key, name text not null unique, description text, priority integer not null, created_at integer not null)");
      st.execute("create table if not exists clients(id text primary key, user_id text not null, group_id text, name text not null, enabled integer not null, created_at integer not null, expires_at integer, revoked_at integer, note text, device_mode text not null default 'multi', device_limit integer not null default 3)");
      st.execute("create table if not exists client_credentials(client_id text primary key, secret_hash text not null, salt text not null, version integer not null, created_at integer not null, rotated_at integer, grace_until integer, previous_secret_hash text, previous_salt text)");
      st.execute("create table if not exists client_devices(id text primary key, client_id text not null, device_id text not null, platform text, app_version text, first_seen integer not null, last_seen integer, enabled integer not null, unique(client_id, device_id))");
      st.execute("create table if not exists group_policy(group_id text primary key, dns_servers text, routes text, excludes text, traffic_cap_bytes integer, speed_limit_kbps integer, allowed_servers text, allowed_countries text, mesh_allowed integer, relay_allowed integer, expires_at integer, overridable_fields text)");
      st.execute("create table if not exists client_policy(client_id text primary key, dns_servers text, routes text, excludes text, traffic_cap_bytes integer, speed_limit_kbps integer, allowed_servers text, allowed_countries text, mesh_allowed integer, relay_allowed integer, expires_at integer, override_mask text)");
      st.execute("create table if not exists sessions(id text primary key, client_id text, device_id text, server_id text, started_at integer not null, ended_at integer, rx_bytes integer not null default 0, tx_bytes integer not null default 0, path text, remote_addr text, platform text, app_version text)");
      st.execute("create table if not exists traffic_total(client_id text primary key, rx_bytes integer not null default 0, tx_bytes integer not null default 0, updated_at integer not null)");
      st.execute("create table if not exists traffic_daily(client_id text, day text, rx_bytes integer not null default 0, tx_bytes integer not null default 0, primary key(client_id, day))");
      st.execute("create table if not exists traffic_monthly(client_id text, month text, rx_bytes integer not null default 0, tx_bytes integer not null default 0, primary key(client_id, month))");
      st.execute("create table if not exists dns_logs(id integer primary key autoincrement, client_id text, device_id text, ts integer not null, domain text not null, action text not null, resolver text, ttl integer)");
      st.execute("create table if not exists cluster_registry(id integer primary key check(id=1), version integer not null, payload text not null, sig text, pub text, updated_at integer not null)");
      st.execute("create table if not exists cluster_registry_nodes(id text primary key, endpoint text, last_pull_at integer, version integer, ok integer)");
      st.execute("create table if not exists revoked_keys(id text primary key, client_id text, revoked_at integer not null, reason text)");
      st.execute("create table if not exists audit_log(id integer primary key autoincrement, ts integer not null, type text not null, subject text, detail text)");
      st.execute("create table if not exists control_sessions(id text primary key, created_at integer not null, expires_at integer not null, remote_addr text)");
      st.execute("create table if not exists managed_nonces(client_id text not null, nonce text not null, ts integer not null, primary key(client_id, nonce))");
    }
    addColumnIfMissing("client_credentials", "previous_secret_hash", "text");
    addColumnIfMissing("client_credentials", "previous_salt", "text");
    addColumnIfMissing("clients", "device_mode", "text not null default 'multi'");
    addColumnIfMissing("clients", "device_limit", "integer not null default 3");
    seedGroups();
  }

  private void addColumnIfMissing(String table, String column, String type) throws SQLException {
    try (ResultSet rs = db.getMetaData().getColumns(null, null, table, column)) {
      if (rs.next()) return;
    }
    try (Statement st = db.createStatement()) {
      st.execute("alter table " + table + " add column " + column + " " + type);
    }
  }

  record CreatedClient(String id, String userId, String name, String groupId, long expiresAt, String secret, String salt, long createdAt) {}

  record ControlClient(String id, String userId, String groupId, String name, boolean enabled, long createdAt, long expiresAt, long revokedAt, String note, String deviceMode, int deviceLimit, long rxBytes, long txBytes) {
    static ControlClient from(ResultSet rs) throws SQLException {
      return new ControlClient(
          rs.getString("id"),
          rs.getString("user_id"),
          rs.getString("group_id"),
          rs.getString("name"),
          rs.getInt("enabled") != 0,
          rs.getLong("created_at"),
          rs.getLong("expires_at"),
          rs.getLong("revoked_at"),
          rs.getString("note"),
          rs.getString("device_mode"),
          rs.getInt("device_limit"),
          rs.getLong("rx_bytes"),
          rs.getLong("tx_bytes"));
    }

    JSONObject toJson() {
      return new JSONObject()
          .put("id", id)
          .put("userId", userId)
          .put("groupId", groupId)
          .put("name", name)
          .put("enabled", enabled)
          .put("createdAt", createdAt)
          .put("expiresAt", expiresAt)
          .put("revokedAt", revokedAt)
          .put("note", note != null ? note : "")
          .put("deviceMode", deviceMode != null ? deviceMode : "multi")
          .put("deviceLimit", deviceLimit > 0 ? deviceLimit : 3)
          .put("rxBytes", rxBytes)
          .put("txBytes", txBytes);
    }
  }

  private void seedGroups() throws SQLException {
    String[][] groups = new String[][] {
        {"user", "Default VPN users", "10"},
        {"volunteer", "Relay volunteer clients", "30"},
        {"admin", "Administrators", "100"},
    };
    long now = Instant.now().getEpochSecond();
    try (var ps = db.prepareStatement("insert or ignore into client_groups(id,name,description,priority,created_at) values(?,?,?,?,?)")) {
      for (String[] g : groups) {
        ps.setString(1, g[0]);
        ps.setString(2, g[0]);
        ps.setString(3, g[1]);
        ps.setInt(4, Integer.parseInt(g[2]));
        ps.setLong(5, now);
        ps.addBatch();
      }
      ps.executeBatch();
    }
    try (Statement st = db.createStatement()) {
      st.execute("update clients set group_id='volunteer' where group_id='relay-volunteers'");
      st.execute("update clients set group_id='user' where group_id in ('family','guest','mobile','iot')");
      st.execute("delete from client_groups where id in ('family','guest','mobile','iot','relay-volunteers')");
    }
  }

  @Override
  public void close() throws IOException {
    try {
      db.close();
    } catch (SQLException e) {
      throw new IOException(e);
    }
  }
}
