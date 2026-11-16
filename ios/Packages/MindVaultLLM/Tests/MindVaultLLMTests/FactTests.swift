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

    // MARK: - Numbers: Java's `(int)` cast, never a Swift trap (review should #6)

    func testIntOfSaturatesLikeJavasIntCastInsteadOfTrapping() {
        // Kotlin's field is an Int (32 bit) read through org.json `optInt`, whose
        // `Number.intValue()` is the JLS d2i conversion: NaN → 0, and a saturating
        // clamp at the 32-bit bounds (NOT Swift's 64-bit Int, whose `Int(1e30)` traps).
        XCTAssertEqual(Fact.intOf(.number(1e30)), Int(Int32.max))
        XCTAssertEqual(Fact.intOf(.number(-1e30)), Int(Int32.min))
        XCTAssertEqual(Fact.intOf(.number(.nan)), 0)
        XCTAssertEqual(Fact.intOf(.number(.infinity)), Int(Int32.max))
        XCTAssertEqual(Fact.intOf(.number(-.infinity)), Int(Int32.min))
        XCTAssertEqual(Fact.intOf(.number(7)), 7)
        XCTAssertEqual(Fact.intOf(.number(7.9)), 7)  // truncation toward zero
        XCTAssertEqual(Fact.intOf(.number(-7.9)), -7)
        XCTAssertEqual(Fact.intOf(.number(Double(Int32.max))), Int(Int32.max))
        XCTAssertNil(Fact.intOf(nil))
        XCTAssertNil(Fact.intOf(.bool(true)))
    }

    func testIntOfParsesNumericStringsLikeDoubleValueOf() {
        // `JSON.toInteger(String)` is `(int) Double.parseDouble(s)`: doubles coerce.
        XCTAssertEqual(Fact.intOf(.string("3")), 3)
        XCTAssertEqual(Fact.intOf(.string("3.7")), 3)
        XCTAssertEqual(Fact.intOf(.string(" 7 ")), 7)
        XCTAssertEqual(Fact.intOf(.string("1e30")), Int(Int32.max))
        XCTAssertNil(Fact.intOf(.string("seg")))
        XCTAssertNil(Fact.intOf(.string("")))
    }

    func testFromJsonSurvivesAnAdversarialSegmentNumber() throws {
        // This exact string used to trap in `Int(d)` and kill the app; Android clamps.
        let fact = try XCTUnwrap(Fact.fromJsonString("{\"heading\":\"h\",\"content\":\"c\",\"evidence\":\"e\",\"seg\":1e30,\"segEnd\":-1e30}"))
        XCTAssertEqual(fact.segIdx, Int(Int32.max))
        XCTAssertEqual(fact.segEnd, Int(Int32.min))
    }

    // MARK: - Trimming: only where Android trims (review should #9)

    func testFromJsonDoesNotTrimTextButTrimsTheCategory() throws {
        // Facts.kt: heading/content/evidence/scope are `optString` (verbatim),
        // `category` is `.trim()`, `scope` falls back when blank.
        let fact = try XCTUnwrap(Fact.fromJsonString("{\"heading\":\"h\\n\",\"content\":\"c\\t\",\"evidence\":\"e \",\"scope\":\" \\n\",\"category\":\" coping\\n\"}"))
        XCTAssertEqual(fact.heading, "h\n")
        XCTAssertEqual(fact.content, "c\t")
        XCTAssertEqual(fact.evidence, "e ")
        XCTAssertEqual(fact.scope, Fact.SCOPE_PROFILE)  // ifBlank
        XCTAssertEqual(fact.category, "coping")
    }
}
