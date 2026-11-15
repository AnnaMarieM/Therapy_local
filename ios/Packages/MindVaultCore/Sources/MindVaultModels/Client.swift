import Foundation

public struct Client: Identifiable, Codable, Hashable, Sendable {
    public let id: UUID
    public var firstName: String
    public var lastNameInitial: String?
    public var normalizedID: String
    public var initialNote: String
    public var age: Int?
    public var occupation: String
    public var createdAt: Date
    public var updatedAt: Date

    public init(
        id: UUID = UUID(),
        firstName: String,
        lastName: String = "",
        initialNote: String = "",
        age: Int? = nil,
        occupation: String = "",
        normalizedID: String? = nil,
        createdAt: Date = Date(),
        updatedAt: Date? = nil
    ) {
        let trimmedFirst = firstName.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedLast = lastName.trimmingCharacters(in: .whitespacesAndNewlines)
        self.id = id
        self.firstName = trimmedFirst
        self.lastNameInitial = trimmedLast.first.map(String.init)
        self.normalizedID = normalizedID ?? Self.normalizedID(firstName: trimmedFirst, lastName: trimmedLast)
        self.initialNote = initialNote
        self.age = age
        self.occupation = occupation
        self.createdAt = createdAt
        self.updatedAt = updatedAt ?? createdAt
    }

    public var displayName: String {
        Self.displayName(firstName: firstName, lastNameInitial: lastNameInitial)
    }

    public var initials: String {
        let first = firstName.first.map(String.init) ?? "?"
        if let initial = lastNameInitial, !initial.isEmpty {
            return first + initial
        }
        return first
    }

    public static func displayName(firstName: String, lastName: String) -> String {
        let first = firstName.trimmingCharacters(in: .whitespacesAndNewlines)
        let initial = lastName.trimmingCharacters(in: .whitespacesAndNewlines).first
        return displayName(firstName: first, lastNameInitial: initial.map(String.init))
    }

    public static func displayName(firstName: String, lastNameInitial: String?) -> String {
        let first = firstName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let initial = lastNameInitial?.trimmingCharacters(in: .whitespacesAndNewlines).first else {
            return first
        }
        if first.isEmpty {
            return "\(initial)."
        }
        return "\(first) \(initial)."
    }

    public static func normalizedID(firstName: String, lastName: String) -> String {
        let initial = lastName.trimmingCharacters(in: .whitespacesAndNewlines).first.map(String.init)
        return normalizedID(firstName: firstName, lastNameInitial: initial)
    }

    public static func normalizedID(firstName: String, lastNameInitial: String?) -> String {
        let initial = lastNameInitial?.trimmingCharacters(in: .whitespacesAndNewlines).first.map(String.init) ?? ""
        let combined = (firstName + initial).lowercased()
        let scalars = combined.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }
        return String(String.UnicodeScalarView(scalars))
    }

    public func validated() throws -> Client {
        if firstName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw ClientValidationError.emptyName
        }
        if firstName.count > 80 {
            throw ClientValidationError.nameTooLong
        }
        return self
    }
}
