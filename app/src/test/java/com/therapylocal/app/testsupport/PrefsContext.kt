package com.therapylocal.app.testsupport

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

/** In-memory [SharedPreferences]: [commits] false makes every commit fail, as a full or read-only disk does. */
class MapPrefs(val values: MutableMap<String, Any?> = LinkedHashMap(), var commits: Boolean = true) : SharedPreferences {
    override fun getAll(): Map<String, *> = LinkedHashMap(values)
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as Set<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = LinkedHashMap<String, Any?>()
        private val removes = HashSet<String>()
        private fun put(key: String, value: Any?) = apply { puts[key] = value }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: Set<String>?) = put(key, values)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = apply { removes += key }
        override fun clear() = apply { removes += values.keys }
        override fun commit(): Boolean {
            if (!commits) return false
            removes.forEach { values.remove(it) }
            values.putAll(puts)
            return true
        }
        override fun apply() {
            commit()
        }
    }
}

/** A [Context] with one [prefs] store for every name and [filesDir] as its external files dir. */
class PrefsContext(val prefs: MapPrefs = MapPrefs(), private val filesDir: File? = null) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    override fun getExternalFilesDir(type: String?): File? = filesDir
}
