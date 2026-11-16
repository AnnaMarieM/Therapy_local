import XCTest
@testable import MindVaultLLM

/// The first proof's checker, ported from LlmProofTest.kt (same synthetic fixtures, same
/// reason strings).
final class LlmProofTests: XCTestCase {
    private let good = """
    [
      {"kategorie": "zvíře", "text": "Jana má psa Rexe."},
      {"kategorie": "práce", "text": "Pracuje jako učitelka v Brně."},
      {"kategorie": "koníček", "text": "Ráda plave."},
      {"kategorie": "rodina", "text": "Má dceru Elišku."}
    ]
    """

    private func fail(_ reply: String) -> String {
        guard case .fail(let reason) = LlmProof.check(reply) else { return "unexpectedly Ok" }
        return reason
    }

    func testGoodReplyPasses() {
        XCTAssertEqual(LlmProof.Verdict.ok, LlmProof.check(good))
    }

    func testCodeFencedJsonPasses() {
        XCTAssertEqual(LlmProof.Verdict.ok, LlmProof.check("```json\n\(good)\n```"))
        XCTAssertEqual(LlmProof.Verdict.ok, LlmProof.check("  \n```\n\(good)\n```  \n"))
    }

    func testWhitespaceAndOneLineVariantsPass() {
        let oneLine = good.replacingOccurrences(of: "\\s*\\n\\s*", with: "", options: .regularExpression)
        XCTAssertEqual(LlmProof.Verdict.ok, LlmProof.check(oneLine))
        XCTAssertEqual(LlmProof.Verdict.ok, LlmProof.check("\t\(oneLine)\r\n"))
    }

    func testWordingAndKeyOrderMayDiffer() {
        let reply = "[{\"text\":\"Učitelka z Brna\",\"kategorie\":\"Práce\"},{\"text\":\"pes REX\",\"kategorie\":\"zvire\"},"
            + "{\"text\":\"plavání: ráda plave\",\"kategorie\":\"KONÍČEK\"},{\"text\":\"dcera Eliška\",\"kategorie\":\"rodina\"}]"
        XCTAssertEqual(LlmProof.Verdict.ok, LlmProof.check(reply))
    }

    func testDiacriticsMissingInReplyStillMatch() {
        let reply = good
            .replacingOccurrences(of: "Elišku", with: "Eliska")
            .replacingOccurrences(of: "učitelka", with: "ucitelka")
            .replacingOccurrences(of: "Brně", with: "Brne")
        XCTAssertEqual(LlmProof.Verdict.ok, LlmProof.check(reply))
    }

    /// The shape of issue #48: one object, several keys repeated, instead of four objects.
    func testIssue48OneObjectWithDuplicateKeysFails() {
        let reply = "[{\"kategorie\": \"zvíře\", \"text\": \"Jana má psa Rexe.\", \"kategorie\": \"práce\", \"text\": \"Pracuje jako učitelka v Brně.\", \"kategorie\": \"koníček\", \"text\": \"Ráda plave.\"}]"
        XCTAssertEqual("duplicate_key", fail(reply))
        XCTAssertEqual("duplicate_key", fail("```json\n\(reply)\n```"))
    }

    func testDuplicateKeyInOtherwiseValidArrayFails() {
        let reply = good.replacingOccurrences(
            of: "{\"kategorie\": \"rodina\", \"text\": \"Má dceru Elišku.\"}",
            with: "{\"text\": \"Má dceru Elišku.\", \"text\": \"Má dceru Elišku.\"}"
        )
        XCTAssertEqual("duplicate_key", fail(reply))
    }

    func testTruncatedJsonFails() {
        XCTAssertEqual("invalid_json", fail(String(good.dropLast(30))))
        XCTAssertEqual("invalid_json", fail("```json\n" + String(good.prefix(120))))
    }

    func testProseInsteadOfJsonFails() {
        XCTAssertEqual("invalid_json", fail("OK"))
        XCTAssertEqual("invalid_json", fail("Tady je JSON: \(good)"))
        XCTAssertEqual("invalid_json", fail("\(good) a ještě něco"))
    }

