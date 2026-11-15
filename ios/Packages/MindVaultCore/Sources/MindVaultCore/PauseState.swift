import Foundation

public struct PauseState: Equatable, Sendable {
    public enum Phase: String, Codable, Sendable {
        case idle
        case running
        case paused
        case stopped
    }

    public private(set) var phase: Phase
    public private(set) var accumulated: TimeInterval
    public private(set) var startedAt: Date?
    public private(set) var pausedAt: Date?

    public init(phase: Phase = .idle, accumulated: TimeInterval = 0, startedAt: Date? = nil, pausedAt: Date? = nil) {
        self.phase = phase
        self.accumulated = max(0, accumulated)
        self.startedAt = startedAt
        self.pausedAt = pausedAt
    }

    public var isPaused: Bool { phase == .paused }
    public var isRunning: Bool { phase == .running }

    public mutating func start(at date: Date) {
        phase = .running
        accumulated = 0
        startedAt = date
        pausedAt = nil
    }

    public mutating func pause(at date: Date) {
        guard phase == .running, let startedAt else { return }
        accumulated += max(0, date.timeIntervalSince(startedAt))
        self.startedAt = nil
        pausedAt = date
        phase = .paused
    }

    public mutating func resume(at date: Date) {
        guard phase == .paused else { return }
        startedAt = date
        pausedAt = nil
        phase = .running
    }

    @discardableResult
    public mutating func stop(at date: Date) -> TimeInterval {
        if phase == .running, let startedAt {
            accumulated += max(0, date.timeIntervalSince(startedAt))
        }
        startedAt = nil
        pausedAt = nil
        phase = .stopped
        return accumulated
    }

    public func elapsed(at date: Date) -> TimeInterval {
        guard phase == .running, let startedAt else { return accumulated }
        return accumulated + max(0, date.timeIntervalSince(startedAt))
    }
}
