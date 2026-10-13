package com.icarocomix.proscaenia.core.logging

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogSanitizeTest {

    @Test
    fun `rimuovo firma e token dagli URL mantenendo host e percorso`() {
        val raw = "Errore su https://cdn.example.com/video/123/master.m3u8?token=abc&expires=99 (HTTP 403)"
        assertEquals("Errore su https://cdn.example.com/video/123/master.m3u8?… (HTTP 403)", AppLog.sanitize(raw))
    }

    @Test
    fun `lascio invariato il testo senza query string`() {
        val raw = "Richiesta a /api/catalog fallita (HTTP 500)"
        assertEquals(raw, AppLog.sanitize(raw))
    }
}
