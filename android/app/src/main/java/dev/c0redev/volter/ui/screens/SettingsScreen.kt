package dev.c0redev.volter.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.c0redev.volter.BuildConfig
import dev.c0redev.volter.theme.VolterSpacing
import dev.c0redev.volter.R
import dev.c0redev.volter.domain.model.ClientSettings
import dev.c0redev.volter.quick.QuickConnectPrefs
import dev.c0redev.volter.ui.ConnectionViewModel
import dev.c0redev.volter.ui.UpdateUiState
import dev.c0redev.volter.ui.components.SectionCard
import dev.c0redev.volter.ui.components.PageHeader
import dev.c0redev.volter.ui.components.StyledTextField
import dev.c0redev.volter.ui.components.VolterGlassDialogDefaults

@Composable
fun SettingsScreen(vm: ConnectionViewModel, padding: PaddingValues) {
    val localNames = vm.localConfigs.collectAsState().value.map { it.name }
    val s = vm.clientSettings.collectAsState().value
    val upd by vm.updateStatus.collectAsState()
    val updateUi by vm.updateUi.collectAsState()
    val remoteTag by vm.remoteReleaseTag.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshRemoteReleaseTag()
    }
    var mode by remember { mutableStateOf(s.mode) }
    var proxyListen by remember { mutableStateOf(s.proxyListen) }
    var systemProxy by remember { mutableStateOf(s.systemProxy) }
    var ipv6Tunnel by remember { mutableStateOf(s.ipv6Tunnel) }
    var dualTun by remember { mutableStateOf(s.dualTun) }
    var transportPref by remember { mutableStateOf(ClientSettings.normalizedTransportPreference(s.transportPreference)) }
    var splitMode by remember { mutableStateOf(ClientSettings.normalizedSplitTunnelMode(s.splitTunnelMode)) }
    var splitApps by remember { mutableStateOf(s.splitTunnelApps.toSet()) }

    LaunchedEffect(s) {
        mode = s.mode
        proxyListen = s.proxyListen
        systemProxy = s.systemProxy
        ipv6Tunnel = s.ipv6Tunnel
        dualTun = s.dualTun
        transportPref = ClientSettings.normalizedTransportPreference(s.transportPreference)
        splitMode = ClientSettings.normalizedSplitTunnelMode(s.splitTunnelMode)
        splitApps = s.splitTunnelApps.toSet()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = VolterSpacing.screenHorizontal, vertical = VolterSpacing.screenVertical)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_header_subtitle),
            icon = Icons.Outlined.Settings,
            meta = mode.uppercase(),
        )

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_connection_mode_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = { mode = "tun" },
                        enabled = mode != "tun",
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(VolterSpacing.controlRadius),
                    ) {
                        Text(stringResource(R.string.settings_mode_tun))
                    }
                    FilledTonalButton(
                        onClick = { mode = "proxy" },
                        enabled = mode != "proxy",
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(VolterSpacing.controlRadius),
                    ) {
                        Text(stringResource(R.string.settings_mode_proxy))
                    }
                }

                if (mode == "proxy") {
                    StyledTextField(
                        value = proxyListen,
                        onValueChange = { proxyListen = it },
                        label = stringResource(R.string.settings_proxy_listen_label),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_system_proxy_title),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(R.string.settings_system_proxy_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = systemProxy,
                            onCheckedChange = { systemProxy = it },
                            enabled = mode == "proxy",
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }

                if (mode == "tun") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_ipv6_tun_title),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(R.string.settings_ipv6_tun_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = ipv6Tunnel,
                            onCheckedChange = { ipv6Tunnel = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }

                    Text(
                        text = stringResource(R.string.settings_transport_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.settings_transport_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { transportPref = ClientSettings.TRANSPORT_AUTO },
                            enabled = transportPref != ClientSettings.TRANSPORT_AUTO,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(VolterSpacing.controlRadius),
                        ) {
                            Text(stringResource(R.string.home_route_auto))
                        }
                        FilledTonalButton(
                            onClick = { transportPref = ClientSettings.TRANSPORT_TCP },
                            enabled = transportPref != ClientSettings.TRANSPORT_TCP,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(VolterSpacing.controlRadius),
                        ) {
                            Text(stringResource(R.string.settings_transport_tcp))
                        }
                        FilledTonalButton(
                            onClick = { transportPref = ClientSettings.TRANSPORT_QUIC },
                            enabled = transportPref != ClientSettings.TRANSPORT_QUIC,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(VolterSpacing.controlRadius),
                        ) {
                            Text(stringResource(R.string.settings_transport_quic))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_dual_tun_title),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(R.string.settings_dual_tun_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = dualTun,
                            onCheckedChange = { dualTun = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }

                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            vm.saveClientSettings(
                                ClientSettings(
                                    mode = mode,
                                    systemProxy = systemProxy,
                                    proxyListen = proxyListen,
                                    ipv6Tunnel = ipv6Tunnel,
                                    dualTun = dualTun,
                                    transportPreference = transportPref,
                                    splitTunnelMode = splitMode,
                                    splitTunnelApps = splitApps.toList().sorted(),
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(VolterSpacing.controlRadius),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(stringResource(R.string.settings_save_client), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        SplitTunnelAppsSection(
            splitMode = splitMode,
            selectedPackages = splitApps,
            tunModeSelected = mode == "tun",
            onModeChange = { splitMode = it },
            onClearSelected = { splitApps = emptySet() },
            onTogglePackage = { pkg ->
                splitApps = if (pkg in splitApps) splitApps - pkg else splitApps + pkg
            },
        )

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.quick_tiles_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.quick_tiles_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QuickTileSlotRows(localNames = localNames)
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_updates_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.settings_build_fmt, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!remoteTag.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.settings_release_fmt, remoteTag!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick = { vm.checkForUpdateAndInstall() },
                    enabled = updateUi is UpdateUiState.Idle,
                    modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(VolterSpacing.controlRadius),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SystemUpdateAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(stringResource(R.string.settings_check_updates), modifier = Modifier.padding(start = 8.dp))
                }

                when (val u = updateUi) {
                    is UpdateUiState.Checking -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.update_checking),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdateUiState.Downloading -> {
                        val p = u.progress
                        if (p != null) {
                            LinearProgressIndicator(
                                progress = { p.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            stringResource(R.string.update_downloading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {}
                }

                if (!upd.isNullOrBlank()) {
                    Text(
                        text = upd!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_credits_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.settings_credits_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private data class InstalledAppUi(
    val label: String,
    val packageName: String,
    val icon: Drawable,
)

private fun loadInstalledApps(context: Context): List<InstalledAppUi> {
    val pm = context.packageManager
    val byPackage = linkedMapOf<String, ApplicationInfo>()

    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    runCatching {
        pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
    }.getOrDefault(emptyList()).forEach { info ->
        val app = info.activityInfo?.applicationInfo ?: return@forEach
        byPackage[app.packageName] = app
    }

    runCatching {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    }.getOrDefault(emptyList()).forEach { app ->
        byPackage.putIfAbsent(app.packageName, app)
    }

    return byPackage.values
        .asSequence()
        .filter { it.packageName != context.packageName }
        .filter { it.enabled }
        .map { app ->
            InstalledAppUi(
                label = app.loadLabel(pm).toString().ifBlank { app.packageName },
                packageName = app.packageName,
                icon = app.loadIcon(pm),
            )
        }
        .sortedWith(compareBy<InstalledAppUi> { it.label.lowercase() }.thenBy { it.packageName })
        .toList()
}

@Composable
private fun SplitTunnelAppsSection(
    splitMode: String,
    selectedPackages: Set<String>,
    tunModeSelected: Boolean,
    onModeChange: (String) -> Unit,
    onClearSelected: () -> Unit,
    onTogglePackage: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val apps = remember(ctx) { loadInstalledApps(ctx) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps else apps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_split_apps_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.settings_split_apps_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_split_selected_fmt, selectedPackages.size),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_split_apps_count_fmt, filtered.size, apps.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SplitModeSelector(splitMode = splitMode, onModeChange = onModeChange)
            if (!tunModeSelected) {
                Surface(
                    shape = RoundedCornerShape(VolterSpacing.controlRadius),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.settings_split_tun_required),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.settings_split_search)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = null)
                        }
                    }
                },
                shape = RoundedCornerShape(VolterSpacing.controlRadius),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                ),
            )
            Text(
                text = when (splitMode) {
                    ClientSettings.SPLIT_BYPASS -> stringResource(R.string.settings_split_bypass_hint)
                    ClientSettings.SPLIT_ONLY -> stringResource(R.string.settings_split_only_hint)
                    else -> stringResource(R.string.settings_split_off_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp,
            ) {
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_split_no_apps),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(390.dp)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filtered, key = { it.packageName }) { app ->
                            AppSplitRow(
                                app = app,
                                checked = app.packageName in selectedPackages,
                                enabled = splitMode != ClientSettings.SPLIT_OFF,
                                onToggle = { onTogglePackage(app.packageName) },
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onClearSelected, enabled = selectedPackages.isNotEmpty()) {
                    Text(stringResource(R.string.settings_split_clear_selected))
                }
            }
        }
    }
}

@Composable
private fun SplitModeSelector(
    splitMode: String,
    onModeChange: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SplitModePill(
            title = stringResource(R.string.settings_split_off),
            subtitle = stringResource(R.string.settings_split_off_short),
            selected = splitMode == ClientSettings.SPLIT_OFF,
            onClick = { onModeChange(ClientSettings.SPLIT_OFF) },
            modifier = Modifier.weight(1f),
        )
        SplitModePill(
            title = stringResource(R.string.settings_split_bypass),
            subtitle = stringResource(R.string.settings_split_bypass_short),
            selected = splitMode == ClientSettings.SPLIT_BYPASS,
            onClick = { onModeChange(ClientSettings.SPLIT_BYPASS) },
            modifier = Modifier.weight(1f),
        )
        SplitModePill(
            title = stringResource(R.string.settings_split_only),
            subtitle = stringResource(R.string.settings_split_only_short),
            selected = splitMode == ClientSettings.SPLIT_ONLY,
            onClick = { onModeChange(ClientSettings.SPLIT_ONLY) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SplitModePill(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(VolterSpacing.controlRadius),
        color = bg,
        contentColor = fg,
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AppSplitRow(
    app: InstalledAppUi,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle),
        shape = RoundedCornerShape(18.dp),
        color = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (checked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (checked) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Image(
                    bitmap = app.icon.toBitmap(width = 56, height = 56).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(46.dp)
                        .padding(5.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(text = app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun QuickTileSlotRows(localNames: List<String>) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val s0 = remember(tick, ctx) { QuickConnectPrefs.getSlotName(ctx, 0) }
    val s1 = remember(tick, ctx) { QuickConnectPrefs.getSlotName(ctx, 1) }
    val s2 = remember(tick, ctx) { QuickConnectPrefs.getSlotName(ctx, 2) }
    fun setSlot(slot: Int, name: String?) {
        QuickConnectPrefs.setSlotName(ctx, slot, name)
        tick++
    }
    QuickSlotRow(
        title = stringResource(R.string.quick_slot_1),
        current = s0,
        names = localNames,
        onSelect = { setSlot(0, it) },
    )
    QuickSlotRow(
        title = stringResource(R.string.quick_slot_2),
        current = s1,
        names = localNames,
        onSelect = { setSlot(1, it) },
    )
    QuickSlotRow(
        title = stringResource(R.string.quick_slot_3),
        current = s2,
        names = localNames,
        onSelect = { setSlot(2, it) },
    )
}

@Composable
private fun QuickSlotRow(
    title: String,
    current: String?,
    names: List<String>,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                text = current ?: stringResource(R.string.quick_slot_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.quick_slot_pick))
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            shape = VolterGlassDialogDefaults.shape(),
            containerColor = VolterGlassDialogDefaults.containerColor(),
            tonalElevation = VolterGlassDialogDefaults.tonalElevation,
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            onSelect(null)
                            open = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.quick_slot_none))
                    }
                    names.forEach { n ->
                        TextButton(
                            onClick = {
                                onSelect(n)
                                open = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(n)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.configs_import_cancel))
                }
            },
        )
    }
}
