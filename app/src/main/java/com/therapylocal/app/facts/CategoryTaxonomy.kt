package com.therapylocal.app.facts

/**
 * Standardised clinical taxonomy for psychotherapy.
 * Provides canonical categories and anchor descriptions used to stabilize fact clustering.
 */
object CategoryTaxonomy {
    data class CanonicalCategory(
        val name: String,
        val anchorText: String,
        val description: String,
    )

    val CANONICAL_CS = listOf(
        CanonicalCategory(
            name = "Rodina a vztahy",
            anchorText = "rodina, partner, manželství, děti, rodiče, sourozenci, přátelé, mezilidské vztahy, rozvod, konflikty ve vztahu",
            description = "Rodinné a partnerské zázemí, přátelství, sociální vazby",
        ),
        CanonicalCategory(
            name = "Práce a kariéra",
            anchorText = "zaměstnání, práce, kariéra, škola, studium, kolegové, nadřízený, finance, vyhoření, pracovní stres",
            description = "Pracovní a studijní prostředí, finance, profesní zátěž",
        ),
        CanonicalCategory(
            name = "Zdraví, tělo a spánek",
            anchorText = "fyzické zdraví, nemoci, bolesti, spánek, nespavost, únava, léky, medikace, strava, tělesné příznaky",
            description = "Somatický stav, spánková hygiena, medikace, životospráva",
        ),
        CanonicalCategory(
            name = "Nálada a emoce",
            anchorText = "úzkost, strach, panika, deprese, smutek, vztek, bezmoc, pocity viny, emoční výkyvy, nálada",
            description = "Emoční prožívání, afektivní stavy, úzkostné a depresivní symptomy",
        ),
        CanonicalCategory(
            name = "Historie a události",
            anchorText = "dětství, vzpomínky, formativní zážitky, úmrtí, ztráta, trauma, rozchod, stěhování, minulé události",
            description = "Biografická historie, formativní události, ztráty a traumata",
        ),
        CanonicalCategory(
            name = "Závislosti a návyky",
            anchorText = "alkohol, drogy, kouření, léky, hazard, přejídání, digitální závislosti, nutkavé chování, návyky",
            description = "Užívání návykových látek, behaviorální závislosti, škodlivé stereotypy",
        ),
        CanonicalCategory(
            name = "Zdroje a zvládání",
            anchorText = "koníčky, záliby, co pomáhá, relaxace, silné stránky, opory, cíle terapie, posun, úspěchy, zvládání",
            description = "Copingové strategie, vnitřní a vnější zdroje, zájmy, terapeutické cíle",
        ),
        CanonicalCategory(
            name = "Osobní témata a sebepojetí",
            anchorText = "sebeúcta, sebehodnocení, identita, hranice, vnímání sebe sama, vnitřní kritik, sebeobraz",
            description = "Vnímání vlastní hodnoty, osobní hranice, sebepřijetí",
        ),
    )

    val CANONICAL_EN = listOf(
        CanonicalCategory(
            name = "Family & Relationships",
            anchorText = "family, partner, spouse, marriage, children, parents, siblings, friends, interpersonal relationships, breakup",
            description = "Family background, romantic relationships, friendships, social network",
        ),
        CanonicalCategory(
            name = "Work & Career",
            anchorText = "work, job, career, school, studies, colleagues, supervisor, finances, burnout, workplace stress",
            description = "Work environment, academic life, financial stress, career burnout",
        ),
        CanonicalCategory(
            name = "Health, Body & Sleep",
            anchorText = "physical health, illness, pain, sleep, insomnia, fatigue, medications, diet, somatic symptoms",
            description = "Physical well-being, sleep patterns, medication, somatic complaints",
        ),
        CanonicalCategory(
            name = "Mood & Emotions",
            anchorText = "anxiety, fear, panic, depression, sadness, anger, helplessness, guilt, emotional swings, mood",
            description = "Emotional states, affective symptoms, panic, sadness, mood regulation",
        ),
        CanonicalCategory(
            name = "History & Life Events",
            anchorText = "childhood, memories, formative experiences, bereavement, loss, trauma, relocation, past milestones",
            description = "Biographical history, past trauma, formative experiences, grief",
        ),
        CanonicalCategory(
            name = "Addictions & Habits",
            anchorText = "alcohol, substances, drugs, smoking, medications, gambling, binge eating, behavioral habits",
            description = "Substance use, behavioral addictions, compulsive patterns",
        ),
        CanonicalCategory(
            name = "Coping & Strengths",
            anchorText = "hobbies, what helps, relaxation, strengths, resilience, support network, therapy goals, progress",
            description = "Coping mechanisms, internal and external resources, progress, hobbies",
        ),
        CanonicalCategory(
            name = "Self-concept & Identity",
            anchorText = "self-esteem, self-worth, identity, boundaries, self-perception, inner critic, personal values",
            description = "Self-esteem, boundaries, personal identity, self-image",
        ),
    )

    fun canonicalFor(language: String): List<CanonicalCategory> =
        if (language == PromptLanguage.CS) CANONICAL_CS else CANONICAL_EN

    fun defaultCategory(language: String): String =
        if (language == PromptLanguage.CS) "Osobní témata" else "Personal topics"

    /**
     * Checks whether [candidate] matches any canonical category name (ignoring case, accents, and punctuation).
     * If matched, returns the standard canonical name; otherwise null.
     */
    fun findCanonical(candidate: String, language: String): String? {
        val norm = normalise(candidate)
        if (norm.isBlank()) return null
        return canonicalFor(language).firstOrNull { normalise(it.name) == norm }?.name
    }

    private fun normalise(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .lowercase()
        .replace(Regex("\\b(and|a)\\b"), "")
        .replace(Regex("[^a-z0-9]"), "")
        .trim()
}
