import Foundation
import MindVaultCore

protocol Clock: Sendable {
    func now() -> Date
}

struct SystemClock: Clock {
    func now() -> Date {
        Date()
    }
}

struct FixedClock: Clock {
    let date: Date

    func now() -> Date {
        date
    }
}
