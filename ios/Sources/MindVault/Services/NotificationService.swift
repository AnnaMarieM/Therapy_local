import Foundation
import UserNotifications

final class NotificationService: @unchecked Sendable {
    func requestAuthorization() async -> Bool {
        do {
            return try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
        } catch {
            return false
        }
    }

    func notifyProcessingComplete(sessionID: UUID) async {
        let content = UNMutableNotificationContent()
        content.title = "Session ready"
        content.body = "Your on-device transcript is ready to review."
        content.sound = .default
        let request = UNNotificationRequest(
            identifier: "session-\(sessionID.uuidString)",
            content: content,
            trigger: nil
        )
        try? await UNUserNotificationCenter.current().add(request)
    }
}