    func testEmptyReplyFails() {
        XCTAssertEqual("empty_reply", fail(""))
        XCTAssertEqual("empty_reply", fail("   \n"))
        XCTAssertEqual("empty_reply", fail("```json\n```"))
    }

    func testWrongCountFails() {
        XCTAssertEqual("wrong_count", fail("[{\"kategorie\":\"zvíře\",\"text\":\"Jana má psa Rexe.\"}]"))
        XCTAssertEqual("wrong_count", fail("[]"))
        XCTAssertEqual("wrong_count", fail(String(good.dropLast()) + ",{\"kategorie\":\"a\",\"text\":\"b\"}]"))
    }

    func testWrongKeysFail() {
        XCTAssertEqual("wrong_keys", fail(good.replacingOccurrences(of: "kategorie", with: "category")))
        XCTAssertEqual("wrong_keys", fail(good.replacingOccurrences(
            of: "{\"kategorie\": \"rodina\", \"",
            with: "{\"extra\": \"1\", \"kategorie\": \"rodina\", \""
        )))
    }

    func testMissingFactNamesItsNumber() {
        XCTAssertEqual("missing_fact:1", fail(good.replacingOccurrences(of: "Rexe", with: "psa")))
        XCTAssertEqual("missing_fact:2", fail(good.replacingOccurrences(of: "Brně", with: "městě")))
        XCTAssertEqual("missing_fact:3", fail(good.replacingOccurrences(of: "Ráda plave.", with: "Ráda čte.")))
        XCTAssertEqual("missing_fact:4", fail(good.replacingOccurrences(of: "Elišku", with: "dceru")))
    }

    func testNonStringValueFailsAsInvalid() {
        XCTAssertEqual("invalid_json", fail(good.replacingOccurrences(of: "\"Ráda plave.\"", with: "3")))
    }

    /// The shape the Adreno 650 gave back to the first proof: valid, four objects, right
    /// keys and facts, but garbled category words.
    func testGarbledCategoryWordsFail() {
        XCTAssertEqual("wrong_category:4", fail(good.replacingOccurrences(of: "rodina", with: "Rodzina")))
        XCTAssertEqual("wrong_category:2", fail(good.replacingOccurrences(of: "práce", with: "Pracování")))
        XCTAssertEqual("wrong_category:3", fail(good.replacingOccurrences(of: "koníček", with: "Hobby")))
    }

    func testEmptyValueFails() {
        XCTAssertEqual("empty_value", fail(good.replacingOccurrences(of: "\"zvíře\"", with: "\" \"")))
    }

    func testReasonsNeverCarryReplyText() {
        let reason = fail("[{\"kategorie\":\"tajné jméno\",\"text\":\"x\",\"text\":\"y\"}]")
        XCTAssertEqual("duplicate_key", reason)
    }

    func testThePromptContainsEveryFactAndAFactHasKeyWords() {
        LlmProof.FACTS.forEach { XCTAssertTrue(LlmProof.USER.contains($0)) }
        XCTAssertEqual(LlmProof.FACTS.count, LlmProof.CATEGORIES.count)
        LlmProof.CATEGORIES.forEach { XCTAssertTrue(LlmProof.SYSTEM.contains($0)) }
        XCTAssertEqual(LlmProof.FACTS.count, LlmProof.KEY_WORDS.count)
        for (f, words) in zip(LlmProof.FACTS, LlmProof.KEY_WORDS) {
            for w in words {
                XCTAssertTrue(LlmProof.normalise(f).contains(w), "\(f) / \(w)")
            }
        }
    }

    func testNormaliseStripsDiacriticsAndCase() {
        XCTAssertEqual(LlmProof.normalise("Elišku"), "elisku")
        XCTAssertEqual(LlmProof.normalise("KoníČEK"), "konicek")
        XCTAssertEqual(LlmProof.normalise("Brně"), "brne")
        // UTF-8 multibyte inputs survive the round trip through NFD.
        XCTAssertEqual(LlmProof.normalise("Pila jsem proto víc kávy"), "pila jsem proto vic kavy")
    }
}
