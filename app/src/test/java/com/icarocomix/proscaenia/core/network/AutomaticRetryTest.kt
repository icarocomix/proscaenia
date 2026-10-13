package com.icarocomix.proscaenia.core.network

import com.icarocomix.proscaenia.domain.error.ApiException
import com.icarocomix.proscaenia.domain.error.DeviceLimitException
import com.icarocomix.proscaenia.domain.error.InactiveSubscriptionException
import com.icarocomix.proscaenia.domain.error.InvalidCredentialsException
import com.icarocomix.proscaenia.domain.error.MalformedResponseException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class AutomaticRetryTest {

    // Registro le attese richieste invece di dormire davvero: i test restano istantanei.
    private val waits = mutableListOf<Long>()
    private val fakeSleep: suspend (Long) -> Unit = { waits += it }

    @Test
    fun `un errore di rete viene ripetuto finché la chiamata riesce`() = runBlocking {
        var calls = 0
        val result = AutomaticRetry.run("test", retryRejectedSignIn = true, sleep = fakeSleep) {
            calls++
            if (calls < 3) throw SocketTimeoutException("timeout")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
        assertEquals(listOf(1_000L, 2_000L), waits)
    }

    @Test
    fun `dopo cinque tentativi falliti propago l'ultimo errore`() = runBlocking {
        var calls = 0
        try {
            AutomaticRetry.run("test", retryRejectedSignIn = true, sleep = fakeSleep) {
                calls++
                throw IOException("rete assente")
            }
            fail("Atteso un errore")
        } catch (e: IOException) {
            assertEquals("rete assente", e.message)
        }
        assertEquals(5, calls)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), waits)
    }

    @Test
    fun `l'accesso automatico rifiutato viene ripetuto una sola volta`() = runBlocking {
        var calls = 0
        try {
            AutomaticRetry.run("test", retryRejectedSignIn = true, sleep = fakeSleep) {
                calls++
                throw InvalidCredentialsException()
            }
            fail("Atteso un errore")
        } catch (_: InvalidCredentialsException) {
        }
        assertEquals(2, calls)
    }

    @Test
    fun `al primo accesso il rifiuto delle credenziali non viene ripetuto`() = runBlocking {
        var calls = 0
        try {
            AutomaticRetry.run("test", retryRejectedSignIn = false, sleep = fakeSleep) {
                calls++
                throw InvalidCredentialsException()
            }
            fail("Atteso un errore")
        } catch (_: InvalidCredentialsException) {
        }
        assertEquals(1, calls)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `al primo accesso un errore di rete viene comunque ripetuto`() = runBlocking {
        var calls = 0
        AutomaticRetry.run("test", retryRejectedSignIn = false, sleep = fakeSleep) {
            calls++
            if (calls == 1) throw IOException("rete instabile")
        }
        assertEquals(2, calls)
    }

    @Test
    fun `gli errori definitivi non vengono ripetuti`() {
        assertFalse(AutomaticRetry.isTransient(ApiException(404, "assente")))
        assertFalse(AutomaticRetry.isTransient(MalformedResponseException("formato")))
        assertFalse(AutomaticRetry.isTransient(InactiveSubscriptionException()))
        assertFalse(AutomaticRetry.isTransient(DeviceLimitException()))
    }

    @Test
    fun `gli errori momentanei del server vengono ripetuti`() {
        assertTrue(AutomaticRetry.isTransient(ApiException(500, "errore")))
        assertTrue(AutomaticRetry.isTransient(ApiException(503, "manutenzione")))
        assertTrue(AutomaticRetry.isTransient(ApiException(429, "troppe richieste")))
        assertTrue(AutomaticRetry.isTransient(ApiException(408, "timeout")))
        assertTrue(AutomaticRetry.isTransient(IOException("connessione interrotta")))
    }
}
