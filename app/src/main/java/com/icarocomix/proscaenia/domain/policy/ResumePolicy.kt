package com.icarocomix.proscaenia.domain.policy

import com.icarocomix.proscaenia.domain.model.SavedPosition

/**
 * Regola di ripresa automatica della riproduzione.
 *
 * Riparto dal punto salvato solo se l'utente aveva superato i primi 5 secondi (sotto quella soglia
 * si tratta quasi sempre di un avvio accidentale) e non si trovava negli ultimi 15 secondi, dove di
 * solito scorrono i titoli di coda: in quel caso lo spettacolo è da considerare concluso e lo faccio
 * ripartire dall'inizio. L'ho isolata come funzione pura per poterla verificare con test JVM.
 */
object ResumePolicy {

    const val MIN_RESUME_POSITION_MS = 5_000L
    const val END_EXCLUSION_MS = 15_000L

    /**
     * Uso la durata registrata insieme alla posizione e, se mancante, quella dichiarata dal catalogo.
     * Se nessuna delle due è nota applico solo la soglia iniziale, perché non ho modo di sapere se la
     * posizione cade nel finale.
     */
    fun resumePositionMs(saved: SavedPosition?, catalogDurationMs: Long?): Long {
        if (saved == null || saved.positionMs <= MIN_RESUME_POSITION_MS) return 0L
        val durationMs = saved.durationMs.takeIf { it > 0 } ?: catalogDurationMs?.takeIf { it > 0 }
        if (durationMs != null && saved.positionMs >= durationMs - END_EXCLUSION_MS) return 0L
        return saved.positionMs
    }
}
