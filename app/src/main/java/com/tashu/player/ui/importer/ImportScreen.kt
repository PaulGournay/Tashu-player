package com.tashu.player.ui.importer

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.tashu.player.drive.importer.PlaylistImportWorker
import com.tashu.player.drive.importer.PlaylistImportWorker.Companion as W
import kotlinx.coroutines.flow.map

/**
 * Import d'une playlist YouTube Music : coller le lien, lancer. Le téléchargement et l'envoi sur Drive
 * continuent en arrière-plan (notification) ; cet écran affiche l'avancement s'il est rouvert.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val info by remember {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(W.UNIQUE).map { list -> list.maxByOrNull { it.runAttemptCount } ?: list.lastOrNull() }
    }.collectAsStateWithLifecycle(null)
    var url by rememberSaveable { mutableStateOf("") }
    val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Importer une playlist") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Collez le lien d'une playlist YouTube Music. Les morceaux sont convertis en MP3 (pochette et " +
                    "numéros de piste inclus) puis enregistrés sur votre Drive dans « ${W.IMPORT_ROOT_NAME} », " +
                    "dans un dossier au nom de la playlist.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                label = { Text("Lien de la playlist") },
                placeholder = { Text("https://music.youtube.com/playlist?list=…") },
                singleLine = true,
                enabled = !running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                trailingIcon = {
                    IconButton(onClick = { pasted(context)?.let { url = it.trim() } }, enabled = !running) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = "Coller le lien")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { PlaylistImportWorker.start(context, url) },
                    enabled = !running && url.startsWith("http"),
                ) { Text("Importer sur Drive") }
                if (running) OutlinedButton(onClick = { PlaylistImportWorker.cancel(context) }) { Text("Annuler") }
            }

            Spacer(Modifier.height(4.dp))
            info?.let { ImportProgress(it) }

            Text(
                "Le téléchargement continue si vous quittez l'app. N'importez que des contenus que vous avez le droit de télécharger.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportProgress(info: WorkInfo) {
    when (info.state) {
        WorkInfo.State.ENQUEUED -> Text("En attente d'une connexion…", style = MaterialTheme.typography.titleMedium)
        WorkInfo.State.RUNNING -> {
            val p = info.progress
            val stage = p.getString(W.KEY_STAGE)
            val index = p.getInt(W.KEY_INDEX, 0)
            val total = p.getInt(W.KEY_TOTAL, 0)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.getString(W.KEY_MESSAGE) ?: "Démarrage…", style = MaterialTheme.typography.titleMedium)
                if (total > 0) LinearProgressIndicator(progress = { index / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                p.getString(W.KEY_TRACK)?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (stage == W.STAGE_DOWNLOAD) {
                    LinearProgressIndicator(
                        progress = { p.getFloat(W.KEY_PERCENT, 0f) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        WorkInfo.State.SUCCEEDED -> {
            val o = info.outputData
            val count = o.getInt(W.KEY_COUNT, 0)
            val sent = o.getInt(W.KEY_SENT, 0)
            Text(
                "Import terminé : $count morceau${if (count > 1) "x" else ""} dans « ${o.getString(W.KEY_FOLDER)} »" +
                    (if (sent < count) " (${count - sent} déjà présent${if (count - sent > 1) "s" else ""})" else "") +
                    ". La bibliothèque est en cours de mise à jour.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            o.getString(W.KEY_SKIPPED)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        WorkInfo.State.FAILED -> Text(
            "Import échoué : ${info.outputData.getString(W.KEY_ERROR) ?: "erreur inconnue"}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        WorkInfo.State.CANCELLED -> Text("Import annulé.", style = MaterialTheme.typography.titleMedium)
        WorkInfo.State.BLOCKED -> Unit
    }
}

private fun pasted(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString()
