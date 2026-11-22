package dev.nurec.widget.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore(name = "rec_cache")

class RecRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val state: Flow<UiState> = store.data.map { prefs ->
        UiState(
            snapshot = prefs[KEY_BODY]?.let { runCatching { LiveParser.parse(it) }.getOrNull() },
            lastSuccess = prefs[KEY_SUCCESS_AT]?.let(Instant::ofEpochMilli),
            lastFetchFailed = prefs[KEY_FAILED] ?: false,
            lastAttempt = prefs[KEY_ATTEMPT_AT]?.let(Instant::ofEpochMilli),
        )
    }

    suspend fun current(): UiState = state.first()

    /**
     * Claims the cooldown slot. Returns false if a fetch started less than
     * [UiState.COOLDOWN] ago, in which case the caller must not fetch.
     */
    suspend fun tryStartAttempt(now: Instant = Instant.now()): Boolean {
        var claimed = false
        store.edit { prefs ->
            val last = prefs[KEY_ATTEMPT_AT]?.let(Instant::ofEpochMilli)
            val state = UiState(null, null, false, last)
            if (state.cooldownRemaining(now).isZero) {
                prefs[KEY_ATTEMPT_AT] = now.toEpochMilli()
                claimed = true
            }
        }
        return claimed
    }

    /** Fetches and caches. Returns true on success. Never call from the main thread. */
    suspend fun fetch(): Boolean = withContext(Dispatchers.IO) {
        val body = runCatching { download() }.getOrNull()
        val valid = body?.takeIf { b ->
            runCatching { LiveParser.parse(b).allRooms.isNotEmpty() }.getOrDefault(false)
        }
        store.edit { prefs ->
            if (valid != null) {
                prefs[KEY_BODY] = valid
                prefs[KEY_SUCCESS_AT] = System.currentTimeMillis()
                prefs[KEY_FAILED] = false
            } else {
                prefs[KEY_FAILED] = true
            }
        }
        valid != null
    }

    private fun download(): String {
        val request = Request.Builder().url(ENDPOINT).get().build()
        client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            return resp.body.string()
        }
    }

    companion object {
        const val ENDPOINT = "https://nu-rec-board.netlify.app/api/live"
        private val KEY_BODY = stringPreferencesKey("body")
        private val KEY_SUCCESS_AT = longPreferencesKey("success_at")
        private val KEY_FAILED = booleanPreferencesKey("last_failed")
        private val KEY_ATTEMPT_AT = longPreferencesKey("attempt_at")

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
