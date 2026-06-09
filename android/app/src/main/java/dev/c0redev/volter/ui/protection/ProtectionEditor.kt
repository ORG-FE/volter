package dev.c0redev.volter.ui.protection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import dev.c0redev.volter.R
import dev.c0redev.volter.domain.model.DpiLocalEmbedded
import dev.c0redev.volter.domain.model.ProtectionOptions
import dev.c0redev.volter.domain.model.ProtectionPresets
import dev.c0redev.volter.domain.model.SessionRecord
import dev.c0redev.volter.theme.VolterSpacing
import dev.c0redev.volter.ui.components.SectionCard
import dev.c0redev.volter.ui.components.StyledTextField

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProtectionEditor(
    value: ProtectionOptions?,
    metrics: List<SessionRecord>,
    modifier: Modifier = Modifier,
    showActions: Boolean = true,
    onSave: (ProtectionOptions) -> Unit,
    onClear: (() -> Unit)? = null,
    onChange: ((ProtectionOptions) -> Unit)? = null,
) {
    var draft by remember { mutableStateOf(ProtectionDraft.from(value)) }

    LaunchedEffect(value) {
        draft = ProtectionDraft.from(value)
    }

    fun set(next: ProtectionDraft) {
        draft = next.clean()
        onChange?.invoke(draft.toOptions(value))
    }

    fun applyPreset(p: ProtectionOptions) {
        val base = value ?: ProtectionOptions()
        set(ProtectionDraft.from(mergeQuickPreset(base, p)))
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(VolterSpacing.sectionGap)) {
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(VolterSpacing.sectionGap)) {
                Text(stringResource(R.string.protection_section_quick_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.protection_section_quick_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(
                        onClick = { applyPreset(ProtectionPresets.balanced()) },
                        modifier = Modifier.weight(1f),
                        shape = RectangleShape,
                    ) { Text(stringResource(R.string.protection_preset_balance)) }
                    FilledTonalButton(
                        onClick = { applyPreset(ProtectionPresets.strict()) },
                        modifier = Modifier.weight(1f),
                        shape = RectangleShape,
                    ) { Text(stringResource(R.string.protection_preset_strong)) }
                }
                FilledTonalButton(
                    onClick = { applyPreset(ProtectionPresets.suggestFromMetrics(metrics)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RectangleShape,
                ) { Text(stringResource(R.string.protection_preset_auto)) }
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(VolterSpacing.sectionGap)) {
                Text(stringResource(R.string.protection_section_main), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Segmented(
                    title = stringResource(R.string.protection_obfuscation),
                    options = listOf(
                        "default" to stringResource(R.string.protection_obfuscation_standard),
                        "enhanced" to stringResource(R.string.protection_obfuscation_enhanced),
                    ),
                    selected = draft.obfuscation,
                    onSelect = { set(draft.copy(obfuscation = it)) },
                )
                Segmented(
                    title = stringResource(R.string.protection_preamble),
                    options = listOf(
                        "" to stringResource(R.string.protection_preamble_none),
                        "rotate" to stringResource(R.string.protection_preamble_rotate),
                        "tls_record" to stringResource(R.string.protection_preamble_tls_rec),
                        "tls_ch_shape" to stringResource(R.string.protection_preamble_tls_ch),
                        "smb1_shape" to stringResource(R.string.protection_preamble_smb),
                        "mc_frame" to stringResource(R.string.protection_preamble_mc),
                    ),
                    selected = draft.preambleProfile,
                    onSelect = { set(draft.copy(preambleProfile = it, preambleRotate = it == "rotate")) },
                )
                ToggleRow(
                    title = stringResource(R.string.protection_rotate_with_enhanced),
                    checked = draft.preambleRotate,
                    onCheckedChange = { set(draft.copy(preambleRotate = it)) },
                )
                Segmented(
                    title = stringResource(R.string.protection_junk_style),
                    options = listOf(
                        "random" to stringResource(R.string.protection_junk_random),
                        "tls" to stringResource(R.string.protection_junk_tls),
                    ),
                    selected = draft.junkStyle,
                    onSelect = { set(draft.copy(junkStyle = it)) },
                )
                Segmented(
                    title = stringResource(R.string.protection_flush),
                    options = listOf(
                        "once" to stringResource(R.string.protection_flush_once),
                        "perChunk" to stringResource(R.string.protection_flush_per_chunk),
                    ),
                    selected = draft.flushPolicy,
                    onSelect = { set(draft.copy(flushPolicy = it)) },
                )
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(VolterSpacing.sectionGap)) {
                Text(stringResource(R.string.protection_shaper_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.protection_shaper_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ToggleRow(
                    title = stringResource(R.string.protection_shaper_enabled),
                    checked = draft.shaperEnabled,
                    onCheckedChange = { set(draft.copy(shaperEnabled = it)) },
                )
                if (draft.shaperEnabled) {
                    Segmented(
                        title = stringResource(R.string.protection_shaper_profile),
                        options = listOf(
                            "web" to stringResource(R.string.protection_shaper_profile_web),
                            "video" to stringResource(R.string.protection_shaper_profile_video),
                            "game" to stringResource(R.string.protection_shaper_profile_game),
                            "bulk" to stringResource(R.string.protection_shaper_profile_bulk),
                        ),
                        selected = draft.shaperProfile,
                        onSelect = { set(draft.copy(shaperProfile = it)) },
                    )
                    NumberRow(
                        items = listOf(
                            NumberItem(R.string.protection_shaper_overhead, draft.shaperOverhead, 0, 300) {
                                set(draft.copy(shaperOverhead = it))
                            },
                            NumberItem(R.string.protection_shaper_delay, draft.shaperDelay, 0, 1000) {
                                set(draft.copy(shaperDelay = it))
                            },
                        ),
                    )
                }
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(VolterSpacing.sectionGap)) {
                Text(stringResource(R.string.protection_limits_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                NumberRow(
                    items = listOf(
                        NumberItem(R.string.protection_num_junk_count, draft.junkCount, 0, 12) { set(draft.copy(junkCount = it)) },
                        NumberItem(R.string.protection_num_junk_min, draft.junkMin, 64, 1024) { set(draft.copy(junkMin = it)) },
                        NumberItem(R.string.protection_num_junk_max, draft.junkMax, 64, 2048) { set(draft.copy(junkMax = it)) },
                    ),
                )
                NumberRow(
                    items = listOf(
                        NumberItem(R.string.protection_num_pad_s1, draft.padS1, 0, 64) { set(draft.copy(padS1 = it)) },
                        NumberItem(R.string.protection_num_pad_s2, draft.padS2, 0, 64) { set(draft.copy(padS2 = it)) },
                    ),
                )
                NumberRow(
                    items = listOf(
                        NumberItem(R.string.protection_num_pad_s3, draft.padS3, 0, 64) { set(draft.copy(padS3 = it)) },
                        NumberItem(R.string.protection_num_pad_s4, draft.padS4, 0, 64) { set(draft.copy(padS4 = it)) },
                    ),
                )
                StyledTextField(
                    value = draft.magicSplit,
                    onValueChange = { set(draft.copy(magicSplit = digitsOnly(it).take(3))) },
                    label = stringResource(R.string.protection_magic_split_label),
                    modifier = Modifier.fillMaxWidth(),
                )
                ToggleRow(
                    title = stringResource(R.string.protection_precheck),
                    checked = draft.preCheck,
                    onCheckedChange = { set(draft.copy(preCheck = it)) },
                )
                Text(stringResource(R.string.protection_antidpi_section_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.protection_antidpi_section_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ToggleRow(
                    title = stringResource(R.string.protection_standalone_dpi_only),
                    checked = draft.standaloneDpiOnly,
                    onCheckedChange = { set(draft.copy(standaloneDpiOnly = it)) },
                )
                Segmented(
                    title = stringResource(R.string.protection_dpi_engine_title),
                    options = listOf(
                        false to stringResource(R.string.protection_dpi_engine_embedded),
                        true to stringResource(R.string.protection_dpi_engine_external),
                    ),
                    selected = draft.engineExternal,
                    onSelect = { set(draft.copy(engineExternal = it)) },
                )
                if (!draft.engineExternal) {
                    NumberRow(
                        items = listOf(
                            NumberItem(R.string.protection_dpi_split_after, draft.dpiSplitAfter, 1, 65536) {
                                set(draft.copy(dpiSplitAfter = it))
                            },
                            NumberItem(R.string.protection_dpi_ttl_ms, draft.dpiTtlMillis, 1, 60_000) {
                                set(draft.copy(dpiTtlMillis = it))
                            },
                        ),
                    )
                    NumberRow(
                        items = listOf(
                            NumberItem(R.string.protection_dpi_split_after2, draft.dpiSplitAfter2, 0, 65536) {
                                set(draft.copy(dpiSplitAfter2 = it))
                            },
                            NumberItem(R.string.protection_dpi_ttl2_ms, draft.dpiTtl2Millis, 0, 60_000) {
                                set(draft.copy(dpiTtl2Millis = it))
                            },
                        ),
                    )
                    NumberRow(
                        items = listOf(
                            NumberItem(R.string.protection_dpi_jitter_ms, draft.dpiJitterMaxMs, 0, 5000) {
                                set(draft.copy(dpiJitterMaxMs = it))
                            },
                            NumberItem(R.string.protection_dpi_lead_in_ms, draft.dpiLeadInMs, 0, 60_000) {
                                set(draft.copy(dpiLeadInMs = it))
                            },
                        ),
                    )
                    ToggleRow(
                        title = stringResource(R.string.protection_dpi_disorder),
                        checked = draft.dpiDisorder,
                        onCheckedChange = { set(draft.copy(dpiDisorder = it)) },
                    )
                    ToggleRow(
                        title = stringResource(R.string.protection_dpi_fake_sni),
                        checked = draft.dpiFakeSni,
                        onCheckedChange = { set(draft.copy(dpiFakeSni = it)) },
                    )
                    if (draft.dpiFakeSni) {
                        StyledTextField(
                            value = draft.dpiFakeSniHost,
                            onValueChange = { set(draft.copy(dpiFakeSniHost = it)) },
                            label = stringResource(R.string.protection_dpi_fake_sni_host),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    StyledTextField(
                        value = draft.dpiSplitPosition,
                        onValueChange = { set(draft.copy(dpiSplitPosition = it)) },
                        label = stringResource(R.string.protection_dpi_split_position),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ToggleRow(
                        title = stringResource(R.string.protection_dpi_auto_ttl),
                        checked = draft.dpiAutoTtl,
                        onCheckedChange = { set(draft.copy(dpiAutoTtl = it)) },
                    )
                    NumberRow(
                        items = listOf(
                            NumberItem(R.string.protection_dpi_tcp_segment, draft.dpiTcpSegment, 0, 65536) {
                                set(draft.copy(dpiTcpSegment = it))
                            },
                            NumberItem(R.string.protection_dpi_multi_split, draft.dpiMultiSplit, 0, 10) {
                                set(draft.copy(dpiMultiSplit = it))
                            },
                        ),
                    )
                    ToggleRow(
                        title = stringResource(R.string.protection_dpi_oob_data),
                        checked = draft.dpiOobData,
                        onCheckedChange = { set(draft.copy(dpiOobData = it)) },
                    )
                }
                StyledTextField(
                    value = draft.dpiLocalPreset,
                    onValueChange = { set(draft.copy(dpiLocalPreset = it)) },
                    label = stringResource(R.string.protection_dpi_local_preset_label),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showActions) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { onSave(draft.toOptions(value)) },
                            modifier = Modifier.weight(1f),
                            shape = RectangleShape,
                        ) { Text(stringResource(R.string.protection_save)) }
                        if (onClear != null) {
                            OutlinedButton(
                                onClick = onClear,
                                modifier = Modifier.weight(1f),
                                shape = RectangleShape,
                            ) { Text(stringResource(R.string.protection_clear)) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Segmented(title: String, options: List<Pair<Boolean, String>>, selected: Boolean, onSelect: (Boolean) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                shape = RectangleShape,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Segmented(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                shape = RectangleShape,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NumberRow(items: List<NumberItem>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items.forEach { item ->
            StyledTextField(
                value = item.value.toString(),
                onValueChange = { item.onChange((it.toIntOrNull() ?: 0).coerceIn(item.min, item.max)) },
                label = stringResource(R.string.protection_param_fmt, stringResource(item.labelRes), item.min, item.max),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private data class NumberItem(
    @StringRes val labelRes: Int,
    val value: Int,
    val min: Int,
    val max: Int,
    val onChange: (Int) -> Unit,
)

private data class ProtectionDraft(
    val obfuscation: String = "default",
    val junkCount: Int = 0,
    val junkMin: Int = 64,
    val junkMax: Int = 512,
    val padS1: Int = 0,
    val padS2: Int = 0,
    val padS3: Int = 0,
    val padS4: Int = 0,
    val preCheck: Boolean = false,
    val magicSplit: String = "",
    val junkStyle: String = "random",
    val flushPolicy: String = "once",
    val preambleProfile: String = "",
    val preambleRotate: Boolean = false,
    val standaloneDpiOnly: Boolean = false,
    val engineExternal: Boolean = false,
    val dpiSplitAfter: Int = 1,
    val dpiSplitAfter2: Int = 0,
    val dpiTtlMillis: Int = 8,
    val dpiTtl2Millis: Int = 0,
    val dpiDisorder: Boolean = false,
    val dpiJitterMaxMs: Int = 0,
    val dpiLeadInMs: Int = 0,
    val dpiFakeSni: Boolean = false,
    val dpiFakeSniHost: String = "",
    val dpiSplitPosition: String = "",
    val dpiAutoTtl: Boolean = false,
    val dpiTcpSegment: Int = 0,
    val dpiOobData: Boolean = false,
    val dpiMultiSplit: Int = 0,
    val dpiLocalPreset: String = "",
    val shaperEnabled: Boolean = false,
    val shaperProfile: String = "web",
    val shaperOverhead: Int = 0,
    val shaperDelay: Int = 0,
) {
    fun clean(): ProtectionDraft {
        val jc = junkCount.coerceIn(0, 12)
        val jmin = junkMin.coerceIn(64, 1024)
        val jmax = junkMax.coerceIn(jmin, 2048)
        return copy(
            obfuscation = if (obfuscation == "enhanced") "enhanced" else "default",
            junkCount = jc,
            junkMin = jmin,
            junkMax = jmax,
            padS1 = padS1.coerceIn(0, 64),
            padS2 = padS2.coerceIn(0, 64),
            padS3 = padS3.coerceIn(0, 64),
            padS4 = padS4.coerceIn(0, 64),
            junkStyle = if (junkStyle == "tls") "tls" else "random",
            flushPolicy = if (flushPolicy == "perChunk") "perChunk" else "once",
            preambleProfile = when (preambleProfile) {
                "rotate", "tls_record", "tls_ch_shape", "smb1_shape", "mc_frame" -> preambleProfile
                else -> ""
            },
            dpiSplitAfter = dpiSplitAfter.coerceIn(1, 65536),
            dpiSplitAfter2 = dpiSplitAfter2.coerceIn(0, 65536),
            dpiTtlMillis = dpiTtlMillis.coerceIn(1, 60_000),
            dpiTtl2Millis = dpiTtl2Millis.coerceIn(0, 60_000),
            dpiJitterMaxMs = dpiJitterMaxMs.coerceIn(0, 5000),
            dpiLeadInMs = dpiLeadInMs.coerceIn(0, 60_000),
            dpiTcpSegment = dpiTcpSegment.coerceIn(0, 65536),
            dpiMultiSplit = dpiMultiSplit.coerceIn(0, 10),
            shaperProfile = when (shaperProfile) {
                "web", "video", "game", "bulk" -> shaperProfile
                else -> "web"
            },
            shaperOverhead = shaperOverhead.coerceIn(0, 300),
            shaperDelay = shaperDelay.coerceIn(0, 1000),
        )
    }

    fun toOptions(base: ProtectionOptions?): ProtectionOptions {
        val b = base ?: ProtectionOptions()
        val d = clean()
        val emb = DpiLocalEmbedded(
            splitAfter = d.dpiSplitAfter,
            splitAfter2 = d.dpiSplitAfter2,
            ttlMillis = d.dpiTtlMillis,
            ttl2Millis = d.dpiTtl2Millis,
            disorder = d.dpiDisorder,
            jitterMaxMs = d.dpiJitterMaxMs,
            leadInMs = d.dpiLeadInMs,
            fakeSni = d.dpiFakeSni,
            fakeSniHost = d.dpiFakeSniHost,
            splitPosition = d.dpiSplitPosition,
            autoTtl = d.dpiAutoTtl,
            tcpSegment = d.dpiTcpSegment,
            oobData = d.dpiOobData,
            multiSplit = d.dpiMultiSplit,
        )
        val embDefault =
            emb.splitAfter == 1 &&
                emb.splitAfter2 == 0 &&
                emb.ttlMillis == 8 &&
                emb.ttl2Millis == 0 &&
                !emb.disorder &&
                emb.jitterMaxMs == 0 &&
                emb.leadInMs == 0 &&
                !emb.fakeSni &&
                emb.fakeSniHost.isBlank() &&
                emb.splitPosition.isBlank() &&
                !emb.autoTtl &&
                emb.tcpSegment == 0 &&
                !emb.oobData &&
                emb.multiSplit == 0
        val embOut = if (embDefault) null else emb
        val engine =
            if (d.engineExternal) "external" else "embedded"
        return b.copy(
            obfuscation = d.obfuscation,
            junkCount = d.junkCount,
            junkMin = d.junkMin,
            junkMax = d.junkMax,
            padS1 = d.padS1,
            padS2 = d.padS2,
            padS3 = d.padS3,
            padS4 = d.padS4,
            preCheck = d.preCheck,
            magicSplit = d.magicSplit.takeIf { s -> s.isNotBlank() && s != "0" },
            junkStyle = d.junkStyle,
            flushPolicy = d.flushPolicy,
            preambleProfile = d.preambleProfile.takeIf { s -> s.isNotBlank() },
            preambleRotate = d.preambleRotate,
            standaloneDpiOnly = d.standaloneDpiOnly,
            dpiLocalEngine = engine,
            dpiLocalEmbedded = embOut,
            dpiLocalPreset = d.dpiLocalPreset.trim().takeIf { it.isNotEmpty() },
            shaperEnabled = d.shaperEnabled,
            shaperProfile = if (d.shaperEnabled) d.shaperProfile else null,
            shaperMaxOverheadPct = d.shaperOverhead,
            shaperMaxDelayMs = d.shaperDelay,
        )
    }

    companion object {
        fun from(p: ProtectionOptions?): ProtectionDraft = ProtectionDraft(
            obfuscation = p?.obfuscation ?: "default",
            junkCount = p?.junkCount ?: 0,
            junkMin = p?.junkMin ?: 64,
            junkMax = p?.junkMax ?: 512,
            padS1 = p?.padS1 ?: 0,
            padS2 = p?.padS2 ?: 0,
            padS3 = p?.padS3 ?: 0,
            padS4 = p?.padS4 ?: 0,
            preCheck = p?.preCheck ?: false,
            magicSplit = p?.magicSplit ?: "",
            junkStyle = p?.junkStyle ?: "random",
            flushPolicy = p?.flushPolicy ?: "once",
            preambleProfile = p?.preambleProfile ?: "",
            preambleRotate = p?.preambleRotate ?: false,
            standaloneDpiOnly = p?.standaloneDpiOnly ?: false,
            engineExternal = when {
                p?.dpiLocalEngine.equals("external", ignoreCase = true) == true -> true
                p?.dpiLocalEngine.equals("embedded", ignoreCase = true) == true -> false
                !p?.dpiLocalPreset.isNullOrBlank() -> true
                else -> false
            },
            dpiSplitAfter = p?.dpiLocalEmbedded?.splitAfter ?: 1,
            dpiSplitAfter2 = p?.dpiLocalEmbedded?.splitAfter2 ?: 0,
            dpiTtlMillis = p?.dpiLocalEmbedded?.ttlMillis ?: 8,
            dpiTtl2Millis = p?.dpiLocalEmbedded?.ttl2Millis ?: 0,
            dpiDisorder = p?.dpiLocalEmbedded?.disorder ?: false,
            dpiJitterMaxMs = p?.dpiLocalEmbedded?.jitterMaxMs ?: 0,
            dpiLeadInMs = p?.dpiLocalEmbedded?.leadInMs ?: 0,
            dpiFakeSni = p?.dpiLocalEmbedded?.fakeSni ?: false,
            dpiFakeSniHost = p?.dpiLocalEmbedded?.fakeSniHost ?: "",
            dpiSplitPosition = p?.dpiLocalEmbedded?.splitPosition ?: "",
            dpiAutoTtl = p?.dpiLocalEmbedded?.autoTtl ?: false,
            dpiTcpSegment = p?.dpiLocalEmbedded?.tcpSegment ?: 0,
            dpiOobData = p?.dpiLocalEmbedded?.oobData ?: false,
            dpiMultiSplit = p?.dpiLocalEmbedded?.multiSplit ?: 0,
            dpiLocalPreset = p?.dpiLocalPreset ?: "",
            shaperEnabled = p?.shaperEnabled ?: false,
            shaperProfile = p?.shaperProfile?.ifBlank { null } ?: "web",
            shaperOverhead = p?.shaperMaxOverheadPct ?: 0,
            shaperDelay = p?.shaperMaxDelayMs ?: 0,
        ).clean()
    }
}

private fun mergeQuickPreset(base: ProtectionOptions, p: ProtectionOptions): ProtectionOptions {
    val strictLike = p.obfuscation == "enhanced"
    return base.copy(
        obfuscation = p.obfuscation ?: base.obfuscation,
        junkCount = p.junkCount,
        junkMin = p.junkMin,
        junkMax = p.junkMax,
        padS1 = p.padS1,
        padS2 = p.padS2,
        padS3 = p.padS3,
        padS4 = p.padS4,
        preCheck = p.preCheck,
        magicSplit = p.magicSplit ?: base.magicSplit,
        junkStyle = p.junkStyle ?: base.junkStyle,
        flushPolicy = p.flushPolicy ?: base.flushPolicy,
        preambleProfile = if (strictLike) p.preambleProfile else (p.preambleProfile ?: base.preambleProfile),
        preambleRotate = if (strictLike) p.preambleRotate else base.preambleRotate,
        shaperEnabled = p.shaperEnabled,
        shaperProfile = p.shaperProfile ?: base.shaperProfile,
        shaperMaxOverheadPct = p.shaperMaxOverheadPct,
        shaperMaxDelayMs = p.shaperMaxDelayMs,
    )
}

private fun digitsOnly(v: String): String {
    val out = StringBuilder(v.length)
    for (ch in v) if (ch in '0'..'9') out.append(ch)
    return out.toString()
}
