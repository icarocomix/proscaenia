package com.icarocomix.proscaenia.ui.common

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.icarocomix.proscaenia.ProscaeniaApp
import com.icarocomix.proscaenia.di.AppContainer

/**
 * Recupero il contenitore delle dipendenze dagli extra di creazione del ViewModel.
 *
 * Passo dall'APPLICATION_KEY, che il framework popola sempre per i ViewModel creati da activity e
 * destinazioni di navigazione, invece di tenere un riferimento statico all'Application: le factory
 * restano così pure funzioni degli extra e funzionano allo stesso modo anche nei test.
 */
fun CreationExtras.appContainer(): AppContainer {
    val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
        "Application non disponibile negli extra di creazione del ViewModel"
    }
    return (application as ProscaeniaApp).container
}
