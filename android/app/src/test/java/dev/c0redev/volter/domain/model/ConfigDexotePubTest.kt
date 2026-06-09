package dev.c0redev.volter.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigDexotePubTest {
    @Test
    fun dexoteServerPubRoundTripsThroughJson() {
        val cfg = Config(server = "1.2.3.4:443", token = "tok", dexoteServerPub = "AAECAwQFBgcICQoLDA0ODxA=")
        val back = Config.fromJson(cfg.toJson())
        assertEquals(cfg.dexoteServerPub, back.dexoteServerPub)
    }

    @Test
    fun toJsonOmitsBlankPub() {
        val cfg = Config(server = "1.2.3.4:443", token = "tok")
        assertTrue(!cfg.toJson().has("dexoteServerPub"))
        val back = Config.fromJson(cfg.toJson())
        assertNull(back.dexoteServerPub)
    }
}
