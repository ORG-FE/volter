package dev.c0redev.volter.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.c0redev.volter.R
import dev.c0redev.volter.domain.model.ProtectionOptions
import dev.c0redev.volter.domain.model.ProtectionPresets
import dev.c0redev.volter.ui.ConnectionViewModel
import dev.c0redev.volter.ui.components.SectionCard
import dev.c0redev.volter.ui.components.StyledTextField

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProtectionScreen(vm: ConnectionViewModel, padding: PaddingValues) {
    val current = vm.globalProtection.collectAsState().value
    val metrics = vm.metrics.collectAsState().value.records
    var obf by remember { mutableStateOf("") }
    var junkCount by remember { mutableStateOf("0") }
    var junkMin by remember { mutableStateOf("0") }
    var junkMax by remember { mutableStateOf("0") }
    var padS1 by remember { mutableStateOf("0") }
    var padS2 by remember { mutableStateOf("0") }
    var padS3 by remember { mutableStateOf("0") }
    var padS4 by remember { mutableStateOf("0") }
    var preCheck by remember { mutableStateOf(false) }
    var magicSplit by remember { mutableStateOf("") }
    var junkStyle by remember { mutableStateOf("") }
    var flushPolicy by remember { mutableStateOf("") }
    var preambleProfile by remember { mutableStateOf("") }
    var preambleRotate by remember { mutableStateOf(false) }

    LaunchedEffect(current) {
        obf = current?.obfuscation ?: ""
        junkCount = (current?.junkCount ?: 0).toString()
        junkMin = (current?.junkMin ?: 0).toString()
        junkMax = (current?.junkMax ?: 0).toString()
        padS1 = (current?.padS1 ?: 0).toString()
        padS2 = (current?.padS2 ?: 0).toString()
        padS3 = (current?.padS3 ?: 0).toString()
        padS4 = (current?.padS4 ?: 0).toString()
        preCheck = current?.preCheck ?: false
        magicSplit = current?.magicSplit ?: ""
        junkStyle = current?.junkStyle ?: ""
        flushPolicy = current?.flushPolicy ?: ""
        preambleProfile = current?.preambleProfile ?: ""
        preambleRotate = current?.preambleRotate ?: false
    }

    val applyPreset = { p: ProtectionOptions ->
        obf = p.obfuscation ?: ""
        junkCount = p.junkCount.toString()
        junkMin = p.junkMin.toString()
        junkMax = p.junkMax.toString()
        padS1 = p.padS1.toString()
        padS2 = p.padS2.toString()
        padS3 = p.padS3.toString()
        padS4 = p.padS4.toString()
        preCheck = p.preCheck
        magicSplit = p.magicSplit ?: ""
        junkStyle = p.junkStyle ?: ""
        flushPolicy = p.flushPolicy ?: ""
        preambleProfile = p.preambleProfile ?: ""
        preambleRotate = p.preambleRotate
    }

    val saveCurrent = {
        vm.saveGlobalProtection(
            ProtectionOptions(
                obfuscation = obf.takeIf { it.isNotBlank() },
                junkCount = junkCount.toIntOrNull() ?: 0,
                junkMin = junkMin.toIntOrNull() ?: 0,
                junkMax = junkMax.toIntOrNull() ?: 0,
                padS1 = padS1.toIntOrNull() ?: 0,
                padS2 = padS2.toIntOrNull() ?: 0,
                padS3 = padS3.toIntOrNull() ?: 0,
                padS4 = padS4.toIntOrNull() ?: 0,
                preCheck = preCheck,
                magicSplit = magicSplit.takeIf { it.isNotBlank() },
                junkStyle = junkStyle.takeIf { it.isNotBlank() },
                flushPolicy = flushPolicy.takeIf { it.isNotBlank() },
                preambleProfile = preambleProfile.takeIf { it.isNotBlank() },
                preambleRotate = preambleRotate,
            ),
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.protection_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Быстрые пресеты",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "1 тап, потом Сохранить. Или сразу Применить и сохранить.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { applyPreset(ProtectionPresets.balanced()) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Баланс")
                        }
                        FilledTonalButton(
                            onClick = { applyPreset(ProtectionPresets.strict()) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Усиленная")
                        }
                    }
                    FilledTonalButton(
                        onClick = { applyPreset(ProtectionPresets.suggestFromMetrics(metrics)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Авто по метрикам")
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                applyPreset(ProtectionPresets.balanced())
                                saveCurrent()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Баланс + сохранить") }
                        FilledTonalButton(
                            onClick = {
                                applyPreset(ProtectionPresets.strict())
                                saveCurrent()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Усиленная + сохранить") }
                    }
                }
            }
        }

        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Преамбула",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Выбери профиль маскировки. rotate для авто-смены паттерна.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("" to "Нет", "rotate" to "Rotate", "tls_record" to "TLS rec", "tls_ch_shape" to "TLS CH", "smb1_shape" to "SMB", "mc_frame" to "MC").forEach { (v, title) ->
                            FilledTonalButton(
                                onClick = { preambleProfile = v },
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                val selected = if (preambleProfile == v) "✓ " else ""
                                Text(selected + title)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Rotate с enhanced",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Switch(checked = preambleRotate, onCheckedChange = { preambleRotate = it })
                    }
                }
            }
        }

        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Тонкая настройка",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StyledTextField(
                        value = obf,
                        onValueChange = { obf = it },
                        label = "obfuscation (default/enhanced)",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StyledTextField(
                            value = padS1,
                            onValueChange = { padS1 = digitsOnly(it) },
                            label = "padS1",
                            modifier = Modifier.weight(1f),
                        )
                        StyledTextField(
                            value = padS2,
                            onValueChange = { padS2 = digitsOnly(it) },
                            label = "padS2",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StyledTextField(
                            value = padS3,
                            onValueChange = { padS3 = digitsOnly(it) },
                            label = "padS3",
                            modifier = Modifier.weight(1f),
                        )
                        StyledTextField(
                            value = padS4,
                            onValueChange = { padS4 = digitsOnly(it) },
                            label = "padS4",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StyledTextField(
                            value = junkCount,
                            onValueChange = { junkCount = digitsOnly(it) },
                            label = "junkCount",
                            modifier = Modifier.weight(1f),
                        )
                        StyledTextField(
                            value = junkMin,
                            onValueChange = { junkMin = digitsOnly(it) },
                            label = "junkMin",
                            modifier = Modifier.weight(1f),
                        )
                        StyledTextField(
                            value = junkMax,
                            onValueChange = { junkMax = digitsOnly(it) },
                            label = "junkMax",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    StyledTextField(
                        value = magicSplit,
                        onValueChange = { magicSplit = it },
                        label = "magicSplit",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StyledTextField(
                        value = junkStyle,
                        onValueChange = { junkStyle = it },
                        label = "junkStyle",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StyledTextField(
                        value = flushPolicy,
                        onValueChange = { flushPolicy = it },
                        label = "flushPolicy",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "preCheck",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Switch(checked = preCheck, onCheckedChange = { preCheck = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = saveCurrent,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Сохранить")
                        }
                        FilledTonalButton(
                            onClick = { vm.saveGlobalProtection(null) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Очистить")
                        }
                    }
                }
            }
        }

        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Последние сессии",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val tail = metrics.takeLast(8).asReversed()
                    if (tail.isEmpty()) {
                        Text("Нет данных", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        tail.forEach { r ->
                            Text(
                                text = "${r.configName}: hs=${r.handshakeOk}, err=${r.errorType ?: "-"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun digitsOnly(v: String): String {
    if (v.isEmpty()) return v
    val out = StringBuilder(v.length)
    for (ch in v) {
        if (ch in '0'..'9') {
            out.append(ch)
        }
    }
    return out.toString()
}
