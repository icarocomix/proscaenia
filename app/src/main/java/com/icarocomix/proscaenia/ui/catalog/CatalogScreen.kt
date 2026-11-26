package com.icarocomix.proscaenia.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.icarocomix.proscaenia.R
import com.icarocomix.proscaenia.domain.model.Category
import com.icarocomix.proscaenia.domain.model.ShowSummary
import com.icarocomix.proscaenia.ui.common.ErrorState
import com.icarocomix.proscaenia.ui.common.LoadingState
import com.icarocomix.proscaenia.ui.common.NowPlayingBar
import com.icarocomix.proscaenia.ui.common.asString
import com.icarocomix.proscaenia.ui.common.formatDuration
import com.icarocomix.proscaenia.ui.tv.LocalTvMode
import com.icarocomix.proscaenia.ui.tv.TvSafePadding
import com.icarocomix.proscaenia.ui.tv.tvFocusHighlight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onShowSelected: (String) -> Unit,
    onOpenPlayer: (String) -> Unit,
    viewModel: CatalogViewModel = viewModel(factory = CatalogViewModel.Factory),
) {
    if (LocalTvMode.current) {
        TvCatalogScreen(onShowSelected = onShowSelected, onOpenPlayer = onOpenPlayer, viewModel = viewModel)
        return
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    val refreshError = (state as? CatalogUiState.Content)?.refreshError
    val refreshErrorText = refreshError?.asString()
    LaunchedEffect(refreshErrorText) {
        if (refreshErrorText != null) {
            snackbarHostState.showSnackbar(refreshErrorText)
            viewModel.dismissRefreshError()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.catalog_title),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (val current = state) {
            CatalogUiState.Loading -> LoadingState(Modifier.padding(innerPadding))
            is CatalogUiState.Failure -> ErrorState(
                message = current.message,
                onRetry = viewModel::retry,
                modifier = Modifier.padding(innerPadding),
            )
            is CatalogUiState.Content -> PullToRefreshBox(
                isRefreshing = current.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    NowPlayingBar(
                        onOpenPlayer = onOpenPlayer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    CatalogContent(categories = current.categories, onShowSelected = onShowSelected)
                }
            }
        }
    }
}

/**
 * Catalogo per TV (canvas 960×540 dp). Rispetto al telefono:
 *
 * - niente barra superiore Material: il titolo "Proscaenia" e la barra "In riproduzione" stanno in
 *   un'intestazione compatta, così sotto restano visibili almeno due righe di spettacoli complete;
 * - margini di overscan su tutti i lati e schede più piccole (4-5 per riga);
 * - focus iniziale sulla prima scheda (o su quella da cui si era entrati nella scheda di dettaglio,
 *   ricordata nello stato salvato), perché con il telecomando non c'è un "tocco" da cui partire;
 * - niente "tira per aggiornare", che non ha senso senza touch: il catalogo si aggiorna con i
 *   ritentativi automatici già esistenti.
 */
@Composable
private fun TvCatalogScreen(
    onShowSelected: (String) -> Unit,
    onOpenPlayer: (String) -> Unit,
    viewModel: CatalogViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 27.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.catalog_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            NowPlayingBar(onOpenPlayer = onOpenPlayer)
        }
        when (val current = state) {
            CatalogUiState.Loading -> LoadingState()
            is CatalogUiState.Failure -> ErrorState(message = current.message, onRetry = viewModel::retry)
            is CatalogUiState.Content -> TvCatalogContent(
                categories = current.categories.filter { it.shows.isNotEmpty() },
                onShowSelected = onShowSelected,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvCatalogContent(
    categories: List<Category>,
    onShowSelected: (String) -> Unit,
) {
    if (categories.isEmpty()) {
        Text(
            text = stringResource(R.string.catalog_empty),
            modifier = Modifier.padding(TvSafePadding),
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }
    // Ricordo l'ultima scheda con il focus: al ritorno dalla scheda di dettaglio riporto lì il focus
    // invece di ricominciare dalla prima riga.
    var focusedShowKey by rememberSaveable { mutableStateOf<String?>(null) }
    val initialKey = focusedShowKey ?: showKey(categories.first().id, categories.first().shows.first().id)
    val initialFocus = remember { FocusRequester() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(categories, key = { it.id }) { category ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 48.dp),
                )
                LazyRow(
                    // Padding verticale: lascio spazio all'ingrandimento della scheda con il focus,
                    // che altrimenti verrebbe tagliato dai bordi della riga.
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    // Entrando nella riga dall'alto o dal basso il focus torna sull'ultima scheda
                    // visitata della riga, come nelle righe di Android TV.
                    modifier = Modifier.focusRestorer(),
                ) {
                    items(category.shows, key = { it.id }) { show ->
                        val key = showKey(category.id, show.id)
                        ShowCard(
                            show = show,
                            onClick = { onShowSelected(show.id) },
                            width = 168.dp,
                            modifier = Modifier
                                .then(if (key == initialKey) Modifier.focusRequester(initialFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused || it.hasFocus) focusedShowKey = key },
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        // La scheda potrebbe non essere composta (riga fuori schermo): in quel caso il focus parte
        // dal primo elemento alla prima pressione del D-pad, comportamento accettabile.
        runCatching { initialFocus.requestFocus() }
    }
}

/** Uno stesso spettacolo può comparire in più righe: la chiave del focus include la riga. */
private fun showKey(categoryId: String, showId: String) = "$categoryId/$showId"

@Composable
private fun CatalogContent(
    categories: List<Category>,
    onShowSelected: (String) -> Unit,
) {
    val visibleCategories = categories.filter { it.shows.isNotEmpty() }
    if (visibleCategories.isEmpty()) {
        // Anche il messaggio di catalogo vuoto deve stare in un contenitore scorrevole, altrimenti il
        // gesto "tira per aggiornare" non avrebbe nulla da trascinare.
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = stringResource(R.string.catalog_empty),
                    modifier = Modifier.padding(32.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        items(visibleCategories, key = { it.id }) { category ->
            CategoryRow(category = category, onShowSelected = onShowSelected)
        }
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    onShowSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = category.name,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(category.shows, key = { it.id }) { show ->
                ShowCard(show = show, onClick = { onShowSelected(show.id) })
            }
        }
    }
}

@Composable
private fun ShowCard(
    show: ShowSummary,
    onClick: () -> Unit,
    width: Dp = 220.dp,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .width(width)
            .tvFocusHighlight(CardDefaults.shape),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = show.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            show.durationSeconds?.let { seconds ->
                Text(
                    text = formatDuration(seconds),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.75f), MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = show.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp),
        )
    }
}
