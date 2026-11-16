import XCTest
@testable import MindVaultLLM

/// The minimal JSON tree: parser, org.json-style accessors and the writer.
final class JSONValueTests: XCTestCase {
    func testParsesEveryKind() {
        XCTAssertEqual(JSONValue.parse("{\"a\":1}"), .object(["a": .number(1)]))
        XCTAssertEqual(JSONValue.parse("[1,2.5]"), .array([.number(1), .number(2.5)]))
        XCTAssertEqual(JSONValue.parse("\"text\""), .string("text"))
        XCTAssertEqual(JSONValue.parse("true"), .bool(true))
        XCTAssertEqual(JSONValue.parse("false"), .bool(false))
        XCTAssertEqual(JSONValue.parse("null"), .null)
        XCTAssertEqual(JSONValue.parse("-12"), .number(-12))
        XCTAssertEqual(JSONValue.parse("  { \"a\" : [ true ] }  "), .object(["a": .array([.bool(true)])]))
    }

    func testRejectsBrokenAndTrailingInput() {
        XCTAssertNil(JSONValue.parse("{\"a\":}"))
        XCTAssertNil(JSONValue.parse("[1,2"))
        XCTAssertNil(JSONValue.parse("{\"a\":1} trailing"))
        XCTAssertNil(JSONValue.parse(""))
        XCTAssertNil(JSONValue.parse("\"unterminated"))
        XCTAssertNil(JSONValue.parse("{\"a\":1,}"))
        XCTAssertNil(JSONValue.parse("{a:1}"))
        // Divergence from org.json (documented): org.json rejects a leading zero ("01"),
        // this parser reads it as the number 1 — harmless for model output.
    }

    /// Duplicate keys keep the LAST value, like org.json's optString.
    func testDuplicateKeysKeepTheLastValue() {
        XCTAssertEqual(JSONValue.parse("{\"a\":\"x\",\"a\":\"y\"}"), .object(["a": .string("y")]))
    }

    func testEscapesAndControlCharacters() {
        XCTAssertEqual(JSONValue.parse("\"a\\nb\""), .string("a\nb"))
        XCTAssertEqual(JSONValue.parse("\"\\u00e9\""), .string("é"))
        // Surrogate pair → one scalar.
        XCTAssertEqual(JSONValue.parse("\"\\ud83d\\ude00\""), .string("😀"))
        // A lone surrogate cannot exist in a Swift string.
        XCTAssertNil(JSONValue.parse("\"\\ud83d\""))
        XCTAssertNil(JSONValue.parse("\"\\ude00\""))
        // Raw control characters are not allowed.
        XCTAssertNil(JSONValue.parse("\"a\u{01}b\""))
        XCTAssertEqual(JSONValue.parse("\"\\u0001\""), .string("\u{01}"))
    }

    func testAsStringFollowsOrgJson() {
        XCTAssertEqual(JSONValue.string("s").asString, "s")
        XCTAssertEqual(JSONValue.bool(true).asString, "true")
        XCTAssertEqual(JSONValue.number(3).asString, "3")
        XCTAssertEqual(JSONValue.number(3.5).asString, "3.5")
        XCTAssertEqual(JSONValue.null.asString, "")
        XCTAssertEqual(JSONValue.object([:]).asString, "")
        XCTAssertEqual(JSONValue.object(["k": .number(1)]).string("k"), "1")
        XCTAssertEqual(JSONValue.object(["k": .string(" v ")]).string("k"), "v")
        XCTAssertEqual(JSONValue.object([:]).string("missing"), "")
    }

    func testAsDoubleParsesNumericStrings() {
        XCTAssertEqual(JSONValue.number(2.5).asDouble, 2.5)
        XCTAssertEqual(JSONValue.string("2.5").asDouble, 2.5)
        XCTAssertEqual(JSONValue.string(" 7 ").asDouble, 7)
        XCTAssertNil(JSONValue.string("x").asDouble)
        XCTAssertNil(JSONValue.bool(true).asDouble)
        XCTAssertNil(JSONValue.null.asDouble)
    }

    func testEncodesWithEscapes() {
        XCTAssertEqual(JSONValue.string("a\"b\\c\nd\te").encode(), "\"a\\\"b\\\\c\\nd\\te\"")
        XCTAssertEqual(JSONValue.string("\u{01}").encode(), "\"\\u0001\"")
        XCTAssertEqual(JSONValue.array([.number(1), .string("x")]).encode(), "[1,\"x\"]")
        // Object keys are sorted (Fact builds its own ordered document on top of this).
        XCTAssertEqual(JSONValue.object(["b": .number(1), "a": .number(2)]).encode(), "{\"a\":2,\"b\":1}")
        XCTAssertEqual(JSONValue.number(2).encode(), "2")
        XCTAssertEqual(JSONValue.number(2.5).encode(), "2.5")
    }

    func testRoundtrip() {
        let text = "{\"a\":[1,true,null,\"x\\ny\"],\"b\":{\"c\":2.5}}"
        let parsed = JSONValue.parse(text)
        XCTAssertEqual(parsed?.encode(), "{\"a\":[1,true,null,\"x\\ny\"],\"b\":{\"c\":2.5}}")
    }
}
