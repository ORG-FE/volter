package dev.c0redev.volter.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

class MeshConfigTest {
    @Test
    fun volunteerIsExplicitAndSeparateFromMesh() {
        val raw = JSONObject(
            """
            {
              "enabled": true,
              "volunteer": { "enabled": false },
              "p2p": { "enabled": true },
              "serverRelay": { "enabled": true },
              "stun": { "enabled": true, "servers": ["stun.l.google.com:19302"] },
              "discovery": { "dhtRpcSeedPeers": ["1.2.3.4:4001"] }
            }
            """.trimIndent(),
        )

        val mesh = MeshConfig.fromJson(raw)
        assertTrue(mesh.enabled)
        assertFalse(mesh.volunteer.enabled)
        assertTrue(mesh.p2p.enabled)
        assertTrue(mesh.serverRelay.enabled)
        assertTrue(mesh.stun.enabled)

        val out = MeshConfig.fromJson(mesh.toJson())
        assertTrue(out.enabled)
        assertFalse(out.volunteer.enabled)
        assertTrue(out.p2p.enabled)
    }

    @Test
    fun publicCopyStripsSecretsButKeepsVolunteerChoice() {
        val mesh = MeshConfig(
            enabled = true,
            volunteer = MeshVolunteerOptions(enabled = false, privateKey = "secret-key"),
            discovery = MeshDiscoveryOptions(dhtRpcSecret = "secret-dht"),
        )

        val pub = mesh.publicCopy()
        assertTrue(pub.enabled)
        assertFalse(pub.volunteer.enabled)
        assertNull(pub.volunteer.privateKey)
        assertNull(pub.discovery.dhtRpcSecret)
    }

    @Test
    fun legacyRelayConfigMigratesOnRead() {
        val cfg = Config(
            server = "1.2.3.4:443",
            token = "VIKDKKKK23K3KKJ4JK3",
            relay = RelayOptions(peerId = "peer-old"),
        )
        val out = Config.fromJson(cfg.toJson())
        assertTrue(out.mesh.enabled)
        assertTrue(out.mesh.volunteer.peerId == "peer-old")
    }

    @Test
    fun legacyRelayMigratesToMesh() {
        val raw = JSONObject(
            """
            {
              "server":"1.2.3.4:443",
              "token":"VIKDKKKK23K3KKJ4JK3",
              "relay":{
                "peerId":"peer-old",
                "peerPathFromDiscovery":true,
                "peerRelayUseUdp":true,
                "peerRelayUdpListen":"0.0.0.0:0",
                "turnUrls":["turn:legacy"]
              }
            }
            """.trimIndent(),
        )
        val cfg = Config.fromJson(raw)
        assertTrue(cfg.mesh.enabled)
        assertTrue(cfg.mesh.volunteer.enabled)
        assertTrue(cfg.mesh.p2p.enabled)
        assertTrue(cfg.mesh.p2p.useUdp)
        assertTrue(cfg.relay?.turnUrls?.firstOrNull() == "turn:legacy")
    }

    @Test
    fun carryOverOnlyRelayDoesNotEnableMesh() {
        val raw = JSONObject(
            """
            {
              "server":"1.2.3.4:443",
              "token":"VIKDKKKK23K3KKJ4JK3",
              "relay":{"turnUrls":["turn:legacy"],"emergencyPolicyURL":"https://e"}
            }
            """.trimIndent(),
        )
        val cfg = Config.fromJson(raw)
        assertFalse(cfg.mesh.enabled)
        assertTrue(cfg.relay?.turnUrls?.firstOrNull() == "turn:legacy")
        assertTrue(cfg.relay?.emergencyPolicyURL == "https://e")
    }

