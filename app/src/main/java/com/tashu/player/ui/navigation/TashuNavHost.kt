package com.tashu.player.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import com.tashu.player.ui.theme.bubbleSpring
import com.tashu.player.ui.coverflow.CoverFlowScreen
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
import com.tashu.player.ui.menu.PersonalizationScreen
import com.tashu.player.ui.nowplaying.NowPlayingScreen
import com.tashu.player.ui.onboarding.FolderPickerScreen
import com.tashu.player.ui.onboarding.SignInScreen

object Routes {
    const val SIGN_IN = "signin"
    const val PICK_FOLDERS = "folders"
    const val LIBRARY = "library"
    const val ALBUM = "album/{id}"
    const val NOW_PLAYING = "now"
    const val PERSONALIZATION = "personalization"
    fun album(id: Long) = "album/$id"
}

/** Point d'où le lecteur jaillit et où il retourne : le centre du mini-lecteur, en bas de l'écran. */
private val PlayerOrigin = TransformOrigin(0.5f, 0.83f)

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
    // Quitter le lecteur (chevron, geste ou bouton Retour) déclenche l'atterrissage du mini-lecteur.
    var lastRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(route) {
        if (lastRoute == Routes.NOW_PLAYING && route != Routes.NOW_PLAYING) vm.onPlayerCollapsed()
        lastRoute = route
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val showCoverFlow = landscape && signedIn && route != null && route != Routes.SIGN_IN && route != Routes.PICK_FOLDERS

    Box(Modifier.fillMaxSize()) {
    NavHost(
        nav, startDestination = Routes.SIGN_IN,
        // Navigation « bulle » : les écrans gonflent et se dégonflent avec un ressort au lieu de glisser.
        // Vers le lecteur, l'écran du dessous recule doucement sous la carte qui monte.
        enterTransition = { fadeIn(tween(200)) + scaleIn(bubbleSpring(), initialScale = 0.88f) },
        exitTransition = {
            if (targetState.destination.route == Routes.NOW_PLAYING) scaleOut(bubbleSpring(), targetScale = 0.92f) + fadeOut(tween(400), targetAlpha = 0.4f)
            else fadeOut(tween(160)) + scaleOut(tween(220), targetScale = 1.04f)
        },
        popEnterTransition = {
            if (initialState.destination.route == Routes.NOW_PLAYING) scaleIn(bubbleSpring(), initialScale = 0.92f) + fadeIn(tween(300), initialAlpha = 0.4f)
            else fadeIn(tween(220)) + scaleIn(bubbleSpring(), initialScale = 1.04f)
        },
        popExitTransition = { fadeOut(tween(180)) + scaleOut(bubbleSpring(Spring.StiffnessMedium), targetScale = 0.85f) },
    ) {
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
                onPersonalize = { nav.navigate(Routes.PERSONALIZATION) },
            )
        }
        composable(Routes.PERSONALIZATION) { PersonalizationScreen(vm, onBack = { nav.popBackStack() }) }
        composable(Routes.ALBUM, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            AlbumScreen(vm, entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() }, onOpenPlayer = { nav.navigate(Routes.NOW_PLAYING) })
        }
        composable(
            Routes.NOW_PLAYING,
            // Le lecteur jaillit du mini-lecteur (bas de l'écran) comme une bulle, et y retourne en se rétractant.
            enterTransition = {
                scaleIn(bubbleSpring(), initialScale = 0.2f, transformOrigin = PlayerOrigin) +
                    slideInVertically(bubbleSpring()) { it / 5 } + fadeIn(tween(160))
            },
            exitTransition = { fadeOut(tween(200)) },
            popExitTransition = {
                // Il fond vers le bas de l'écran ; le mini-lecteur prend le relais avec un rebond (voir LibraryHome).
                scaleOut(tween(360, easing = FastOutSlowInEasing), targetScale = 0.14f, transformOrigin = PlayerOrigin) +
                    fadeOut(tween(160, delayMillis = 200))
            },
        ) {
            // Coins très arrondis pendant le trajet, droits une fois le lecteur posé.
            val corner by transition.animateDp(transitionSpec = { bubbleSpring() }, label = "corner") {
                if (it == EnterExitState.Visible) 0.dp else 64.dp
            }
            // Le ressort dépasse légèrement 0 : on borne, un rayon négatif fait planter.
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(corner.coerceAtLeast(0.dp)))) {
                NowPlayingScreen(onClose = { nav.popBackStack() })
            }
        }
    }
    if (showCoverFlow) CoverFlowScreen(vm)
    }
}
