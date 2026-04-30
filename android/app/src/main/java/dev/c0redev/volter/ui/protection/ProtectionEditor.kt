package dev.c0redev.volter.ui.protection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.c0redev.volter.domain.model.ProtectionOptions
import dev.c0redev.volter.domain.model.ProtectionPresets
import dev.c0redev.volter.domain.model.SessionRecord
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
        onChange?.invoke(draft.toOptions())
    }

    fun applyPreset(p: ProtectionOptions) {
        set(ProtectionDraft.from(p))
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(stringResource(R.string.protection_preset_balance)) }
                    FilledTonalButton(
                        onClick = { applyPreset(ProtectionPresets.strict()) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(stringResource(R.string.protection_preset_strong)) }
                }
                FilledTonalButton(
                    onClick = { applyPreset(ProtectionPresets.suggestFromMetrics(metrics)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.protection_preset_auto)) }
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                if (showActions) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { onSave(draft.toOptions()) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text(stringResource(R.string.protection_save)) }
                        if (onClear != null) {
                            OutlinedButton(
                                onClick = onClear,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
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
private fun Segmented(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
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
        )
    }

    fun toOptions(): ProtectionOptions = clean().let {
        ProtectionOptions(
            obfuscation = it.obfuscation,
            junkCount = it.junkCount,
            junkMin = it.junkMin,
            junkMax = it.junkMax,
            padS1 = it.padS1,
            padS2 = it.padS2,
            padS3 = it.padS3,
            padS4 = it.padS4,
            preCheck = it.preCheck,
            magicSplit = it.magicSplit.takeIf { s -> s.isNotBlank() && s != "0" },
            junkStyle = it.junkStyle,
            flushPolicy = it.flushPolicy,
            preambleProfile = it.preambleProfile.takeIf { s -> s.isNotBlank() },
            preambleRotate = it.preambleRotate,
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
        ).clean()
    }
}

private fun digitsOnly(v: String): String {
    val out = StringBuilder(v.length)
    for (ch in v) if (ch in '0'..'9') out.append(ch)
    return out.toString()
}
