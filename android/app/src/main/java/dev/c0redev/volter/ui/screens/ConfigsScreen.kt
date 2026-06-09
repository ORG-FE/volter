package dev.c0redev.volter.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.c0redev.volter.R
import dev.c0redev.volter.domain.model.Config
import dev.c0redev.volter.theme.VolterSpacing
import dev.c0redev.volter.ui.ConfigItemState
import dev.c0redev.volter.ui.ConnectionViewModel
import dev.c0redev.volter.ui.components.ConfigProfileCard
import dev.c0redev.volter.ui.components.StyledTextField
import dev.c0redev.volter.ui.components.VolterGlassDialogDefaults
import dev.c0redev.volter.ui.qr.buildQrBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigsScreen(vm: ConnectionViewModel, padding: PaddingValues) {
    val ctx = LocalContext.current
    val localItems = vm.localConfigs.collectAsState().value
    val cloudItems = vm.cloudConfigs.collectAsState().value
    val cloudLoading = vm.cloudLoading.collectAsState().value
    val localRefreshing = vm.localRefreshing.collectAsState().value
    val localInitialLoad = vm.localConfigsInitialLoad.collectAsState().value
    val localShowWait = (localInitialLoad || localRefreshing) && localItems.isEmpty()
    val cloudProgress = vm.cloudRefreshProgress.collectAsState().value
    val connectingName = vm.connectingProfileName.collectAsState().value
    val conn = vm.connection.collectAsState().value
    val activeProfileName = vm.activeProfileName.collectAsState().value
    var tabIndex by remember { mutableIntStateOf(0) }

    var editorOpen by remember { mutableStateOf(false) }
    var editorOldName by remember { mutableStateOf<String?>(null) }
    var editorCfg by remember { mutableStateOf<Config?>(null) }
    var importTarget by remember { mutableStateOf<ConfigItemState?>(null) }
    var deleteConfirmName by remember { mutableStateOf<String?>(null) }
    var shareTarget by remember { mutableStateOf<Pair<String, Config>?>(null) }

    val scheme = MaterialTheme.colorScheme
    val onHero = scheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VolterSpacing.screenHorizontal, vertical = VolterSpacing.screenVertical + 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.configs_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = onHero,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = activeProfileName?.let { stringResource(R.string.configs_hero_active_fmt, it) }
                        ?: stringResource(R.string.configs_hero_no_profile),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.Medium,
                )
                ConfigSourceSegment(
                    modifier = Modifier.fillMaxWidth(),
                    selectedIndex = tabIndex,
                    onSelect = { tabIndex = it },
                    localLabel = stringResource(R.string.configs_tab_local),
                    cloudLabel = stringResource(R.string.configs_tab_cloud),
                    onDarkBackground = false,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = VolterSpacing.screenHorizontal, vertical = VolterSpacing.screenVertical),
        ) {
        Text(
            text = stringResource(R.string.configs_screen_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    editorOldName = null
                    editorCfg = null
                    editorOpen = true
                },
                shape = RectangleShape,
            ) {
                Text(stringResource(R.string.configs_add))
            }
            if (conn.connected) {
                FilledTonalButton(
                    onClick = { vm.disconnect() },
                    shape = RectangleShape,
                ) {
                    Text(stringResource(R.string.home_disconnect))
                }
            }
        }

        when (tabIndex) {
            0 -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(VolterSpacing.sectionGap),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.refreshLocalConfigs() },
                            enabled = !localRefreshing,
                            shape = RectangleShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.configs_local_refresh))
                        }
                        if (localRefreshing) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                stringResource(R.string.configs_local_refreshing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (localShowWait) {
                    item {
                        Text(
                            stringResource(R.string.configs_local_wait),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(3, key = { "local_skel_$it" }) {
                        LocalProfileSkeleton()
                    }
                }
                if (localItems.isEmpty() && !localShowWait) {
                    item {
                        Text(
                            text = stringResource(R.string.configs_empty_local),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 24.dp),
                        )
                    }
                }
                items(localItems, key = { it.name }) { it ->
                    val profileActive = conn.connected && activeProfileName == it.name
                    ConfigProfileCard(
                        item = it,
                        isActive = profileActive,
                        primaryBusy = connectingName == it.name,
                        primaryBusyLabel = stringResource(R.string.config_connecting),
                        primaryLabel = stringResource(R.string.configs_connect),
                        onPrimary = { vm.connect(it.name, it.config) },
                        onEdit = {
                            editorOldName = it.name
                            editorCfg = it.config
                            editorOpen = true
                        },
                        onDelete = { deleteConfirmName = it.name },
                        onShare = { shareTarget = it.name to it.config },
                    )
                }
            }
            1 -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(VolterSpacing.sectionGap),
            ) {
                item {
                    Button(
                        onClick = { vm.refreshCloudConfigs(true) },
                        enabled = !cloudLoading,
                        shape = RectangleShape,
                    ) {
                        Text(stringResource(R.string.configs_cloud_refresh))
                    }
                }
                if (cloudLoading) {
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (cloudProgress > 0f) {
                                LinearProgressIndicator(
                                    progress = { cloudProgress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Text(
                                stringResource(R.string.configs_cloud_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (!cloudLoading && cloudItems.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.configs_empty_cloud),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                items(cloudItems, key = { it.name }) { item ->
                    val profileActive = conn.connected && activeProfileName == item.name
                    ConfigProfileCard(
                        item = item,
                        isActive = profileActive,
                        primaryBusy = connectingName == item.name,
                        primaryBusyLabel = stringResource(R.string.config_connecting),
                        primaryLabel = stringResource(R.string.configs_cloud_connect),
                        onPrimary = {
                            vm.connect(
                                item.name,
                                item.config,
                                applyCloudDefaults = true,
                                cloudServerMode = item.serverMode,
                                cloudProbeIpv6 = item.ipv6Support,
                            )
                        },
                        onEdit = null,
                        onDelete = null,
                        onImport = { importTarget = item },
                        importLabel = stringResource(R.string.configs_import_local),
                    )
                }
            }
        }
        }
    }

    val deleteName = deleteConfirmName
    if (deleteName != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmName = null },
            shape = VolterGlassDialogDefaults.shape(),
            containerColor = VolterGlassDialogDefaults.containerColor(),
            tonalElevation = VolterGlassDialogDefaults.tonalElevation,
            title = { Text(stringResource(R.string.configs_delete_title)) },
            text = { Text(stringResource(R.string.configs_delete_message, deleteName)) },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteLocalConfig(deleteName)
                        deleteConfirmName = null
                    },
                ) {
                    Text(stringResource(R.string.configs_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmName = null }) {
                    Text(stringResource(R.string.configs_import_cancel))
                }
            },
        )
    }

    val importTargetVal = importTarget
    if (importTargetVal != null) {
        var importName by remember(importTargetVal.name) { mutableStateOf(importTargetVal.name) }
        AlertDialog(
            onDismissRequest = { importTarget = null },
            shape = VolterGlassDialogDefaults.shape(),
            containerColor = VolterGlassDialogDefaults.containerColor(),
            tonalElevation = VolterGlassDialogDefaults.tonalElevation,
            title = { Text(stringResource(R.string.configs_import_title)) },
            confirmButton = {
                Button(
                    onClick = {
                        vm.importCloudAsLocal(importName, importTargetVal)
                        importTarget = null
                        tabIndex = 0
                    },
                ) { Text(stringResource(R.string.configs_import_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { importTarget = null }) {
                    Text(stringResource(R.string.configs_import_cancel))
                }
            },
            text = {
                StyledTextField(
                    value = importName,
                    onValueChange = { importName = it },
                    label = stringResource(R.string.configs_import_name_label),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }

    if (editorOpen) {
        ConfigEditorDialog(
            oldName = editorOldName,
            initialConfig = editorCfg,
            onDismiss = { editorOpen = false },
            onSave = { newName, newCfg ->
                editorOpen = false
                val old = editorOldName
                if (old != null && old != newName) vm.deleteLocalConfig(old)
                vm.upsertLocalConfig(newName, newCfg)
            },
        )
    }
    if (shareTarget != null) {
        val (name, cfg) = shareTarget!!
        ShareConfigQrDialog(
            name = name,
            cfg = cfg,
            context = ctx,
            onDismiss = { shareTarget = null },
        )
    }
}

@Composable
private fun ShareConfigQrDialog(name: String, cfg: Config, context: Context, onDismiss: () -> Unit) {
    val uri = remember(name, cfg) { Config.buildShareUri(name, cfg) }
    val img = remember(uri) { buildQrBitmap(uri).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = VolterGlassDialogDefaults.shape(),
        containerColor = VolterGlassDialogDefaults.containerColor(),
        tonalElevation = VolterGlassDialogDefaults.tonalElevation,
        confirmButton = {
            Button(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, uri)
                }
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.qr_config_share_chooser)))
            }) {
                Text(stringResource(R.string.action_share))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("volter", uri))
                onDismiss()
            }) {
                Text(stringResource(R.string.action_copy))
            }
        },
        title = { Text(stringResource(R.string.qr_config_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    bitmap = img,
                    contentDescription = stringResource(R.string.qr_config_image_cd),
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
                Text(text = name, style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
}

@Composable
private fun ConfigSourceSegment(
    modifier: Modifier = Modifier,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    localLabel: String,
    cloudLabel: String,
    onDarkBackground: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RectangleShape,
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outline),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier
                .padding(4.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(0 to localLabel, 1 to cloudLabel).forEach { (idx, label) ->
                val sel = selectedIndex == idx
                val interaction = remember(idx) { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RectangleShape)
                        .background(if (sel) scheme.background else Color.Transparent)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = { onSelect(idx) },
                        )
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (sel) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigEditorDialog(
    oldName: String?,
    initialConfig: Config?,
    onDismiss: () -> Unit,
    onSave: (String, Config) -> Unit,
) {
    val errBadConnection = stringResource(R.string.config_editor_err_connection)
    val errBadTransport = stringResource(R.string.config_editor_err_transport)
    val errBadSkip = stringResource(R.string.config_editor_err_skipverify)
    val errBadQuicPort = stringResource(R.string.config_editor_err_quic_port)
    var name by remember { mutableStateOf(oldName ?: "default") }
    var connection by remember { mutableStateOf("") }
    var routes by remember { mutableStateOf("") }
    var exclude by remember { mutableStateOf("") }
    var tunCIDR6 by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("auto") }
    var quicPort by remember { mutableStateOf("") }
    var quicServerName by remember { mutableStateOf("") }
    var quicSkipVerify by remember { mutableStateOf("") }
    var quicCertPin by remember { mutableStateOf("") }
    var quicCaCert by remember { mutableStateOf("") }
    var traceLog by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialConfig, oldName) {
        err = null
        name = oldName ?: "default"
        if (initialConfig != null) {
            connection = Config.buildMinimalConnectionUri(initialConfig)
            routes = initialConfig.routes ?: ""
            exclude = initialConfig.exclude ?: ""
            tunCIDR6 = initialConfig.tunCIDR6 ?: ""
            transport = initialConfig.transport ?: "auto"
            quicPort = portFromHostPort(initialConfig.quicServer) ?: ""
            quicServerName = initialConfig.quicServerName ?: ""
            quicSkipVerify = initialConfig.quicSkipVerify?.toString() ?: ""
            quicCertPin = initialConfig.quicCertPinSHA256 ?: ""
            quicCaCert = initialConfig.quicCaCert ?: ""
            traceLog = initialConfig.quicTraceLog == true
        } else {
            connection = ""
            routes = ""
            exclude = ""
            tunCIDR6 = ""
            transport = "auto"
            quicPort = ""
            quicServerName = ""
            quicSkipVerify = ""
            quicCertPin = ""
            quicCaCert = ""
            traceLog = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = VolterGlassDialogDefaults.shape(),
        containerColor = VolterGlassDialogDefaults.containerColor(),
        tonalElevation = VolterGlassDialogDefaults.tonalElevation,
        confirmButton = {
            Button(
                onClick = {
                    err = null
                    val shareParsed = Config.parseShareUri(connection)
                    val parsedFromShare = shareParsed?.second?.copy(protection = null)
                    val parsedCfg = parsedFromShare ?: Config.parseConnectionConfig(connection)
                    val parsed = Config.parseConnection(connection)
                    if (parsed == null && parsedCfg == null) {
                        err = errBadConnection
                        return@Button
                    }
                    val (server, token) = parsed ?: (parsedCfg!!.server to parsedCfg.token)
                    val safeName = Config.sanitizeName(
                        if (oldName == null && name.isBlank()) {
                            shareParsed?.first ?: name
                        } else {
                            name
                        },
                    )

                    val tr = transport.trim().lowercase()
                    val transportOut = when (tr) {
                        "", "auto" -> null
                        "tcp" -> "tcp"
                        "quic" -> "quic"
                        else -> null
                    }

                    val skip = quicSkipVerify.trim().lowercase()
                    val quicSkipOut = when {
                        skip.isBlank() -> null
                        skip == "true" -> true
                        skip == "false" -> false
                        else -> null
                    }

                    if (transportOut == null && transport.trim().isNotBlank() && tr != "auto") {
                        err = errBadTransport
                        return@Button
                    }

                    if (quicSkipOut == null && quicSkipVerify.trim().isNotBlank()) {
                        err = errBadSkip
                        return@Button
                    }

                    val pin = quicCertPin.replace(":", "").trim().takeIf { it.isNotBlank() }
                    val ca = quicCaCert.trim().takeIf { it.isNotBlank() }
                    val routesOut = routes.trim().takeIf { it.isNotBlank() }
                    val excludeOut = exclude.trim().takeIf { it.isNotBlank() }
                    val tun6Out = tunCIDR6.trim().takeIf { it.isNotBlank() }

                    val quicPortOut = quicPort.trim().toIntOrNull()
                    if (quicPort.trim().isNotBlank() && (quicPortOut == null || quicPortOut !in 1..65535)) {
                        err = errBadQuicPort
                        return@Button
                    }
                    val quicHost = parsedCfg?.quicServer?.let { Config.hostFromServer(it) } ?: Config.hostFromServer(server)
                    val quicSrvOut = quicPortOut?.let { Config.quicHostPort(quicHost, it) }
                    val quicSrvFinal = if (transportOut == "tcp") null else quicSrvOut
                    val cfg = Config(
                        server = server,
                        token = token,
                        routes = routesOut,
                        exclude = excludeOut,
                        tunCIDR6 = tun6Out,
                        transport = transportOut,
                        quicServer = quicSrvFinal,
                        quicServerName = quicServerName.trim().takeIf { it.isNotBlank() },
                        quicSkipVerify = quicSkipOut,
                        quicCertPinSHA256 = pin,
                        quicCaCert = ca,
                        quicTraceLog = if (traceLog) true else null,
                        managed = parsedCfg?.managed,
                        protection = null,
                        mesh = parsedCfg?.mesh ?: dev.c0redev.volter.domain.model.MeshConfig(),
                        relay = parsedCfg?.relay,
                    )

                    onSave(safeName, cfg)
                },
            ) { Text(stringResource(R.string.config_editor_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.configs_import_cancel)) } },
        title = { Text(stringResource(R.string.config_editor_title)) },
        text = {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StyledTextField(value = name, onValueChange = { name = it }, label = stringResource(R.string.config_editor_label_name), modifier = Modifier.fillMaxWidth())
                StyledTextField(value = connection, onValueChange = { connection = it }, label = stringResource(R.string.config_editor_label_connection), modifier = Modifier.fillMaxWidth())
                StyledTextField(value = routes, onValueChange = { routes = it }, label = stringResource(R.string.config_editor_label_routes), modifier = Modifier.fillMaxWidth())
                StyledTextField(value = exclude, onValueChange = { exclude = it }, label = stringResource(R.string.config_editor_label_exclude), modifier = Modifier.fillMaxWidth())
                StyledTextField(value = tunCIDR6, onValueChange = { tunCIDR6 = it }, label = stringResource(R.string.config_editor_label_tun6), modifier = Modifier.fillMaxWidth())
                StyledTextField(value = transport, onValueChange = { transport = it }, label = stringResource(R.string.config_editor_label_transport), modifier = Modifier.fillMaxWidth())
                StyledTextField(value = quicPort, onValueChange = { quicPort = digitsOnly(it).take(5) }, label = stringResource(R.string.config_editor_label_quic_port), modifier = Modifier.fillMaxWidth())
                StyledTextField(value = quicServerName, onValueChange = { quicServerName = it }, label = stringResource(R.string.config_editor_label_quic_sni), modifier = Modifier.fillMaxWidth())
                StyledTextField(value = quicSkipVerify, onValueChange = { quicSkipVerify = it }, label = stringResource(R.string.config_editor_label_quic_skip), modifier = Modifier.fillMaxWidth())
                StyledTextField(value = quicCertPin, onValueChange = { quicCertPin = it }, label = stringResource(R.string.config_editor_label_quic_pin), modifier = Modifier.fillMaxWidth())
                StyledTextField(value = quicCaCert, onValueChange = { quicCaCert = it }, label = stringResource(R.string.config_editor_label_quic_ca), modifier = Modifier.fillMaxWidth())

                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = traceLog, onCheckedChange = { traceLog = it })
                    Text(
                        stringResource(R.string.config_editor_quic_trace),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (err != null) {
                    Text(
                        text = err ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    )
}

private fun portFromHostPort(value: String?): String? {
    val s = value?.trim().orEmpty()
    if (s.isBlank()) return null
    val rawPort = if (s.startsWith("[")) {
        val end = s.indexOf(']')
        if (end < 0 || end + 2 > s.length) return null
        s.substring(end + 2)
    } else {
        val idx = s.lastIndexOf(':')
        if (idx < 0) return null
        s.substring(idx + 1)
    }
    val port = rawPort.toIntOrNull() ?: return null
    return port.takeIf { it in 1..65535 }?.toString()
}

private fun digitsOnly(v: String): String {
    val out = StringBuilder(v.length)
    for (ch in v) if (ch in '0'..'9') out.append(ch)
    return out.toString()
}

@Composable
private fun LocalProfileSkeleton() {
    val bar = MaterialTheme.colorScheme.surfaceVariant
    Surface(
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.42f)
                    .height(18.dp)
                    .clip(RectangleShape)
                    .background(bar),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.62f)
                    .height(13.dp)
                    .clip(RectangleShape)
                    .background(bar),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RectangleShape)
                        .background(bar),
                )
                Box(
                    Modifier
                        .width(52.dp)
                        .height(32.dp)
                        .clip(RectangleShape)
                        .background(bar),
                )
            }
        }
    }
}
