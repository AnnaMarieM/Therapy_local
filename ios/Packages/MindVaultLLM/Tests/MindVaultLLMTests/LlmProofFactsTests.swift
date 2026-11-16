import XCTest
@testable import MindVaultLLM

/// The second proof's checker (LlmProof.checkFacts), against the real raw replies of the
/// SM-T970 tablet (Adreno 650, LiteRT-LM 0.18.1, greedy), issue #48. Ported from
/// LlmProofFactsTest.kt. The replies are to a made-up text, no patient data. Measured:
/// the CPU passes; the generic Gemma file on the GPU answers ONE merged object (the bug
/// that "Reply with OK." and the first reformat proof let through); the -gpu file on the
/// GPU answers garbage; FLOAT32 activations give three objects on both files.
final class LlmProofFactsTests: XCTestCase {
    private let cpu = """
    ```json
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
    ```
    """

    private let gpuGeneric = """
    ```json
    {
      "Téma": "Vztah s sestrou a pocit vzdálenosti",
      "Obsah": "Klientka cítí, že se s sestrou Lucie teď velmi vzdálená.",
      "Důvod": "Přesun sestry Lucie do Brna.",
      "Důsledky": "Změna rytmu spánku (usínání až po půlnoci a budí se v pět ráno).",
      "Problémový chování": "Zvýšená dávka kafe (tři až čtyři hrnky denně) jako reakce na změnu.",
      "Způsobit": "Pila jsem proto víc kávy, tři až čtyři hrnky denně."
    }
    ```
    """

    private let gpuFile = "Zat zdeतरहЦена"

    private let gpuGenericF32 = """
    ```json
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
    ```
    """

    private let gpuFileF32 = """
    ```json
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
    ```
    """

    private func fail(_ reply: String) -> String {
        guard case .fail(let reason) = LlmProof.checkFacts(reply) else { return "unexpectedly Ok" }
        return reason
    }

    func testTheCpuReplyPasses() {
        XCTAssertEqual(LlmProof.Verdict.ok, LlmProof.checkFacts(cpu))
    }

    func testFloat32RepliesPassOnBothFiles() {
        XCTAssertEqual(LlmProof.Verdict.ok, LlmProof.checkFacts(gpuGenericF32))
        XCTAssertEqual(LlmProof.Verdict.ok, LlmProof.checkFacts(gpuFileF32))
    }

    func testTheGenericFileOnTheGpuIsOneMergedObjectAndFails() {
        XCTAssertEqual("too_few_objects", fail(gpuGeneric))
    }

    func testTheGpuFileOnTheGpuIsGarbageAndFails() {
        XCTAssertEqual("invalid_json", fail(gpuFile))
    }

    func testEmptyReplyFails() {
        XCTAssertEqual("empty_reply", fail("  \n```\n"))
    }

    func testARepeatedKeyFails() {
        let r = "{\"a\": \"x\", \"a\": \"y\", \"c\": \"z\"}\n{\"a\": \"x\", \"b\": \"y\", \"c\": \"z\"}\n{\"a\": \"x\", \"b\": \"y\", \"c\": \"z\"}"
        XCTAssertEqual("duplicate_key", fail(r))
    }

    func testAnObjectWithFourKeysFails() {
        let three = "{\"h\": \"a\", \"c\": \"b\", \"e\": \"Usínám až po půlnoci a v pět ráno se budím.\"}"
        let four = "{\"h\": \"a\", \"c\": \"b\", \"d\": \"c\", \"e\": \"Usínám až po půlnoci a v pět ráno se budím.\"}"
        XCTAssertEqual("wrong_keys", fail("\(three)\n\(three)\n\(four)"))
    }

    func testAQuoteThatIsNotInTheTextFails() {
        let ok = LlmProof.SENTENCES.map { "{\"h\": \"a\", \"c\": \"b\", \"e\": \"\($0)\"}" }
        XCTAssertEqual(LlmProof.Verdict.ok, LlmProof.checkFacts(ok.joined(separator: "\n")))
        let bad = ok.prefix(2) + ["{\"h\": \"a\", \"c\": \"b\", \"e\": \"Chodím na dlouhé procházky.\"}"]
        XCTAssertEqual("evidence_not_quote:3", fail(bad.joined(separator: "\n")))
    }

    func testTheSameQuoteTwiceFails() {
        let one = "{\"h\": \"a\", \"c\": \"b\", \"e\": \"\(LlmProof.SENTENCES[0])\"}"
        XCTAssertEqual("repeated_evidence", fail("\(one)\n\(one)\n\(one)"))
    }

    func testAnArrayAndCaseAndDiacriticsAreAccepted() {
        let items = LlmProof.SENTENCES.map { $0.uppercased() }
            .map { "{\"h\": \"a\", \"c\": \"b\", \"e\": \"\($0)\"}" }
            .joined(separator: ",")
        XCTAssertEqual(LlmProof.Verdict.ok, LlmProof.checkFacts("[\(items)]"))
    }

    func testANonStringValueFails() {
        XCTAssertEqual("invalid_json", fail("{\"h\": \"a\", \"c\": [\"x\"], \"e\": \"y\"}"))
    }
}
