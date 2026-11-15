import Foundation
import SwiftUI
import MindVaultCore

@main
struct MindVaultApp: App {
    @StateObject private var environment: AppEnvironment

    init() {
        let isUITesting = ProcessInfo.processInfo.arguments.contains("-ui-testing")
        _environment = StateObject(wrappedValue: isUITesting ? AppEnvironment.preview() : AppEnvironment.live())
    }

    var body: some Scene {
        WindowGroup {
            RootView(app: environment)
                .preferredColorScheme(.light)
        }
    }
}
