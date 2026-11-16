import Foundation
import SwiftUI
import MindVaultCore

@main
struct MindVaultApp: App {
    @StateObject private var environment: AppEnvironment

    init() {
        #if DEBUG
        // CI screenshots (issue #133): `-MVShot <screen>` picks the screen and its data.
        if let screenshotEnvironment = ScreenshotHarness.environment() {
            _environment = StateObject(wrappedValue: screenshotEnvironment)
            return
        }
        #endif
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
