import Foundation
import MindVaultModels

public struct JobQueue: Equatable, Sendable {
    public private(set) var jobs: [Job]

    public init(jobs: [Job] = []) {
        self.jobs = jobs
    }

    public var pendingCount: Int {
        jobs.filter { !$0.status.isFinished }.count
    }

    public func job(id: UUID) -> Job? {
        jobs.first { $0.id == id }
    }

    @discardableResult
    public mutating func enqueue(
        kind: JobKind,
        sessionID: UUID? = nil,
        chunkIndex: Int? = nil,
        now: Date = Date()
    ) -> Job {
        let job = Job(kind: kind, sessionID: sessionID, chunkIndex: chunkIndex, createdAt: now)
        jobs.append(job)
        return job
    }

    @discardableResult
    public mutating func next(now: Date = Date()) -> Job? {
        guard let index = jobs.firstIndex(where: { $0.status == .queued }) else { return nil }
        jobs[index].status = .running
        jobs[index].attempts += 1
        jobs[index].updatedAt = now
        return jobs[index]
    }

    public mutating func markRunning(id: UUID, now: Date = Date()) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        jobs[index].status = .running
        jobs[index].updatedAt = now
    }

    public mutating func complete(id: UUID, now: Date = Date()) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        jobs[index].status = .done
        jobs[index].progress = 1
        jobs[index].updatedAt = now
    }

    public mutating func fail(id: UUID, error: String, maxAttempts: Int, now: Date = Date()) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        jobs[index].lastError = error
        jobs[index].updatedAt = now
        jobs[index].status = jobs[index].attempts >= max(1, maxAttempts) ? .failed : .queued
    }

    public mutating func cancel(id: UUID, now: Date = Date()) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        jobs[index].status = .cancelled
        jobs[index].updatedAt = now
    }

    public mutating func retry(id: UUID, now: Date = Date()) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        jobs[index].status = .queued
        jobs[index].attempts = 0
        jobs[index].lastError = nil
        jobs[index].progress = nil
        jobs[index].updatedAt = now
    }

    public mutating func updateProgress(id: UUID, progress: Double, now: Date = Date()) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        jobs[index].progress = min(1, max(0, progress))
        jobs[index].updatedAt = now
    }
}
