package com.therapylocal.app.engine.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProofTest {
    private val good = """[
  {"kategorie": "zvíře", "text": "Jana má psa Rexe."},
  {"kategorie": "práce", "text": "Pracuje jako učitelka v Brně."},
  {"kategorie": "koníček", "text": "Ráda plave."},
  {"kategorie": "rodina", "text": "Má dceru Elišku."}
]"""

    private fun fail(reply: String) = (LlmProof.check(reply) as LlmProof.Verdict.Fail).reason

    @Test fun good_reply_passes() = assertEquals(LlmProof.Verdict.Ok, LlmProof.check(good))

    @Test fun code_fenced_json_passes() {
        assertEquals(LlmProof.Verdict.Ok, LlmProof.check("```json\n$good\n```"))
        assertEquals(LlmProof.Verdict.Ok, LlmProof.check("  \n```\n$good\n```  \n"))
    }

    @Test fun whitespace_and_one_line_variants_pass() {
        val oneLine = good.replace(Regex("\\s*\\n\\s*"), "")
        assertEquals(LlmProof.Verdict.Ok, LlmProof.check(oneLine))
        assertEquals(LlmProof.Verdict.Ok, LlmProof.check("\t$oneLine\r\n"))
    }

    @Test fun wording_and_key_order_may_differ() {
        val reply = """[{"text":"Učitelka z Brna","kategorie":"Práce"},{"text":"pes REX","kategorie":"zvire"},
            {"text":"plavání: ráda plave","kategorie":"KONÍČEK"},{"text":"dcera Eliška","kategorie":"rodina"}]"""
        assertEquals(LlmProof.Verdict.Ok, LlmProof.check(reply))
    }

    @Test fun diacritics_missing_in_reply_still_match() {
        val reply = good.replace("Elišku", "Eliska").replace("učitelka", "ucitelka").replace("Brně", "Brne")
        assertEquals(LlmProof.Verdict.Ok, LlmProof.check(reply))
    }

    /** The shape of issue #48: one object, several keys repeated, instead of four objects. Synthetic text. */
    @Test fun issue_48_one_object_with_duplicate_keys_fails() {
        val reply = """[{"kategorie": "zvíře", "text": "Jana má psa Rexe.", "kategorie": "práce", "text": "Pracuje jako učitelka v Brně.", "kategorie": "koníček", "text": "Ráda plave."}]"""
        assertEquals("duplicate_key", fail(reply))
        assertEquals("duplicate_key", fail("```json\n$reply\n```"))
    }

    @Test fun duplicate_key_in_otherwise_valid_array_fails() {
        assertEquals("duplicate_key", fail(good.replace("""{"kategorie": "rodina", "text": "Má dceru Elišku."}""", """{"text": "Má dceru Elišku.", "text": "Má dceru Elišku."}""")))
    }

    @Test fun truncated_json_fails() {
        assertEquals("invalid_json", fail(good.substring(0, good.length - 30)))
        assertEquals("invalid_json", fail("```json\n" + good.substring(0, 120)))
    }

    @Test fun prose_instead_of_json_fails() {
        assertEquals("invalid_json", fail("OK"))
        assertEquals("invalid_json", fail("Tady je JSON: $good"))
        assertEquals("invalid_json", fail("$good a ještě něco"))
    }

    @Test fun empty_reply_fails() {
        assertEquals("empty_reply", fail(""))
        assertEquals("empty_reply", fail("   \n"))
        assertEquals("empty_reply", fail("```json\n```"))
    }

    @Test fun wrong_count_fails() {
        assertEquals("wrong_count", fail("""[{"kategorie":"zvíře","text":"Jana má psa Rexe."}]"""))
        assertEquals("wrong_count", fail("[]"))
        assertEquals("wrong_count", fail(good.trimEnd(']') + """,{"kategorie":"a","text":"b"}]"""))
    }

    @Test fun wrong_keys_fail() {
        assertEquals("wrong_keys", fail(good.replace("kategorie", "category")))
        assertEquals("wrong_keys", fail(good.replace("""{"kategorie": "rodina", ""","""{"extra": "1", "kategorie": "rodina", """)))
    }

    @Test fun missing_fact_names_its_number() {
        assertEquals("missing_fact:1", fail(good.replace("Rexe", "psa")))
        assertEquals("missing_fact:2", fail(good.replace("Brně", "městě")))
        assertEquals("missing_fact:3", fail(good.replace("Ráda plave.", "Ráda čte.")))
        assertEquals("missing_fact:4", fail(good.replace("Elišku", "dceru")))
    }

    @Test fun non_string_value_fails_as_invalid() {
        assertEquals("invalid_json", fail(good.replace(""""Ráda plave."""", "3")))
    }

    /** The shape the Adreno 650 gave back to the first proof: valid, four objects, right keys and facts, but garbled category words. */
    @Test fun garbled_category_words_fail() {
        assertEquals("wrong_category:4", fail(good.replace("rodina", "Rodzina")))
        assertEquals("wrong_category:2", fail(good.replace("práce", "Pracování")))
        assertEquals("wrong_category:3", fail(good.replace("koníček", "Hobby")))
    }

    @Test fun empty_value_fails() = assertEquals("empty_value", fail(good.replace(""""zvíře"""", """" """")))

    @Test fun reasons_never_carry_reply_text() {
        val r = fail("""[{"kategorie":"tajné jméno","text":"x","text":"y"}]""")
        assertTrue(r, r == "duplicate_key")
    }

    @Test fun the_prompt_contains_every_fact_and_a_fact_has_key_words() {
        LlmProof.FACTS.forEach { assertTrue(LlmProof.USER.contains(it)) }
        assertEquals(LlmProof.FACTS.size, LlmProof.CATEGORIES.size)
        LlmProof.CATEGORIES.forEach { assertTrue(LlmProof.SYSTEM.contains(it)) }
        assertEquals(LlmProof.FACTS.size, LlmProof.KEY_WORDS.size)
        LlmProof.FACTS.zip(LlmProof.KEY_WORDS).forEach { (f, ws) -> ws.forEach { assertTrue("$f / $it", LlmProof.normalise(f).contains(it)) } }
    }
}
