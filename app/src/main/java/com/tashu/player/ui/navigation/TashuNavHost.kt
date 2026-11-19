package com.tashu.player.ui.navigation

import com.tashu.player.ui.coverflow.CoverFlowScreen
import com.tashu.player.ui.importer.ImportScreen
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import android.content.res.Configuration
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tashu.player.ui.MainViewModel
import com.tashu.player.ui.library.AlbumScreen
import com.tashu.player.ui.library.LibraryScreen
import com.tashu.player.ui.nowplaying.NowPlayingScreen
import com.tashu.player.ui.onboarding.FolderPickerScreen
import com.tashu.player.ui.onboarding.SignInScreen

object Routes {
    const val SIGN_IN = "signin"
    const val PICK_FOLDERS = "folders"
    const val LIBRARY = "library"
    const val ALBUM = "album/{id}"
    const val NOW_PLAYING = "now"
    const val IMPORT = "import"
    fun album(id: Long) = "album/$id"
}

@Composable
fun TashuNavHost(nav: NavHostController, signedIn: Boolean) {
    val vm: MainViewModel = hiltViewModel()
    val roots by vm.roots.collectAsStateWithLifecycle()

    // Redirections d'onboarding : connexion → choix des dossiers → bibliothèque.
    LaunchedEffect(signedIn, roots) {
        val target = when {
            !signedIn -> Routes.SIGN_IN
            roots == null -> return@LaunchedEffect
            roots!!.isEmpty() -> Routes.PICK_FOLDERS
            else -> Routes.LIBRARY
        }
        val current = nav.currentDestination?.route
        val onboarding = current == null || current == Routes.SIGN_IN || current == Routes.PICK_FOLDERS
        if (current != target && (onboarding || !signedIn)) {
            nav.navigate(target) { popUpTo(0) { inclusive = true } }
        }
    }

    // Paysage : carrousel de pochettes par-dessus l'app (hors connexion / choix des dossiers).
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val showCoverFlow = landscape && signedIn && route != null && route != Routes.SIGN_IN && route != Routes.PICK_FOLDERS && route != Routes.IMPORT

    Box(Modifier.fillMaxSize()) {
    NavHost(nav, startDestination = Routes.SIGN_IN) {
        composable(Routes.SIGN_IN) { SignInScreen(vm) }
        composable(Routes.PICK_FOLDERS) {
            FolderPickerScreen(onDone = { ids -> vm.saveRoots(ids); nav.navigate(Routes.LIBRARY) { popUpTo(0) } })
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                vm = vm,
                onOpenAlbum = { nav.navigate(Routes.album(it)) },
                onOpenPlayer = { nav.navigate(Routes.NOW_PLAYING) },
                onManageFolders = { nav.navigate(Routes.PICK_FOLDERS) },
                onImport = { nav.navigate(Routes.IMPORT) },
            )
        }
        composable(Routes.ALBUM, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            AlbumScreen(vm, entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() }, onOpenPlayer = { nav.navigate(Routes.NOW_PLAYING) })
        }
        composable(Routes.IMPORT) { ImportScreen(onBack = { nav.popBackStack() }) }
        composable(
            Routes.NOW_PLAYING,
            enterTransition = { slideInVertically { it } },
            exitTransition = { slideOutVertically { it } },
        ) {
            NowPlayingScreen(onClose = { nav.popBackStack() })
        }
    }
    if (showCoverFlow) CoverFlowScreen(vm)
    }
}
