import Foundation
import MindVaultCore

struct FactExtractionResult: Sendable {
    let facts: [Fact]
    let mentions: [Mention]
}

protocol FactExtracting: Sendable {
    func extract(
        transcript: String,
        client: Client,
        sessionID: UUID,
        chunkID: UUID?
    ) -> FactExtractionResult
}

struct ExtractiveFactExtractor: FactExtracting {
    let embeddings: any EmbeddingProviding

    func extract(
        transcript: String,
        client: Client,
        sessionID: UUID,
        chunkID: UUID?
    ) -> FactExtractionResult {
        let sentences = Self.sentences(in: transcript)
        var seen: Set<String> = []
        var facts: [Fact] = []
        var mentions: [Mention] = []

        for sentence in sentences {
            let normalized = TextNormalization.normalize(sentence)
            guard normalized.count >= 20, sentence.count <= 300, !seen.contains(normalized) else { continue }
            seen.insert(normalized)
            let category = Self.category(for: normalized)
            let fact = Fact(
                clientID: client.id,
                sessionID: sessionID,
                category: category,
                subject: client.firstName,
                text: sentence,
                evidence: sentence,
                chunkID: chunkID,
                embedding: embeddings.embed(text: sentence)
            )
            facts.append(fact)
            mentions.append(
                Mention(
                    factID: fact.id,
                    sessionID: sessionID,
                    chunkID: chunkID,
                    evidence: sentence
                )
            )
            if facts.count >= 20 { break }
        }
        return FactExtractionResult(facts: facts, mentions: mentions)
    }

    private static func sentences(in transcript: String) -> [String] {
        let pattern = #"[^.!?]+[.!?]?"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        let range = NSRange(transcript.startIndex..<transcript.endIndex, in: transcript)
        return regex.matches(in: transcript, range: range).compactMap { match in
            guard let matchRange = Range(match.range, in: transcript) else { return nil }
            let sentence = transcript[matchRange].trimmingCharacters(in: .whitespacesAndNewlines)
            return sentence.isEmpty ? nil : sentence
        }
    }

    private static func category(for normalized: String) -> FactCategory {
        let tokens = Set(normalized.split(separator: " ").map(String.init))
        if !tokens.isDisjoint(with: ["sister", "brother", "mother", "father", "friend", "partner", "wife", "husband", "son", "daughter"]) {
            return .person
        }
        if !tokens.isDisjoint(with: ["want", "hope", "plan", "goal", "try", "will"]) {
            return .goal
        }
        if !tokens.isDisjoint(with: ["walk", "walking", "breathe", "breathing", "helps", "calm", "cope", "coping", "rest"]) {
            return .coping
        }
        if !tokens.isDisjoint(with: ["worried", "worry", "afraid", "scared", "anxious", "hard", "loud", "tired"]) {
            return .concern
        }
        if !tokens.isDisjoint(with: ["like", "love", "prefer", "enjoy", "favourite", "favorite"]) {
            return .preference
        }
        if !tokens.isDisjoint(with: ["market", "park", "office", "home", "block", "garden", "school"]) {
            return .place
        }
        if !tokens.isDisjoint(with: ["saw", "met", "talked", "called", "visited", "went"]) {
            return .event
        }
        return .other
    }
}
