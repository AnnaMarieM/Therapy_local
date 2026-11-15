import Foundation

public struct EmbeddingVector: Codable, Hashable, Sendable {
    public var values: [Float]

    public init(values: [Float]) {
        self.values = values
    }

    public var dimension: Int {
        values.count
    }

    public var magnitude: Double {
        var sum = 0.0
        for value in values {
            sum += Double(value) * Double(value)
        }
        return sum.squareRoot()
    }

    public var normalized: EmbeddingVector {
        let length = magnitude
        guard length > 0 else { return self }
        return EmbeddingVector(values: values.map { Float(Double($0) / length) })
    }

    public func dot(_ other: EmbeddingVector) -> Double {
        guard dimension == other.dimension else { return 0 }
        var sum = 0.0
        for index in values.indices {
            sum += Double(values[index]) * Double(other.values[index])
        }
        return sum
    }

    public func cosineSimilarity(to other: EmbeddingVector) -> Double {
        let denominator = magnitude * other.magnitude
        guard denominator > 0 else { return 0 }
        return dot(other) / denominator
    }
}

public struct Chunk: Identifiable, Codable, Hashable, Sendable {
    public let id: UUID
    public var sessionID: UUID
    public var index: Int
    public var startMs: Int
    public var endMs: Int
    public var overlapMs: Int
    public var text: String
    public var textEmbedding: EmbeddingVector?
    public var audioEmbedding: EmbeddingVector?
    public var createdAt: Date

    public init(
        id: UUID = UUID(),
        sessionID: UUID,
        index: Int,
        startMs: Int,
        endMs: Int,
        overlapMs: Int,
        text: String = "",
        textEmbedding: EmbeddingVector? = nil,
        audioEmbedding: EmbeddingVector? = nil,
        createdAt: Date = Date()
    ) {
        self.id = id
        self.sessionID = sessionID
        self.index = index
        self.startMs = startMs
        self.endMs = endMs
        self.overlapMs = overlapMs
        self.text = text
        self.textEmbedding = textEmbedding
        self.audioEmbedding = audioEmbedding
        self.createdAt = createdAt
    }

    public var durationMs: Int {
        max(0, endMs - startMs)
    }
}

public enum FactCategory: String, Codable, CaseIterable, Sendable {
    case person
    case relationship
    case event
    case place
    case preference
    case goal
    case concern
    case coping
    case other

    public var title: String {
        switch self {
        case .person: return "People"
        case .relationship: return "Relationships"
        case .event: return "Events"
        case .place: return "Places"
        case .preference: return "Preferences"
        case .goal: return "Goals"
        case .concern: return "Concerns"
        case .coping: return "Coping"
        case .other: return "Notes"
        }
    }
}

public struct Fact: Identifiable, Codable, Hashable, Sendable {
    public let id: UUID
    public var clientID: UUID
    public var sessionID: UUID
    public var category: FactCategory
    public var subject: String
    public var text: String
    public var evidence: String
    public var chunkID: UUID?
    public var embedding: EmbeddingVector?
    public var createdAt: Date

    public init(
        id: UUID = UUID(),
        clientID: UUID,
        sessionID: UUID,
        category: FactCategory = .other,
        subject: String,
        text: String,
        evidence: String,
        chunkID: UUID? = nil,
        embedding: EmbeddingVector? = nil,
        createdAt: Date = Date()
    ) {
        self.id = id
        self.clientID = clientID
        self.sessionID = sessionID
        self.category = category
        self.subject = subject
        self.text = text
        self.evidence = evidence
        self.chunkID = chunkID
        self.embedding = embedding
        self.createdAt = createdAt
    }
}

public struct Mention: Identifiable, Codable, Hashable, Sendable {
    public let id: UUID
    public var factID: UUID
    public var sessionID: UUID
    public var chunkID: UUID?
    public var evidence: String
    public var createdAt: Date

    public init(
        id: UUID = UUID(),
        factID: UUID,
        sessionID: UUID,
        chunkID: UUID? = nil,
        evidence: String,
        createdAt: Date = Date()
    ) {
        self.id = id
        self.factID = factID
        self.sessionID = sessionID
        self.chunkID = chunkID
        self.evidence = evidence
        self.createdAt = createdAt
    }
}

public struct MemoryCue: Identifiable, Codable, Hashable, Sendable {
    public let id: UUID
    public var factID: UUID
    public var clientID: UUID
    public var sessionID: UUID
    public var chunkID: UUID?
    public var category: FactCategory
    public var text: String
    public var score: Double
    public var createdAt: Date

    public init(
        id: UUID = UUID(),
        factID: UUID,
        clientID: UUID,
        sessionID: UUID,
        chunkID: UUID? = nil,
        category: FactCategory = .other,
        text: String,
        score: Double,
        createdAt: Date = Date()
    ) {
        self.id = id
        self.factID = factID
        self.clientID = clientID
        self.sessionID = sessionID
        self.chunkID = chunkID
        self.category = category
        self.text = text
        self.score = score
        self.createdAt = createdAt
    }
}

public struct SearchResult: Identifiable, Codable, Hashable, Sendable {
    public let id: UUID
    public var clientID: UUID
    public var sessionID: UUID
    public var chunkID: UUID?
    public var factID: UUID?
    public var text: String
    public var snippet: String
    public var score: Double
    public var startMs: Int
    public var endMs: Int

    public init(
        id: UUID = UUID(),
        clientID: UUID,
        sessionID: UUID,
        chunkID: UUID? = nil,
        factID: UUID? = nil,
        text: String,
        snippet: String,
        score: Double,
        startMs: Int,
        endMs: Int
    ) {
        self.id = id
        self.clientID = clientID
        self.sessionID = sessionID
        self.chunkID = chunkID
        self.factID = factID
        self.text = text
        self.snippet = snippet
        self.score = score
        self.startMs = startMs
        self.endMs = endMs
    }
}