    @Test
    fun legacyPeerRelayUseTcpMigratesToMesh() {
        val raw = JSONObject(
            """
            {
              "server":"1.2.3.4:443",
              "token":"VIKDKKKK23K3KKJ4JK3",
              "relay":{"peerId":"p","peerPathFromDiscovery":true,"peerRelayUseTcp":false}
            }
            """.trimIndent(),
        )
        val cfg = Config.fromJson(raw)
        assertTrue(cfg.mesh.enabled)
        assertFalse(cfg.mesh.p2p.useTcp)
    }

    @Test
    fun legacyRelayMaxPeerHopsMigratesToMeshPolicy() {
        val raw = JSONObject(
            """
            {
              "server":"1.2.3.4:443",
              "token":"VIKDKKKK23K3KKJ4JK3",
              "relay":{"peerId":"p","peerPathFromDiscovery":true,"maxPeerHops":2,"healthMaxAgeSec":120}
            }
            """.trimIndent(),
        )
        val cfg = Config.fromJson(raw)
        assertTrue(cfg.mesh.enabled)
        assertTrue(cfg.mesh.policy.maxPeerHops == 2)
        assertTrue(cfg.mesh.policy.healthMaxAgeSec == 120)
    }

    @Test
    fun legacyRelayAbsentUdpFlagMigratesLikeGo() {
        val raw = JSONObject(
            """
            {
              "server":"1.2.3.4:443",
              "token":"VIKDKKKK23K3KKJ4JK3",
              "relay":{"peerId":"p","peerPathFromDiscovery":true}
            }
            """.trimIndent(),
        )
        val cfg = Config.fromJson(raw)
        assertTrue(cfg.mesh.enabled)
        assertFalse(cfg.mesh.p2p.useUdp)
    }

    @Test
    fun canonicalProfileKeepsCarryOverRelay() {
        val cfg = Config(
            server = "1.2.3.4:443",
            token = "VIKDKKKK23K3KKJ4JK3",
            mesh = MeshConfig(enabled = true),
            relay = RelayOptions(turnUrls = listOf("turn:legacy"), emergencyPolicyURL = "https://e"),
        )
        val out = Config.fromJson(cfg.canonicalProfile().toJson())
        assertTrue(out.mesh.enabled)
        assertTrue(out.relay?.turnUrls?.firstOrNull() == "turn:legacy")
        assertTrue(out.relay?.emergencyPolicyURL == "https://e")
    }

    @Test
    fun withMeshKeepingCarryOverDoesNotDropLegacyRelayFields() {
        val cfg = Config(
            server = "1.2.3.4:443",
            token = "VIKDKKKK23K3KKJ4JK3",
            mesh = MeshConfig(enabled = true),
            relay = RelayOptions(turnUrls = listOf("turn:legacy"), emergencyPolicyURL = "https://e"),
        )
        val next = cfg.withMeshKeepingCarryOver(cfg.mesh.copy(p2p = cfg.mesh.p2p.copy(enabled = true)))
        assertTrue(next.mesh.p2p.enabled)
        assertTrue(next.relay?.turnUrls?.firstOrNull() == "turn:legacy")
        assertTrue(next.relay?.emergencyPolicyURL == "https://e")
    }

    @Test
    fun disabledMeshProfileKeepsPreparedFields() {
        val cfg = Config(
            server = "1.2.3.4:443",
            token = "VIKDKKKK23K3KKJ4JK3",
            mesh = MeshConfig(
                enabled = false,
                volunteer = MeshVolunteerOptions(peerId = "peer-a", udpListen = "0.0.0.0:4001"),
                p2p = MeshP2POptions(enabled = true, useTcp = false),
                discovery = MeshDiscoveryOptions(dhtRpcSeedPeers = listOf("seed:4001")),
            ),
        )
        val out = Config.fromJson(cfg.toJson())
        assertFalse(out.mesh.enabled)
        assertTrue(out.mesh.volunteer.peerId == "peer-a")
        assertTrue(out.mesh.discovery.dhtRpcSeedPeers?.firstOrNull() == "seed:4001")
        assertFalse(out.mesh.p2p.useTcp)
    }
}
