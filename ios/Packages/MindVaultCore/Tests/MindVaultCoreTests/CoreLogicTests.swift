import XCTest
@testable import MindVaultCore

final class FactParserTests: XCTestCase {
    func testSalvagesRecordsWithoutJSON() {
        let raw = """
        "heading":"Sister","content":"George saw his sister Maya at the market.","evidence":"I saw my sister Maya at the market."
        "heading":"Call","content":"George wants to call Maya this weekend.","evidence":"I want to call her again this weekend."
        """
        let facts = FactParser.anyFacts(text: raw)
        XCTAssertEqual(facts.count, 2)
        XCTAssertEqual(facts.first?.heading, "Sister")
        XCTAssertTrue(facts.allSatisfy(FactParser.valid))
    }

    func testEvidenceMustBeVerbatim() {
        let fact = ParsedFact(
            heading: "Runs again",
            content: "Ana took up running again in May.",
            evidence: "I took up running again in May."
        )
        XCTAssertTrue(FactParser.evidenceHolds(fact, source: Fixtures.anaTranscript1))
        let invented = ParsedFact(heading: "Runs again", content: "Ana started running.", evidence: "Ana runs on Sundays.")
        XCTAssertFalse(FactParser.evidenceHolds(invented, source: Fixtures.anaTranscript1))
    }

    func testSegmentOfEvidence() {
        // Tour data: every line sits in the one transcript window, so the
        // evidence lands in chunk 0 wherever it is in the take.
        let segments = Fixtures.sessions[0].segments
        XCTAssertEqual(FactParser.segmentOf(evidence: Fixtures.lines1[3], segments: segments), 0)
        // The result is the segment's chunkIndex, not its position: the same
        // quote in the middle of a two-window take maps to the second window.
        let byChunk = [
            TranscriptSegment(sessionID: nil, chunkIndex: 5, startMs: 0, endMs: 5_000, text: "Good to see you again."),
            TranscriptSegment(sessionID: nil, chunkIndex: 6, startMs: 5_000, endMs: 10_000, text: "Busy, but better."),
            TranscriptSegment(sessionID: nil, chunkIndex: 7, startMs: 10_000, endMs: 15_000, text: "It clears my head before work."),
        ]
        XCTAssertEqual(FactParser.segmentOf(evidence: "Busy, but better.", segments: byChunk), 6)
        // Evidence must be verbatim: an invented line has no segment.
        XCTAssertNil(FactParser.segmentOf(evidence: "Ana runs on Sundays.", segments: segments))
    }

    func testVerdictDecoding() {
        XCTAssertEqual(FactParser.verdict(raw: "{\"duplicate\":true,\"of\":\"K2\"}", known: 3), DuplicateVerdict(duplicate: true, of: 1))
        XCTAssertEqual(FactParser.verdict(raw: "{\"duplicate\":false}", known: 3), DuplicateVerdict(duplicate: false, of: nil))
        XCTAssertNil(FactParser.verdict(raw: "unreadable", known: 3))
        XCTAssertEqual(DuplicateVerdict(duplicate: true, of: 2).encoded(), "dup:2")
    }
}

final class SearchRankingTests: XCTestCase {
    func testLexicalScorePrefersExactPhrase() {
        let exact = SearchRanking.lexicalScore(query: "sister Maya", text: "I saw my sister Maya at the market.")
        let partial = SearchRanking.lexicalScore(query: "sister Maya", text: "The market was busy.")
        XCTAssertGreaterThan(exact, partial)
        XCTAssertEqual(partial, 0)
    }

    func testCuesRankFactsByEmbedding() {
        let query = HashingEmbedding.embed("Ana took up running again in May")
        let facts = Fixtures.facts.map { fact in
            var copy = fact
            copy.embedding = HashingEmbedding.embed(fact.text)
            return copy
        }
        let cues = SearchRanking.cues(query: query, facts: facts, clientID: Fixtures.anaID, limit: 2)
        XCTAssertFalse(cues.isEmpty)
        XCTAssertEqual(cues.first?.clientID, Fixtures.anaID)
        XCTAssertLessThanOrEqual(cues.count, 2)
    }

    func testSearchFindsChunkAndFact() {
        let results = SearchRanking.search(
            query: "running",
            chunks: Fixtures.chunks,
            facts: Fixtures.facts,
            sessions: Fixtures.sessions,
            clientID: Fixtures.anaID
        )
        XCTAssertTrue(results.contains { $0.chunkID != nil })
        XCTAssertTrue(results.allSatisfy { $0.clientID == Fixtures.anaID })
    }
}

