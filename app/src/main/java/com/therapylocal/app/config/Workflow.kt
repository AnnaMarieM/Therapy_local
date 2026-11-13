package com.therapylocal.app.config

import android.content.Context
import com.therapylocal.app.models.GemmaVariant

/**
 * How a recording becomes facts (docs/AD/AD-22.md). Every workflow diarizes first and then works turn by turn.
 *
 * - [Fast]: Gemma 4 E2B hears each turn and writes the transcript in the language spoken and its English translation in one answer; roles, facts
 *   and everything after read the English and are done by E2B too. No speech recogniser is loaded.
 * - [Quality]: Whisper large-v3 (q8_0, whisper.cpp, CPU) writes English text from each turn (the Whisper translate task); roles, facts and the rest
 *   are done by Gemma 4 E4B. **There is no transcript in the language spoken** for a client who does not speak English.
 * - [QualityCs]: Whisper large-v3 (q8_0, CPU) transcribes each turn in the language spoken (the Whisper transcribe task, no translation); Gemma 4 E4B
 *   translates that text to English in the TRANSLATE job (AD-17); roles, facts and the rest are as in [Quality]. The transcript in the language spoken is kept.
 * - [Canary]: Canary 1B v2 transcribes each turn and Gemma translates the text (AD-17). The behaviour from before the two workflows, kept as the
 *   control the others are compared with and as the default until a person has chosen.
 *
 * An English client is processed the same way in every workflow, with no translation step.
 */
enum class Workflow(val key: String, val gemma: GemmaVariant?) {
    Canary("canary", null),
    Fast("fast", GemmaVariant.E2B),
    Quality("quality", GemmaVariant.E4B),
    QualityCs("quality-cs", GemmaVariant.E4B),
    ;

    /** True when the Gemma build is fixed by the workflow and the Gemma setting does not apply. */
    val fixesGemma: Boolean get() = gemma != null

    /** Fast and Quality work in English whatever the processing language setting says (it keeps its meaning for [Canary]). */
    val forcesEnglish: Boolean get() = this != Canary

    /** True when the stored transcript of a client speaking [clientLanguage] is in that language. Quality has none for a client who does not speak English. */
    fun keepsSourceTranscript(clientLanguage: String): Boolean = this != Quality || clientLanguage.equals("en", ignoreCase = true)

    companion object {
        /** Unchanged behaviour until a person picks one (the UX owner decides how the choice is offered; see the PR). */
        val DEFAULT = Canary

        fun of(key: String?): Workflow = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** The workflow, one for the whole vault, in the app's "settings" preferences (same shape as [ProcessingLanguageSetting]). */
object WorkflowSetting {
    const val PREF = "workflow"

    /** Whisper's beam size for the Quality workflow (1 is greedy); the research default is 5. */
    const val PREF_BEAM = "whisper_beam"
    const val DEFAULT_BEAM = 5

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun get(context: Context): Workflow = Workflow.of(prefs(context).getString(PREF, null))

    fun set(context: Context, workflow: Workflow) {
        prefs(context).edit().putString(PREF, workflow.key).apply()
    }

    fun beam(context: Context): Int = prefs(context).getInt(PREF_BEAM, DEFAULT_BEAM).coerceIn(1, 8)

    fun setBeam(context: Context, beam: Int) {
        prefs(context).edit().putInt(PREF_BEAM, beam.coerceIn(1, 8)).apply()
    }
}
