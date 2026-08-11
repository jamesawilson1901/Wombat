package com.magpie.data

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.Calendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.magpieDataStore by preferencesDataStore(name = "magpie_settings")

data class SettingsSnapshot(
    val apiKeyCipher: String,
    val model: String,
    val spendCapUsd: Double,
    val monthSpendUsd: Double,
    val allTimeSpendUsd: Double,
    val callCount: Int,
    val monthKey: String,
    val inputRatePerMTok: Double,
    val outputRatePerMTok: Double,
    val debounceSeconds: Int,
    val sweepEnabled: Boolean,
    val sweepDestination: String,
    val watchedFolders: Set<String>,
    val setupComplete: Boolean,
    val presetWizardDone: Boolean,
    val apiCardDismissed: Boolean,
    val colorOsConfirmed: Boolean,
) {
    val hasApiKey: Boolean get() = apiKeyCipher.isNotEmpty()
    val capReached: Boolean get() = monthSpendUsd >= spendCapUsd
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val API_KEY_CIPHER = stringPreferencesKey("api_key_cipher")
        val MODEL = stringPreferencesKey("model")
        val SPEND_CAP = doublePreferencesKey("spend_cap_usd")
        val MONTH_SPEND = doublePreferencesKey("month_spend_usd")
        val ALL_TIME_SPEND = doublePreferencesKey("all_time_spend_usd")
        val CALL_COUNT = intPreferencesKey("call_count")
        val MONTH_KEY = stringPreferencesKey("month_key")
        val INPUT_RATE = doublePreferencesKey("input_rate_per_mtok")
        val OUTPUT_RATE = doublePreferencesKey("output_rate_per_mtok")
        val DEBOUNCE_SECONDS = intPreferencesKey("debounce_seconds")
        val SWEEP_ENABLED = booleanPreferencesKey("sweep_enabled")
        val SWEEP_DEST = stringPreferencesKey("sweep_destination")
        val WATCHED = stringSetPreferencesKey("watched_folders")
        val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val PRESET_WIZARD_DONE = booleanPreferencesKey("preset_wizard_done")
        val API_CARD_DISMISSED = booleanPreferencesKey("api_card_dismissed")
        val COLOROS_CONFIRMED = booleanPreferencesKey("coloros_confirmed")
        val LAST_ERROR = stringPreferencesKey("last_api_error")
        val LAST_ERROR_AT = longPreferencesKey("last_api_error_at")
    }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-4-5-20251001"
        // Claude Haiku 4.5 list rates; editable in settings because they change.
        const val DEFAULT_INPUT_RATE = 1.0
        const val DEFAULT_OUTPUT_RATE = 5.0
        const val DEFAULT_SPEND_CAP = 2.0
        const val DEFAULT_DEBOUNCE = 10

        val storageRoot: String
            get() = Environment.getExternalStorageDirectory().absolutePath

        fun defaultWatched(): Set<String> = setOf(
            "$storageRoot/Download",
            "$storageRoot/DCIM/Screenshots",
            "$storageRoot/Pictures/Screenshots",
        )

        fun defaultSweepDest(): String = "$storageRoot/Pictures/Screenshots"

        fun currentMonthKey(): String {
            val cal = Calendar.getInstance()
            return "%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
        }
    }

    val snapshots: Flow<SettingsSnapshot> = context.magpieDataStore.data.map { p ->
        SettingsSnapshot(
            apiKeyCipher = p[Keys.API_KEY_CIPHER] ?: "",
            model = p[Keys.MODEL] ?: DEFAULT_MODEL,
            spendCapUsd = p[Keys.SPEND_CAP] ?: DEFAULT_SPEND_CAP,
            monthSpendUsd = if ((p[Keys.MONTH_KEY] ?: "") == currentMonthKey()) p[Keys.MONTH_SPEND] ?: 0.0 else 0.0,
            allTimeSpendUsd = p[Keys.ALL_TIME_SPEND] ?: 0.0,
            callCount = p[Keys.CALL_COUNT] ?: 0,
            monthKey = p[Keys.MONTH_KEY] ?: currentMonthKey(),
            inputRatePerMTok = p[Keys.INPUT_RATE] ?: DEFAULT_INPUT_RATE,
            outputRatePerMTok = p[Keys.OUTPUT_RATE] ?: DEFAULT_OUTPUT_RATE,
            debounceSeconds = p[Keys.DEBOUNCE_SECONDS] ?: DEFAULT_DEBOUNCE,
            sweepEnabled = p[Keys.SWEEP_ENABLED] ?: true,
            sweepDestination = p[Keys.SWEEP_DEST] ?: defaultSweepDest(),
            watchedFolders = p[Keys.WATCHED] ?: defaultWatched(),
            setupComplete = p[Keys.SETUP_COMPLETE] ?: false,
            presetWizardDone = p[Keys.PRESET_WIZARD_DONE] ?: false,
            apiCardDismissed = p[Keys.API_CARD_DISMISSED] ?: false,
            colorOsConfirmed = p[Keys.COLOROS_CONFIRMED] ?: false,
        )
    }

    suspend fun snapshot(): SettingsSnapshot = snapshots.first()

    val lastApiError: Flow<Pair<String, Long>?> = context.magpieDataStore.data.map { p ->
        val msg = p[Keys.LAST_ERROR]
        if (msg.isNullOrEmpty()) null else msg to (p[Keys.LAST_ERROR_AT] ?: 0L)
    }

    suspend fun setApiKeyCipher(cipher: String) = edit { it[Keys.API_KEY_CIPHER] = cipher }
    suspend fun setModel(model: String) = edit { it[Keys.MODEL] = model.trim() }
    suspend fun setSpendCap(usd: Double) = edit { it[Keys.SPEND_CAP] = usd }
    suspend fun setRates(input: Double, output: Double) = edit {
        it[Keys.INPUT_RATE] = input
        it[Keys.OUTPUT_RATE] = output
    }

    suspend fun setDebounceSeconds(seconds: Int) = edit { it[Keys.DEBOUNCE_SECONDS] = seconds.coerceIn(2, 60) }
    suspend fun setSweepEnabled(enabled: Boolean) = edit { it[Keys.SWEEP_ENABLED] = enabled }
    suspend fun setSweepDestination(path: String) = edit { it[Keys.SWEEP_DEST] = path }
    suspend fun setWatchedFolders(folders: Set<String>) = edit { it[Keys.WATCHED] = folders }
    suspend fun setSetupComplete(done: Boolean) = edit { it[Keys.SETUP_COMPLETE] = done }
    suspend fun setPresetWizardDone(done: Boolean) = edit { it[Keys.PRESET_WIZARD_DONE] = done }
    suspend fun setApiCardDismissed(dismissed: Boolean) = edit { it[Keys.API_CARD_DISMISSED] = dismissed }
    suspend fun setColorOsConfirmed(confirmed: Boolean) = edit { it[Keys.COLOROS_CONFIRMED] = confirmed }

    // API errors are never surfaced as blocking dialogs (§6.3); they land here
    // and the log screen shows a small dismissible line.
    suspend fun recordApiError(message: String) = edit {
        it[Keys.LAST_ERROR] = message
        it[Keys.LAST_ERROR_AT] = System.currentTimeMillis()
    }

    suspend fun clearApiError() = edit {
        it[Keys.LAST_ERROR] = ""
        it[Keys.LAST_ERROR_AT] = 0L
    }

    suspend fun addSpend(costUsd: Double) = edit { p ->
        val month = currentMonthKey()
        val monthSpend = if ((p[Keys.MONTH_KEY] ?: "") == month) p[Keys.MONTH_SPEND] ?: 0.0 else 0.0
        p[Keys.MONTH_KEY] = month
        p[Keys.MONTH_SPEND] = monthSpend + costUsd
        p[Keys.ALL_TIME_SPEND] = (p[Keys.ALL_TIME_SPEND] ?: 0.0) + costUsd
        p[Keys.CALL_COUNT] = (p[Keys.CALL_COUNT] ?: 0) + 1
    }

    suspend fun resetMonthSpend() = edit { p ->
        p[Keys.MONTH_KEY] = currentMonthKey()
        p[Keys.MONTH_SPEND] = 0.0
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.magpieDataStore.edit { block(it) }
    }
}
