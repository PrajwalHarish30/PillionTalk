package com.prajwal.pilliontalk.profile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Your display name: what the other phone shows (the name on their tile).
 *
 * Stored in SharedPreferences: Android's simple key-value file, kept between app launches.
 * (Flutter equivalent: the shared_preferences package, which uses this exact thing on Android.)
 */
class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    private val _name = MutableStateFlow(prefs.getString(KEY_NAME, "").orEmpty())
    val name: StateFlow<String> = _name.asStateFlow()

    fun setName(value: String) {
        val clean = value.trim().take(MAX_NAME_LENGTH)
        prefs.edit().putString(KEY_NAME, clean).apply() // apply() saves in the background
        _name.value = clean
    }

    companion object {
        private const val KEY_NAME = "name"
        const val MAX_NAME_LENGTH = 20
    }
}

/** "Captain Wheelie" → "CW", "wheelie" → "WH". For the avatar circles. */
fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}
