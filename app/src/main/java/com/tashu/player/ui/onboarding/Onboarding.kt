package com.tashu.player.ui.onboarding

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tashu.player.ui.FolderPickerViewModel
import com.tashu.player.ui.MainViewModel

@Composable
fun SignInScreen(vm: MainViewModel) {
    val activity = LocalContext.current as Activity
    val signInError by vm.error.collectAsStateWithLifecycle()
    val authError by vm.auth.error.collectAsStateWithLifecycle()
    val error = signInError ?: authError
    // Surface du thème : sans elle, le titre s'écrivait en noir sur le fond noir de la fenêtre.
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), contentAlignment = Alignment.BottomStart) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Votre musique, depuis votre Google Drive.", style = MaterialTheme.typography.displaySmall)
                Text(
                    "Tashu lit vos fichiers FLAC, ALAC, WAV, Opus, AAC, MP3 et Ogg directement depuis Drive. " +
                        "L'accès est en lecture seule : rien n'est modifié ni supprimé.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.signIn(activity) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Se connecter avec Google")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(onDone: (Set<String>) -> Unit, vm: FolderPickerViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.path.lastOrNull()?.name ?: "Choisir les dossiers de musique") },
                navigationIcon = {
                    if (state.path.isNotEmpty()) IconButton(onClick = vm::up) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Dossier parent")
                    }
                },
                actions = {
                    if (state.folders.isNotEmpty()) TextButton(onClick = vm::toggleAllShown) {
                        Text(if (state.folders.all { it.id in selected }) "Tout décocher" else "Tout cocher")
                    }
                },
            )
        },
        floatingActionButton = {
            if (selected.isNotEmpty()) ExtendedFloatingActionButton(onClick = { onDone(selected.keys) }) {
                Text(if (selected.size == 1) "Indexer 1 dossier" else "Indexer ${selected.size} dossiers")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().consumeWindowInsets(padding), contentPadding = padding) {
            if (state.path.isEmpty()) {
                item { SectionHeader("Dossiers de musique trouvés") }
                when {
                    suggestions.loading -> item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Recherche des dossiers qui contiennent de la musique…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    suggestions.error != null -> item { Text(suggestions.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                    suggestions.folders.isEmpty() -> item {
                        Text("Aucun dossier de musique trouvé.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(suggestions.folders, key = { "suggestion:" + it.folder.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.path) },
                        supportingContent = {
                            Text("${s.albums} album${if (s.albums > 1) "s" else ""}, ${s.tracks} morceau${if (s.tracks > 1) "x" else ""}")
                        },
                        leadingContent = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                        trailingContent = { Checkbox(checked = s.folder.id in selected, onCheckedChange = { vm.toggle(s.folder) }) },
                        modifier = Modifier.clickable { vm.toggle(s.folder) },
                    )
                }
                item { SectionHeader("Tous les dossiers") }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            if (!state.loading && state.folders.isEmpty() && state.error == null) {
                item { Text("Aucun sous-dossier ici.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.folders, key = { it.id }) { f ->
                ListItem(
                    headlineContent = { Text(f.name) },
                    leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                    trailingContent = {
                        // La racine virtuelle "root" se résout en vrai ID au scan (alias Drive).
                        Checkbox(checked = f.id in selected, onCheckedChange = { vm.toggle(f) })
                    },
                    modifier = Modifier.clickable { vm.open(f) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp))
}
