package dev.c0redev.volter.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionOptionsShaperTest {

    @Test
    fun shaperFieldsRoundtripThroughJson() {
        val opts = ProtectionOptions(
            obfuscation = "enhanced",
            shaperEnabled = true,
            shaperProfile = "video",
            shaperMaxOverheadPct = 120,
            shaperMaxDelayMs = 60,
        )
        val back = ProtectionOptions.fromJson(opts.toJson())
        assertEquals(true, back.shaperEnabled)
        assertEquals("video", back.shaperProfile)
        assertEquals(120, back.shaperMaxOverheadPct)
        assertEquals(60, back.shaperMaxDelayMs)
    }

    @Test
    fun shaperDefaultsOmittedFromJson() {
        val opts = ProtectionOptions(obfuscation = "default")
        val j = opts.toJson()
        assertTrue("disabled shaper не должен сериализоваться", !j.has("shaperEnabled"))
        assertTrue(!j.has("shaperProfile"))
        assertTrue(!j.has("shaperMaxOverheadPct"))
        assertTrue(!j.has("shaperMaxDelayMs"))
        val back = ProtectionOptions.fromJson(j)
        assertEquals(false, back.shaperEnabled)
        assertEquals(null, back.shaperProfile)
        assertEquals(0, back.shaperMaxOverheadPct)
    }

    @Test
    fun presetsCarryShaperProfile() {
        assertEquals("web", ProtectionPresets.balanced().shaperProfile)
        assertEquals(true, ProtectionPresets.balanced().shaperEnabled)
        assertEquals("web", ProtectionPresets.strict().shaperProfile)
        assertEquals(80, ProtectionPresets.strict().shaperMaxOverheadPct)
        assertEquals(40, ProtectionPresets.strict().shaperMaxDelayMs)
    }
}
