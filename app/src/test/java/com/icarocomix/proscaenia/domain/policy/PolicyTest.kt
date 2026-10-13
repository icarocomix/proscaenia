package com.icarocomix.proscaenia.domain.policy

import com.icarocomix.proscaenia.domain.model.SavedPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePolicyTest {

    @Test
    fun `senza posizione salvata parto dall'inizio`() {
        assertEquals(0L, ResumePolicy.resumePositionMs(null, 3_600_000))
    }

    @Test
    fun `sotto i 5 secondi riparto dall'inizio`() {
        assertEquals(0L, ResumePolicy.resumePositionMs(SavedPosition(5_000, 3_600_000), null))
    }

    @Test
    fun `a meta spettacolo riprendo dal punto salvato`() {
        assertEquals(1_800_000L, ResumePolicy.resumePositionMs(SavedPosition(1_800_000, 3_600_000), null))
    }

    @Test
    fun `negli ultimi 15 secondi considero lo spettacolo concluso`() {
        assertEquals(0L, ResumePolicy.resumePositionMs(SavedPosition(3_590_000, 3_600_000), null))
    }

    @Test
    fun `senza durata salvata uso quella del catalogo`() {
        assertEquals(0L, ResumePolicy.resumePositionMs(SavedPosition(3_590_000, -1), 3_600_000))
        assertEquals(60_000L, ResumePolicy.resumePositionMs(SavedPosition(60_000, -1), null))
    }
}

class SpeedPolicyTest {

    @Test
    fun `le velocita vanno da 0,5x a 3x a passi di 0,25x`() {
        assertEquals(11, SpeedPolicy.availableSpeeds.size)
        assertEquals(0.5f, SpeedPolicy.availableSpeeds.first())
        assertEquals(3.0f, SpeedPolicy.availableSpeeds.last())
    }

    @Test
    fun `normalizzo al passo piu vicino e dentro i limiti`() {
        assertEquals(1.25f, SpeedPolicy.normalize(1.3f))
        assertEquals(3.0f, SpeedPolicy.normalize(8f))
        assertEquals(0.5f, SpeedPolicy.normalize(0.1f))
        assertEquals(1.0f, SpeedPolicy.normalize(Float.NaN))
    }

    @Test
    fun `sul Chromecast la velocita non supera 2x`() {
        assertEquals(2.0f, SpeedPolicy.capForCast(2.75f))
        assertEquals(1.5f, SpeedPolicy.capForCast(1.5f))
    }
}
