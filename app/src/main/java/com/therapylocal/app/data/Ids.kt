package com.therapylocal.app.data

/** Client and session ids, which are the primary keys of `clients` and `sessions`. */
object Ids {
    /** Client ids are lowercase slugs without underscores, because a session id starts with `<client>_`. */
    fun clientId(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "").ifEmpty { "client" }

    /** Session ids are `<client>_<yyyymmdd-hhmmss>`; the client is always the part before the first underscore. */
    fun sessionId(client: String, startedAtMs: Long): String =
        "${client}_" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(startedAtMs))
}
