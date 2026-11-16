import XCTest
@testable import MindVaultLLM

/// The Fact record, its ordered JSON (the extraction cache format) and the review
/// Verdict. Fixtures are synthetic.
final class FactTests: XCTestCase {
    func testToJsonWritesTheFieldsInAndroidOrder() {
        let fact = Fact(heading: "Walks", content: "Client walks when stressed.", evidence: "I go for a walk when it gets loud", segIdx: 2, scope: Fact.SCOPE_SESSION, category: "coping")
        XCTAssertEqual(
            fact.toJson(),
            "{\"heading\":\"Walks\",\"content\":\"Client walks when stressed.\",\"evidence\":\"I go for a walk when it gets loud\",\"seg\":2,\"segEnd\":2,\"scope\":\"session\",\"category\":\"coping\"}"
        )
    }

    func testSegEndDefaultsToSegIdx() {
        XCTAssertEqual(Fact(heading: "h", content: "c", evidence: "e", segIdx: 4).segEnd, 4)
        XCTAssertEqual(Fact(heading: "h", content: "c", evidence: "e", segIdx: 1, segEnd: 3).segEnd, 3)
    }

    func testRoundtripKeepsEveryField() throws {
        let fact = Fact(heading: "Sister", content: "Sibling lives in another city nearby.", evidence: "my sibling lives across town", segIdx: 7, scope: Fact.SCOPE_SESSION, category: "relationship", segEnd: 9)
        let back = try XCTUnwrap(Fact.fromJsonString(fact.toJson()))
        XCTAssertEqual(back, fact)
    }

    func testFromJsonFallsBackLikeAndroid() {
        // Missing scope → profile; missing segEnd → seg; missing numbers → 0.
        let fact = Fact.fromJson(.object([
            "heading": .string("h"),
            "content": .string("c"),
            "evidence": .string("e"),
        ]))
        XCTAssertEqual(fact, Fact(heading: "h", content: "c", evidence: "e"))
        // Numbers may arrive as strings ("seg":"3").
        let stringy = Fact.fromJsonString("{\"heading\":\"h\",\"content\":\"c\",\"evidence\":\"e\",\"seg\":\"3\",\"segEnd\":\"5\"}")
        XCTAssertEqual(stringy?.segIdx, 3)
        XCTAssertEqual(stringy?.segEnd, 5)
    }

    func testFromJsonStringRejectsNonObjects() {
        XCTAssertNil(Fact.fromJsonString("[]"))
        XCTAssertNil(Fact.fromJsonString("not json"))
        XCTAssertNil(Fact.fromJsonString(""))
    }

    func testToJsonEscapesTheText() {
        let fact = Fact(heading: "He said \"ok\"", content: "Line one\nLine two", evidence: "back\\slash")
        let back = Fact.fromJsonString(fact.toJson())
        XCTAssertEqual(back?.heading, "He said \"ok\"")
        XCTAssertEqual(back?.content, "Line one\nLine two")
        XCTAssertEqual(back?.evidence, "back\\slash")
    }

    // MARK: - Verdict

    func testVerdictEncodeDecodeRoundtrip() {
        XCTAssertEqual(Verdict(true, 3).encode(), "dup:3")
        XCTAssertEqual(Verdict(true, nil).encode(), "dup:-1")
        XCTAssertEqual(Verdict(false, 3).encode(), "new")
        XCTAssertEqual(Verdict.decode("dup:0"), Verdict(true, 0))
        XCTAssertEqual(Verdict.decode("new"), Verdict(false, nil))
        XCTAssertNil(Verdict.decode("dup:x"))
        XCTAssertNil(Verdict.decode("dup"))
        XCTAssertNil(Verdict.decode(""))
        XCTAssertNil(Verdict.decode("other"))
    }

    func testFactSchemasCarryTheParserKeys() {
        // The strict extraction schema asks for the fields Fact.fromJson reads.
        for key in ["\"heading\"", "\"content\"", "\"evidence\"", "\"scope\"", "\"category\"", "\"facts\"", "maxItems\":20"] {
            XCTAssertTrue(FactSchemas.EXTRACT_STRICT.contains(key), key)
        }
    }
}
