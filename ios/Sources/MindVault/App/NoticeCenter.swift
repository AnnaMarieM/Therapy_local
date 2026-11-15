import Foundation
import SwiftUI

@MainActor
final class NoticeCenter: ObservableObject {
    struct Notice: Identifiable, Equatable {
        enum Kind: Equatable {
            case success
            case error
            case info
        }

        let id = UUID()
        let kind: Kind
        let message: String
    }

    @Published private(set) var current: Notice?

    func show(_ message: String, kind: Notice.Kind) {
        current = Notice(kind: kind, message: message)
    }

    func success(_ message: String) {
        show(message, kind: .success)
    }

    func error(_ message: String) {
        show(message, kind: .error)
    }

    func info(_ message: String) {
        show(message, kind: .info)
    }

    func dismiss() {
        current = nil
    }
}
