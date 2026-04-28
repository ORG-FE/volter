package dev.c0redev.volter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.c0redev.volter.R
import dev.c0redev.volter.ui.ConnectionViewModel
import dev.c0redev.volter.ui.components.SectionCard
import dev.c0redev.volter.ui.protection.ProtectionEditor

@Composable
fun ProtectionScreen(vm: ConnectionViewModel, padding: PaddingValues) {
    val current = vm.globalProtection.collectAsState().value
    val metrics = vm.metrics.collectAsState().value.records

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
			ProtectionEditor(
				value = current,
				metrics = metrics,
				onSave = { vm.saveGlobalProtection(it) },
				onClear = { vm.saveGlobalProtection(null) },
			)
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
