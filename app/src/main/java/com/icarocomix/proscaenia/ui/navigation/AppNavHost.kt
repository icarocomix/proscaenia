package com.icarocomix.proscaenia.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.icarocomix.proscaenia.ui.catalog.CatalogScreen
import com.icarocomix.proscaenia.ui.detail.DetailScreen
import com.icarocomix.proscaenia.ui.login.LoginScreen
import com.icarocomix.proscaenia.ui.player.PlayerScreen
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val ARG_SHOW_ID = "showId"
    const val LOGIN = "login"
    const val CATALOG = "catalog"
    const val SHOW = "show/{$ARG_SHOW_ID}"
    const val PLAYER = "player/{$ARG_SHOW_ID}"

    // Codifico l'ID perché potrebbe contenere caratteri come "/" che spezzerebbero la rotta.
    fun show(showId: String) = "show/${Uri.encode(showId)}"

    fun player(showId: String) = "player/${Uri.encode(showId)}"
}

/**
 * Grafo di navigazione dell'app.
 *
 * Passo tra le destinazioni solo l'identificativo dello spettacolo: i dati completi vengono riletti
 * dal repository, che li tiene in cache in memoria, così la navigazione non trasporta oggetti
 * voluminosi nello stato salvato e sopravvive alla ricreazione del processo.
 */
@Composable
fun AppNavHost(
    startDestination: String,
    playerRequests: StateFlow<String?>,
    onPlayerRequestHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val pendingPlayerShowId by playerRequests.collectAsStateWithLifecycle()

    LaunchedEffect(pendingPlayerShowId) {
        val showId = pendingPlayerShowId ?: return@LaunchedEffect
        // Una notifica esiste solo se c'è una riproduzione, quindi le credenziali sono già salvate;
        // controllo comunque di non essere sul login per non scavalcare il primo avvio.
        if (navController.currentDestination?.route != Routes.LOGIN) {
            navController.navigate(Routes.player(showId)) { launchSingleTop = true }
        }
        onPlayerRequestHandled()
    }

    val showIdArgument = listOf(navArgument(Routes.ARG_SHOW_ID) { type = NavType.StringType })

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.LOGIN) {
            LoginScreen(onSignedIn = { navController.openCatalogClearingStack() })
        }
        composable(Routes.CATALOG) {
            // Il login è raggiungibile solo come destinazione iniziale del primo avvio: dopo che le
            // credenziali sono state verificate e salvate, nessuna schermata riporta più all'accesso
            // e ogni rifiuto o errore di rete viene gestito con ritentativi automatici.
            CatalogScreen(
                onShowSelected = { navController.navigate(Routes.show(it)) },
            )
        }
        composable(Routes.SHOW, arguments = showIdArgument) {
            DetailScreen(
                onBack = { navController.popBackStack() },
                onPlay = { navController.navigate(Routes.player(it)) { launchSingleTop = true } },
            )
        }
        composable(Routes.PLAYER, arguments = showIdArgument) {
            PlayerScreen(
                onBack = {
                    // Se il player è stato aperto dalla notifica non c'è nulla sotto: torno al catalogo.
                    if (!navController.popBackStack()) navController.navigate(Routes.CATALOG)
                },
            )
        }
    }
}

/**
 * Dopo un login riuscito svuoto l'intero back stack: sia al primo avvio sia dopo un aggiornamento
 * delle credenziali non ha senso poter tornare indietro alla schermata di accesso.
 */
private fun NavHostController.openCatalogClearingStack() {
    navigate(Routes.CATALOG) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
