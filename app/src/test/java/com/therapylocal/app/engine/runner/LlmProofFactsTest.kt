package com.therapylocal.app.engine.runner

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The second proof (LlmProof.checkFacts), against the real raw replies of the SM-T970 tablet (Adreno 650, LiteRT-LM 0.18.1, greedy), issue #48.
 * The replies are to a made-up text, no patient data. Measured: the CPU passes; the generic Gemma file on the GPU answers ONE merged object (the
 * bug that "Reply with OK." and the first reformat proof let through); the -gpu file on the GPU answers garbage; FLOAT32 activations give three objects on both files.
 */
class LlmProofFactsTest {
    private val cpu = """```json
{
  "Klíč": "Petra",
  "Obsah": "Pocit vzdálenosti od sestry Lucie po přestěhování do Brna.",
  "Důkaz": "Moje sestra Lucie se přestěhovala do Brna a já mám pocit, že jsme si s ní teď strašně vzdálené."
}
```
```json
{
  "Klíč": "Stav psychické/fyzické",
  "Obsah": "Změna rytmu spánku a nespánky.",
  "Důkaz": "Usínám až po půlnoci a v pět ráno se budím."
}
```
```json
{
  "Klíč": "Chorobné/adaptivní chování",
  "Obsah": "Zvýšená příjem kávé.",
  "Důkaz": "Pila jsem proto víc kávy, tři až čtyři hrnky denně."
}
```"""

    private val gpuGeneric = """```json
{
  "Téma": "Vztah s sestrou a pocit vzdálenosti",
  "Obsah": "Klientka cítí, že se s sestrou Lucie teď velmi vzdálená.",
  "Důvod": "Přesun sestry Lucie do Brna.",
  "Důsledky": "Změna rytmu spánku (usínání až po půlnoci a budí se v pět ráno).",
  "Problémový chování": "Zvýšená dávka kafe (tři až čtyři hrnky denně) jako reakce na změnu.",
  "Způsobit": "Pila jsem proto víc kávy, tři až čtyři hrnky denně."
}
```"""

    private val gpuFile = """Zat zdeतरहЦена"""

    private val gpuGenericF32 = """```json
{
  "Klíč": "Petra",
  "Obsah": "Pocit vzdálenosti od sestry Lucie po přestěhování do Brna.",
  "Důkaz": "Moje sestra Lucie se přestěhovala do Brna a já mám pocit, že jsme si s ní teď strašně vzdálené."
}
```
```json
{
  "Klíč": "Petra",
  "Obsah": "Změna snů a porovnání s předchozím stavem.",
  "Důkaz": "Usínám až po půlnoci a v pět ráno se budím."
}
```
```json
{
  "Klíč": "Petra",
  "Obsah": "Zvýšená spotřeba kávé.",
  "Důkaz": "Pila jsem proto víc kávy, tři až čtyři hrnky denně."
}
```"""

    private val gpuFileF32 = """```json
{
  "Klíč": "Petra",
  "Znění": "Moje sestra Lucie se přestěhovala do Brna a já mám pocit, že jsme si s ní teď strašně vzdálené.",
  "Důkaz": "Moje sestra Lucie se přestěhovala do Brna a já mám pocit, že jsme si s ní teď strašně vzdálené."
}
```
```json
{
  "Klíč": "Petra",
  "Znění": "Usínám až po půlnoci a v pět ráno se budím.",
  "Důkaz": "Usínám až po půlnoci a v pět ráno se budím."
}
```
```json
{
  "Klíč": "Petra",
  "Znění": "Pila jsem proto víc kávy, tři až čtyři hrnky denně.",
  "Důkaz": "Pila jsem proto víc kávy, tři až čtyři hrnky denně."
}
```"""

    private fun fail(reply: String) = (LlmProof.checkFacts(reply) as LlmProof.Verdict.Fail).reason

    @Test fun the_cpu_reply_passes() = assertEquals(LlmProof.Verdict.Ok, LlmProof.checkFacts(cpu))

    @Test fun float32_replies_pass_on_both_files() {
        assertEquals(LlmProof.Verdict.Ok, LlmProof.checkFacts(gpuGenericF32))
        assertEquals(LlmProof.Verdict.Ok, LlmProof.checkFacts(gpuFileF32))
    }

    @Test fun the_generic_file_on_the_gpu_is_one_merged_object_and_fails() = assertEquals("too_few_objects", fail(gpuGeneric))

    @Test fun the_gpu_file_on_the_gpu_is_garbage_and_fails() = assertEquals("invalid_json", fail(gpuFile))

    @Test fun empty_reply_fails() = assertEquals("empty_reply", fail("  \n```\n"))

    @Test fun a_repeated_key_fails() {
        val r = """{"a": "x", "a": "y", "c": "z"}""" + "\n" + """{"a": "x", "b": "y", "c": "z"}""" + "\n" + """{"a": "x", "b": "y", "c": "z"}"""
        assertEquals("duplicate_key", fail(r))
    }

    @Test fun an_object_with_four_keys_fails() {
        val three = """{"h": "a", "c": "b", "e": "Usínám až po půlnoci a v pět ráno se budím."}"""
        val four = """{"h": "a", "c": "b", "d": "c", "e": "Usínám až po půlnoci a v pět ráno se budím."}"""
        assertEquals("wrong_keys", fail("$three\n$three\n$four"))
    }

    @Test fun a_quote_that_is_not_in_the_text_fails() {
        val ok = LlmProof.SENTENCES.map { """{"h": "a", "c": "b", "e": "$it"}""" }
        assertEquals(LlmProof.Verdict.Ok, LlmProof.checkFacts(ok.joinToString("\n")))
        val bad = ok.take(2) + """{"h": "a", "c": "b", "e": "Chodím na dlouhé procházky."}"""
        assertEquals("evidence_not_quote:3", fail(bad.joinToString("\n")))
    }

    @Test fun the_same_quote_twice_fails() {
        val one = """{"h": "a", "c": "b", "e": "${LlmProof.SENTENCES[0]}"}"""
        assertEquals("repeated_evidence", fail("$one\n$one\n$one"))
    }

    @Test fun an_array_and_case_and_diacritics_are_accepted() {
        val items = LlmProof.SENTENCES.joinToString(",") { """{"h": "a", "c": "b", "e": "${it.uppercase()}"}""" }
        assertEquals(LlmProof.Verdict.Ok, LlmProof.checkFacts("[$items]"))
    }

    @Test fun a_non_string_value_fails() =
        assertEquals("invalid_json", fail("""{"h": "a", "c": ["x"], "e": "y"}"""))
}
