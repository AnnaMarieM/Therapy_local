import XCTest
@testable import MindVaultLLM

/// The transcript as text and the speaker-role map. Fixtures are synthetic.
final class TranscriptFormatTests: XCTestCase {
    private let therapist = Segment(1, 0, 4000, "  How are you today? ", "S0")
    private let patient = Segment(2, 192_000, 196_000, "I am fine.", "S1")
    private let silent = Segment(3, 300_000, 301_000, "   ", "S0")

    func testFormatSkipsBlankUtterancesAndTrims() {
        let text = TranscriptFormat.format([therapist, patient, silent]) { role in
            role == "S0" ? "Therapist" : "Patient"
        }
        XCTAssertEqual(text, "[00:00:00]\nTherapist: How are you today?\n\n[00:03:12]\nPatient: I am fine.")
    }

    func testEmptyTranscriptGivesEmptyText() {
        XCTAssertEqual(TranscriptFormat.format([]) { _ in "?" }, "")
        XCTAssertEqual(TranscriptFormat.format([silent]) { _ in "?" }, "")
    }

    func testClockIsSessionTimeWithTwoDigitHours() {
        XCTAssertEqual(TranscriptFormat.clock(0), "00:00:00")
        XCTAssertEqual(TranscriptFormat.clock(192_000), "00:03:12")
        XCTAssertEqual(TranscriptFormat.clock(3_661_000), "01:01:01")
        XCTAssertEqual(TranscriptFormat.clock(3_723_000), "01:02:03")
        XCTAssertEqual(TranscriptFormat.clock(120_000_000), "33:20:00")
        // A negative time counts as 0.
        XCTAssertEqual(TranscriptFormat.clock(-5), "00:00:00")
    }

    func testNeutralLabelsNumberSpeakersByFirstAppearance() {
        let label = TranscriptFormat.neutralLabels([patient, therapist, silent])
        XCTAssertEqual(label("S1"), "Speaker 1")
        XCTAssertEqual(label("S0"), "Speaker 2")
        XCTAssertEqual(label("S9"), "Speaker ?")
        XCTAssertEqual(label(nil), "Speaker ?")
    }

    func testRoleLabelsUseTheRoleMapAndNeutralElsewhere() {
        let map = SpeakerMaps.decode("{\"S0\":\"therapist\",\"S1\":\"patient\"}")
        // The neutral labels come from the whole transcript (three speakers here), so a
        // speaker without a role keeps their number and an unseen one stays "Speaker ?".
        let other = Segment(3, 400_000, 404_000, "Hello.", "S2")
        let label = TranscriptFormat.roleLabel(
            map,
            roleName: { $0 == .therapist ? "Terapeut" : "Pacient" },
            neutral: TranscriptFormat.neutralLabels([therapist, patient, other])
        )
        XCTAssertEqual(label("S0"), "Terapeut")
        XCTAssertEqual(label("S1"), "Pacient")
        XCTAssertEqual(label("S2"), "Speaker 3")
        XCTAssertEqual(label("S9"), "Speaker ?")
        XCTAssertEqual(label(nil), "Speaker ?")
    }
}

final class SpeakerMapsTests: XCTestCase {
    func testEncodeWritesSortedKeys() {
        XCTAssertEqual(SpeakerMaps.encode(["S1": .patient, "S0": .therapist]), "{\"S0\":\"therapist\",\"S1\":\"patient\"}")
        XCTAssertEqual(SpeakerMaps.encode([:]), "{}")
    }

    func testDecodeIgnoresMalformedJson() {
        XCTAssertEqual(SpeakerMaps.decode("not json"), [:])
        XCTAssertEqual(SpeakerMaps.decode(""), [:])
        XCTAssertEqual(SpeakerMaps.decode("[1]"), [:])
        XCTAssertEqual(SpeakerMaps.decode("{\"S0\":null}"), ["S0": .other])
    }

    func testDecodeMapsUnknownRoleNamesToOther() {
        XCTAssertEqual(SpeakerMaps.decode("{\"S0\":\"judge\",\"S1\":\"patient\"}"), ["S0": .other, "S1": .patient])
        XCTAssertEqual(SpeakerMaps.decode("{\"S0\":\"THERAPIST\"}"), ["S0": .therapist])
    }

    func testSwapNeedsExactlyOneOfEach() {
        XCTAssertTrue(SpeakerMaps.canSwap(SpeakerMaps.decode("{\"S0\":\"therapist\",\"S1\":\"patient\"}")))
        XCTAssertFalse(SpeakerMaps.canSwap(SpeakerMaps.decode("{\"S0\":\"therapist\",\"S1\":\"other\"}")))
        XCTAssertFalse(SpeakerMaps.canSwap(SpeakerMaps.decode("{\"S0\":\"patient\",\"S1\":\"patient\"}")))
        XCTAssertFalse(SpeakerMaps.canSwap(SpeakerMaps.decode("{\"S0\":\"therapist\"}")))
    }

    func testSwapTradesTheRolesAndKeepsOthers() {
        let map = SpeakerMaps.decode("{\"S0\":\"therapist\",\"S1\":\"patient\",\"S2\":\"other\"}")
        let swapped = SpeakerMaps.swap(map)
        XCTAssertEqual(swapped["S0"], .patient)
        XCTAssertEqual(swapped["S1"], .therapist)
        XCTAssertEqual(swapped["S2"], .other)
        // A map that cannot swap comes back unchanged.
        let bad = SpeakerMaps.decode("{\"S0\":\"therapist\"}")
        XCTAssertEqual(SpeakerMaps.swap(bad), bad)
    }

    func testSegmentDefaults() {
        let s = Segment(2, 100, 200, "text")
        XCTAssertNil(s.speaker)
        XCTAssertEqual(Segment(1, 0, 1, "a", "S0").speaker, "S0")
        XCTAssertEqual(Role.allCases.map(\.json), ["therapist", "patient", "other"])
    }
}
