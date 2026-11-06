package com.therapylocal.app.engine.stress

/**
 * The lane tiers of the catalogue (section 5). The runner's `annotation` / `notAnnotation` filters select them (`qa/run-device-tests.sh INSTR_ARGS`).
 * A CORE test carries [Core] and [Full]; a FULL test that is not CORE carries [Full] and [FullOnly]. So:
 *  - the two PR stress shards run `-e annotation ...Full -e notAnnotation ...Core`;
 *  - the existing `android-test` lanes run everything but `-e notAnnotation ...FullOnly,...Lmk,...Slow` (the existing tests plus CORE).
 */
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION) annotation class Core
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION) annotation class Full
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION) annotation class FullOnly
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION) annotation class Lmk
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION) annotation class Slow

/** Tests that record a 10 s perfetto trace (A04, B01, D10, K01, K14). */
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION) annotation class Trace

/** The catalogue id and plan id of a test, written to its `results.jsonl` line (for example `@Case("A07", "A07-batch-llm-initialize-sigabrt")`). */
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class Case(val id: String, val plan: String = "")
