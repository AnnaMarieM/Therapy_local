import Foundation
import MindVaultCore
import SwiftUI

@MainActor
final class SettingsViewModel: ObservableObject {
    @Published private(set) var report: CapabilityReport?
    @Published var notificationsEnabled = false

    private let capabilities: CapabilityService
    private let notifications: NotificationService
    private let notices: NoticeCenter

    init(capabilities: CapabilityService, notifications: NotificationService, notices: NoticeCenter) {
        self.capabilities = capabilities
        self.notifications = notifications
        self.notices = notices
    }

    func load() {
        report = capabilities.report()
    }

    func setNotificationsEnabled(_ enabled: Bool) async {
        guard enabled else {
            notificationsEnabled = false
            return
        }
        let granted = await notifications.requestAuthorization()
        notificationsEnabled = granted
        if !granted {
            notices.info("Notifications can be enabled in iOS Settings.")
        }
    }
}
