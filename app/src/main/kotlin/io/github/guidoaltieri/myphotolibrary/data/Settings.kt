package io.github.guidoaltieri.myphotolibrary.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Preferenze dell'app (come settings.json di ArchPhotos), osservabili dall'interfaccia. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    inner class Pref<T>(private val key: String, private val default: T) {
        @Suppress("UNCHECKED_CAST")
        private fun read(): T = when (default) {
            is Boolean -> prefs.getBoolean(key, default) as T
            is Int -> prefs.getInt(key, default) as T
            is Long -> prefs.getLong(key, default) as T
            is Float -> prefs.getFloat(key, default) as T
            is String -> (prefs.getString(key, default) ?: default) as T
            else -> default
        }

        private val state = MutableStateFlow(read())
        val flow: StateFlow<T> get() = state
        var value: T
            get() = state.value
            set(v) {
                state.value = v
                prefs.edit().apply {
                    when (v) {
                        is Boolean -> putBoolean(key, v)
                        is Int -> putInt(key, v)
                        is Long -> putLong(key, v)
                        is Float -> putFloat(key, v)
                        is String -> putString(key, v)
                    }
                }.apply()
            }
    }

    /** system | light | dark | black */
    val theme = Pref("theme", "system")
    /** nomi dei luoghi da OpenStreetMap (solo coordinate arrotondate a ~1 km) */
    val placeNames = Pref("place_names", true)
    /** data e luogo modificati vengono scritti anche nei file (EXIF) */
    val writeFiles = Pref("write_files", false)
    /** colonne della griglia «Tutte le foto» */
    val columns = Pref("columns", 4)
    /** vista della libreria: years | months | days | all */
    val libraryLevel = Pref("library_level", "all")
    /** riconoscimento dei volti attivo */
    val faces = Pref("faces", true)
    /** schema delle cartelle per le importazioni */
    val importLayout = Pref("import_layout", "{anno}/{mese}")
    val importUseExisting = Pref("import_use_existing", true)
    val importDeleteAfter = Pref("import_delete_after", false)
    /** durata di ogni foto nella presentazione (secondi) */
    val slideSeconds = Pref("slide_seconds", 3)
    val firstRun = Pref("first_run", true)
}
