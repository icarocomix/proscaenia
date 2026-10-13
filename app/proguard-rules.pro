# Il provider delle opzioni Cast è referenziato solo per nome nel manifest (meta-data): lo mantengo
# esplicitamente perché R8 non può dedurre l'uso da una stringa.
-keep class androidx.media3.cast.DefaultCastOptionsProvider { *; }