final class IdentifierTests: XCTestCase {
    func testSessionExternalID() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .current
        let date = Date(timeIntervalSince1970: 1_791_554_400)
        let id = IDs.sessionExternalID(
            firstName: "George",
            lastName: "Miller",
            date: date,
            calendar: calendar,
            timeZone: TimeZone(secondsFromGMT: 0) ?? .current
        )
        XCTAssertEqual(id, "georgem_20261009-140000")
    }
}

final class PauseStateTests: XCTestCase {
    func testPauseAndResume() {
        let start = Date(timeIntervalSince1970: 1_000)
        var state = PauseState()
        state.start(at: start)
        XCTAssertEqual(state.elapsed(at: start.addingTimeInterval(10)), 10, accuracy: 0.001)
        state.pause(at: start.addingTimeInterval(10))
        XCTAssertEqual(state.elapsed(at: start.addingTimeInterval(20)), 10, accuracy: 0.001)
        state.resume(at: start.addingTimeInterval(20))
        XCTAssertEqual(state.elapsed(at: start.addingTimeInterval(30)), 20, accuracy: 0.001)
        XCTAssertEqual(state.stop(at: start.addingTimeInterval(30)), 20, accuracy: 0.001)
        XCTAssertEqual(state.phase, .stopped)
    }
}

final class JobQueueTests: XCTestCase {
    func testRetryThenFail() {
        var queue = JobQueue()
        let job = queue.enqueue(kind: .transcribe)
        XCTAssertEqual(queue.next()?.id, job.id)
        XCTAssertEqual(queue.job(id: job.id)?.attempts, 1)
        queue.fail(id: job.id, error: "first", maxAttempts: 2)
        XCTAssertEqual(queue.job(id: job.id)?.status, .queued)
        XCTAssertEqual(queue.next()?.id, job.id)
        queue.fail(id: job.id, error: "second", maxAttempts: 2)
        XCTAssertEqual(queue.job(id: job.id)?.status, .failed)
        queue.retry(id: job.id)
        XCTAssertEqual(queue.job(id: job.id)?.status, .queued)
        XCTAssertEqual(queue.job(id: job.id)?.attempts, 0)
    }
}

final class EmbeddingTests: XCTestCase {
    func testDeterministicAndNormalized() {
        let first = HashingEmbedding.embed("George saw his sister Maya at the market")
        let second = HashingEmbedding.embed("George saw his sister Maya at the market")
        XCTAssertEqual(first, second)
        XCTAssertEqual(first.dimension, 768)
        XCTAssertEqual(first.magnitude, 1, accuracy: 0.0001)
    }

    func testSharedWordsAreCloser() {
        let base = HashingEmbedding.embed("George saw his sister Maya at the market")
        let related = HashingEmbedding.embed("Maya at the market with George")
        let unrelated = HashingEmbedding.embed("The weather is cold today")
        XCTAssertGreaterThan(base.cosineSimilarity(to: related), base.cosineSimilarity(to: unrelated))
    }

    func testVectorMath() {
        XCTAssertEqual(VectorMath.cosine([1, 0], [0, 1]), 0, accuracy: 0.0001)
        XCTAssertEqual(VectorMath.cosine([1, 0], [2, 0]), 1, accuracy: 0.0001)
        XCTAssertEqual(VectorMath.mean([[1, 2], [3, 4]]), [2, 3])
        XCTAssertEqual(VectorMath.topIndices(scores: [0.1, 0.9, 0.5], limit: 2), [1, 2])
    }
}

final class CryptoTests: XCTestCase {
    func testSHA256KnownVector() {
        XCTAssertEqual(SHA256.hash(Data("abc".utf8)).hexString, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(SHA256.hash(Data()).hexString, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    func testHMACKnownVector() {
        let key = Data(repeating: 0x0b, count: 20)
        let mac = HMACSHA256.authenticate(key: key, message: Data("Hi There".utf8))
        XCTAssertEqual(mac.hexString, "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7")
    }

    func testPBKDF2KnownVectors() {
        let salt = Data("salt".utf8)
        XCTAssertEqual(
            PBKDF2.deriveKey(password: "password", salt: salt, iterations: 1).hexString,
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"
        )
        XCTAssertEqual(
            PBKDF2.deriveKey(password: "password", salt: salt, iterations: 2).hexString,
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43"
        )
        XCTAssertEqual(
            PBKDF2.deriveKey(password: "password", salt: salt, iterations: 4_096).hexString,
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"
        )
    }

    func testConstantTimeEquals() {
        XCTAssertTrue(PBKDF2.constantTimeEquals(Data([1, 2, 3]), Data([1, 2, 3])))
        XCTAssertFalse(PBKDF2.constantTimeEquals(Data([1, 2, 3]), Data([1, 2, 4])))
        XCTAssertFalse(PBKDF2.constantTimeEquals(Data([1, 2]), Data([1, 2, 3])))
    }
}

private extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
