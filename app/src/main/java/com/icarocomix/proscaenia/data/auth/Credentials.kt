package com.icarocomix.proscaenia.data.auth

/**
 * Coppia username/password della piattaforma.
 *
 * Ridefinisco `toString` perché la versione generata da `data class` stamperebbe la password in
 * chiaro in qualunque log o messaggio di eccezione che includa l'oggetto per distrazione.
 */
data class Credentials(
    val username: String,
    val password: String,
) {
    override fun toString(): String = "Credentials(username=$username, password=***)"
}
