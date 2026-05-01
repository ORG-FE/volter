package dev.c0redev.volter.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.c0redev.volter.R
import dev.c0redev.volter.theme.VolterSpacing
import dev.c0redev.volter.domain.model.Config
import dev.c0redev.volter.domain.model.ProtectionOptions
import dev.c0redev.volter.ui.ConnectionViewModel
import dev.c0redev.volter.ui.components.SectionCard
import dev.c0redev.volter.ui.components.VolterGlassDialogDefaults
import dev.c0redev.volter.ui.protection.ProtectionEditor
import dev.c0redev.volter.ui.qr.buildQrBitmap

@Composable
fun ProtectionScreen(vm: ConnectionViewModel, padding: PaddingValues) {
    val ctx = LocalContext.current
    val current = vm.globalProtection.collectAsState().value
    val metrics = vm.metrics.collectAsState().value.records
    var shareProtection by remember { mutableStateOf<ProtectionOptions?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = VolterSpacing.screenHorizontal, vertical = VolterSpacing.screenVertical),
        verticalArrangement = Arrangement.spacedBy(VolterSpacing.sectionGap),
    ) {
        item {
            Text(
                text = stringResource(R.string.protection_title),
                style = MaterialTheme.typography.titleLarge,
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
            FilledTonalButton(
                onClick = { shareProtection = current ?: ProtectionOptions() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.protection_share_qr_button))
            }
        }

        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.protection_sessions_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val tail = metrics.takeLast(8).asReversed()
                    if (tail.isEmpty()) {
                        Text(
                            stringResource(R.string.protection_sessions_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
    val share = shareProtection
    if (share != null) {
        ProtectionQrDialog(
            protection = share,
            context = ctx,
            onDismiss = { shareProtection = null },
        )
    }
}

@Composable
private fun ProtectionQrDialog(protection: ProtectionOptions, context: Context, onDismiss: () -> Unit) {
    val uri = remember(protection) { Config.buildProtectionUri("protection", protection) }
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
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.protection_qr_share_chooser)))
            }) { Text(stringResource(R.string.action_share)) }
        },
        dismissButton = {
            TextButton(onClick = {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("volter-protection", uri))
                onDismiss()
            }) { Text(stringResource(R.string.action_copy)) }
        },
        title = { Text(stringResource(R.string.protection_qr_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    bitmap = img,
                    contentDescription = stringResource(R.string.protection_qr_image_cd),
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
                Text(stringResource(R.string.protection_qr_scan_hint))
            }
        },
    )
}
