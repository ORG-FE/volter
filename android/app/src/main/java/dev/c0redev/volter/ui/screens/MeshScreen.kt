package dev.c0redev.volter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.c0redev.volter.R
import dev.c0redev.volter.core.CoreBridge
import dev.c0redev.volter.domain.model.RelayOptions
import dev.c0redev.volter.domain.model.VolterMeshDefaults
import dev.c0redev.volter.ui.ConnectionViewModel
import dev.c0redev.volter.ui.mesh.MeshRelayEditor
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun MeshScreen(vm: ConnectionViewModel, contentPadding: PaddingValues) {
    val local by vm.localConfigs.collectAsState()
    var selected by remember { mutableStateOf(local.firstOrNull()?.name.orEmpty()) }
    LaunchedEffect(local.map { it.name }) {
        if (selected.isNotEmpty() && local.none { it.name == selected }) {
            selected = local.firstOrNull()?.name.orEmpty()
        }
        if (selected.isEmpty() && local.isNotEmpty()) {
            selected = local.first().name
        }
    }
    val item = local.find { it.name == selected }
    var draft by remember { mutableStateOf(RelayOptions()) }
    LaunchedEffect(item) {
        draft = item?.let { VolterMeshDefaults.relayForEditor(it.config.server, it.config.relay) }
            ?: RelayOptions()
    }

    var body by remember { mutableStateOf("{}") }
    LaunchedEffect(Unit) {
        while (true) {
            body = prettyMeshJson(CoreBridge.meshStatus())
            delay(2000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.mesh_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            text = stringResource(R.string.mesh_profile_label),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            items(local, key = { it.name }) { it ->
                FilterChip(
                    selected = selected == it.name,
                    onClick = { selected = it.name },
                    label = { Text(it.name) },
                )
            }
        }
        if (item != null) {
            MeshRelayEditor(
                relay = draft,
                onRelayChange = { draft = it },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            FilledTonalButton(
                onClick = {
                    vm.upsertLocalConfig(selected, item.config.copy(relay = draft))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(stringResource(R.string.mesh_save))
            }
        } else {
            Text(
                text = "Add a local profile on Home / Profiles first.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringResource(R.string.mesh_status_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private fun prettyMeshJson(raw: String): String {
    return try {
        JSONObject(raw).toString(2)
    } catch (_: Exception) {
        raw
    }
}
