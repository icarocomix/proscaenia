package com.icarocomix.proscaenia.core.logging

import android.content.Context
import android.os.Build
import android.util.Log
import com.icarocomix.proscaenia.BuildConfig
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Registro degli errori dell'app: scrive su logcat e, per avvisi ed errori, su un file di testo
 * raggiungibile dall'esplora risorse dello smartphone, pensato per essere inoltrato a chi sviluppa.
 *
 * L'ho reso un oggetto globale, come `android.util.Log`, perché il logging è una preoccupazione
 * trasversale usata anche da classi che non passano dal contenitore delle dipendenze (per esempio il
 * gestore delle eccezioni non catturate); viene inizializzato una sola volta dall'Application.
 *
 * Le scritture normali passano da un executor a thread singolo, così nessuna operazione su file
 * avviene sul thread principale e l'ordine dei messaggi è preservato. L'unica eccezione è il crash:
 * in quel caso scrivo in modo sincrono sul thread che sta morendo, perché il processo termina subito
 * dopo e un compito ancora in coda andrebbe perso proprio quando è più prezioso.
 *
 * Prima di scrivere ripulisco il testo: tolgo la query string dagli URL, dove le piattaforme di
 * streaming mettono firme e token di accesso. Le credenziali non arrivano mai qui, perché nessuna
 * classe le registra e il loro `toString` le maschera comunque.
 */
object AppLog {

    private const val MAX_FILE_BYTES = 1_024L * 1_024L
    private val URL_QUERY = Regex("""(https?://[^\s?#"']+)[?#][^\s"']*""")
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private val lock = Any()

    @Volatile
    private var sink: LogFileSink? = null
    private var executor: ExecutorService? = null

    /** Percorso del file mostrabile all'utente, disponibile dopo [init]. */
    val filePath: String? get() = sink?.displayPath

    fun init(context: Context) {
        if (sink != null) return
        sink = LogFileSink.create(context)
        executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "AppLog").apply { isDaemon = true } }
        writeAsync(sessionHeader())
        installCrashHandler()
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        writeAsync(format("W", tag, message, error))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        writeAsync(format("E", tag, message, error))
    }

    private fun writeAsync(entry: String) {
        val currentExecutor = executor ?: return
        try {
            currentExecutor.execute { writeNow(entry) }
        } catch (e: RuntimeException) {
            // Executor già chiuso (processo in chiusura): il messaggio resta comunque su logcat.
            Log.w("AppLog", "Impossibile accodare la scrittura del log", e)
        }
    }

    /**
     * Scrittura effettiva, serializzata dal lock perché può arrivare sia dall'executor sia, in caso di
     * crash, da qualunque altro thread. Un errore di scrittura non deve mai propagarsi: il log è uno
     * strumento diagnostico e non può essere la causa di un malfunzionamento dell'app.
     */
    private fun writeNow(entry: String) {
        val currentSink = sink ?: return
        synchronized(lock) {
            try {
                currentSink.append(entry, MAX_FILE_BYTES)
            } catch (e: Exception) {
                Log.w("AppLog", "Scrittura del file di log non riuscita", e)
            }
        }
    }

    /**
     * Registro il crash e poi passo il controllo al gestore precedente (quello di sistema), così il
     * comportamento visibile all'utente resta identico: messaggio di arresto e chiusura dell'app.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("AppLog", "Arresto anomalo sul thread ${thread.name}", throwable)
            writeNow(format("CRASH", "AppLog", "Arresto anomalo sul thread ${thread.name}", throwable))
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun format(level: String, tag: String, message: String, error: Throwable?): String {
        val builder = StringBuilder()
            .append(LocalDateTime.now().format(TIMESTAMP)).append(' ')
            .append(level).append('/').append(tag).append(": ")
            .append(message).append('\n')
        if (error != null) {
            val trace = StringWriter()
            error.printStackTrace(PrintWriter(trace))
            builder.append(trace).append('\n')
        }
        return sanitize(builder.toString())
    }

    private fun sessionHeader(): String =
        "\n===== ${LocalDateTime.now().format(TIMESTAMP)} · Proscaenia ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) =====\n"

    internal fun sanitize(text: String): String = URL_QUERY.replace(text) { "${it.groupValues[1]}?…" }
}
