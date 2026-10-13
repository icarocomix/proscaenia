package com.icarocomix.proscaenia.core.logging

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Destinazione fisica del file di log.
 *
 * Ho separato due implementazioni perché il modo di rendere un file visibile all'esplora risorse è
 * cambiato con Android 10: da lì in poi lo storage condiviso si scrive solo tramite MediaStore, mentre
 * fino ad Android 9 le app di gestione file possono sfogliare liberamente anche la cartella privata
 * esterna dell'app. Entrambe le strade non richiedono permessi di archiviazione.
 */
internal interface LogFileSink {

    /** Aggiungo [text] in coda al file, ricominciandolo se ha superato [maxBytes]. */
    @Throws(IOException::class)
    fun append(text: String, maxBytes: Long)

    /** Percorso leggibile dall'utente, mostrato nella documentazione e nell'intestazione del log. */
    val displayPath: String

    companion object {
        const val FILE_NAME = "proscaenia-errori.txt"

        fun create(context: Context): LogFileSink =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreDownloadsSink(context) else AppExternalFileSink(context)
    }
}

/**
 * Android 10 e successivi: file nella cartella pubblica Download/Proscaenia, creato tramite
 * MediaStore. Un'app può sempre leggere e modificare i file che ha creato lei stessa in MediaStore
 * senza alcun permesso; memorizzo l'Uri per riaprirlo in append. Se l'utente cancella il file, o se
 * l'app viene reinstallata (e perde la "proprietà" del file precedente), l'apertura fallisce e ne
 * creo uno nuovo invece di perdere i messaggi.
 */
@RequiresApi(Build.VERSION_CODES.Q)
private class MediaStoreDownloadsSink(context: Context) : LogFileSink {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val displayPath: String = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/${LogFileSink.FILE_NAME}"

    override fun append(text: String, maxBytes: Long) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val uri = existingUri() ?: createFile()
        try {
            write(uri, bytes, maxBytes)
        } catch (e: FileNotFoundException) {
            // L'Uri salvato non è più valido (file cancellato dall'utente): ricomincio con un file nuovo.
            forgetUri()
            write(createFile(), bytes, maxBytes)
        } catch (e: SecurityException) {
            // Accade dopo una reinstallazione: il file esiste ma non appartiene più a questa installazione.
            forgetUri()
            write(createFile(), bytes, maxBytes)
        }
    }

    private fun write(uri: Uri, bytes: ByteArray, maxBytes: Long) {
        val currentSize = currentSize(uri)
        // "wa" aggiunge in coda; "wt" tronca e riscrive, ed è la mia rotazione semplice oltre la soglia.
        val mode = if (currentSize + bytes.size > maxBytes) "wt" else "wa"
        resolver.openOutputStream(uri, mode)?.use { it.write(bytes) }
            ?: throw IOException("Impossibile aprire il file di log $uri")
    }

    /**
     * Misuro la dimensione attuale del file per decidere se ruotarlo. MediaStore crea il file fisico
     * solo alla prima apertura in scrittura: subito dopo l'inserimento la voce esiste ma il file no, e
     * aprirlo in lettura fallisce con ENOENT. In quel caso il file è semplicemente vuoto, quindi
     * restituisco 0 e lascio che sia l'apertura in append a crearlo. Un file davvero cancellato
     * dall'utente viene invece intercettato più avanti, quando fallisce proprio l'apertura in scrittura.
     */
    private fun currentSize(uri: Uri): Long =
        try {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (e: FileNotFoundException) {
            0L
        }

    private fun existingUri(): Uri? = prefs.getString(KEY_URI, null)?.toUri() ?: findOwnedFile()?.also(::rememberUri)

    /** Cerco un file con lo stesso nome già creato da questa installazione, per non duplicarlo. */
    private fun findOwnedFile(): Uri? {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(LogFileSink.FILE_NAME, RELATIVE_PATH),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return Uri.withAppendedPath(collection, cursor.getLong(0).toString())
        }
        return null
    }

    private fun createFile(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, LogFileSink.FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("MediaStore non ha creato il file di log")
        rememberUri(uri)
        return uri
    }

    private fun rememberUri(uri: Uri) = prefs.edit { putString(KEY_URI, uri.toString()) }

    private fun forgetUri() = prefs.edit { remove(KEY_URI) }

    private companion object {
        const val FOLDER = "Proscaenia"
        val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"
        const val PREFS_NAME = "error_log"
        // Chiave versionata: fino alla 1.0.0.6 qui era salvato l'Uri del file con il vecchio nome
        // (doppia estensione) e, aggiornando l'app senza disinstallarla, si continuava a scrivere lì.
        // Cambiando chiave il vecchio riferimento viene ignorato e si passa al file con il nome attuale.
        const val KEY_URI = "file_uri_v2"
    }
}

/**
 * Android 8 e 9: file nella cartella esterna dell'app (Android/data/<pacchetto>/files). Su queste
 * versioni è sfogliabile da qualunque esplora risorse, e scriverci non richiede il permesso
 * WRITE_EXTERNAL_STORAGE, che altrimenti dovrei chiedere all'utente solo per i log.
 */
private class AppExternalFileSink(context: Context) : LogFileSink {

    private val file: File = File(context.getExternalFilesDir(null) ?: context.filesDir, LogFileSink.FILE_NAME)

    override val displayPath: String = file.absolutePath

    override fun append(text: String, maxBytes: Long) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (file.length() + bytes.size > maxBytes) file.writeBytes(bytes) else file.appendBytes(bytes)
    }
}
