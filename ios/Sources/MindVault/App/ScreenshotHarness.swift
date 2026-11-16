#if DEBUG
import Foundation
import MindVaultCore
import SwiftUI

/// DEBUG-only launch-argument harness for the CI screenshot job (lane A2b, issue #133).
///
/// Launch the app with `-MVShot <screen>` to open one screen deterministically, on the
/// fixture data of `MindVaultCore.Fixtures`, with no taps and no UI-test runner:
///
///     xcrun simctl launch <udid> com.therapylocal.app -MVShot home
///
/// Screens: `setup`, `unlock`, `home-empty`, `home`, `add-client`, `profile`, `session`,
/// `summary`, `settings`. The names match the Android screen tour (`ScreenList.kt`) where
/// the two apps have the same screen, so the parity pass can compare them one to one.
///
/// A launch without the argument is untouched, and nothing here is compiled into Release.
enum ScreenshotHarness {
    enum Screen: String {
        case setup      // first run: "Create your vault"
        case unlock     // returning user: enter the passphrase
        case homeEmpty  // unlocked, no clients
        case home       // unlocked, fixture clients
        case addClient  // the "New client" sheet over home
        case profile    // a client page: note, memory, sessions
        case session    // the idle session page for a client
        case summary    // the session review sheet
        case settings   // the settings sheet over home
    }

    /// The screen asked for on the command line, if any. Reads both `-MVShot home`
    /// (the `simctl launch` form) and `MVShot=home`.
    static let screen: Screen? = {
        let arguments = ProcessInfo.processInfo.arguments
        if let index = arguments.firstIndex(of: "-MVShot"), arguments.count > index + 1 {
            return parse(arguments[index + 1])
        }
        let prefix = "MVShot="
        if let argument = arguments.first(where: { $0.hasPrefix(prefix) }) {
            return parse(String(argument.dropFirst(prefix.count)))
        }
        return nil
    }()

    /// The environment to run in when a screen was asked for, else nil (a normal launch
    /// uses `AppEnvironment.live()`). The vault screens get an empty vault, so their data
    /// is never fabricated; the rest get the fixture clients, sessions and facts.
    static func environment() -> AppEnvironment? {
        switch screen {
        case .none:
            return nil
        case .setup:
            return AppEnvironment.screenshot(vaultState: .needsSetup, seeded: false)
        case .unlock:
            return AppEnvironment.screenshot(vaultState: .locked, seeded: false)
        case .homeEmpty:
            return AppEnvironment.screenshot(vaultState: .unlocked, seeded: false)
        case .home, .addClient, .profile, .session, .summary, .settings:
            return AppEnvironment.screenshot(vaultState: .unlocked, seeded: true)
        }
    }

    /// Drives the router (and the home sheet) to the requested screen. Called from
    /// `RootView.init`, before the first body, so the destination is there on first draw.
    @MainActor
    static func configure(router: AppRouter, home: HomeViewModel) {
        guard let screen else { return }
        switch screen {
        case .setup, .unlock, .homeEmpty, .home:
            break
        case .addClient:
            home.showingAddClient = true
        case .profile:
            router.openProfile(Fixtures.georgeID)
        case .session:
            if let client = Fixtures.clients.first {
                router.startSession(client)
            }
        case .summary:
            router.summarySession = Fixtures.sessions.first
        case .settings:
            router.showingSettings = true
        }
    }

    private static func parse(_ raw: String) -> Screen? {
        switch raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "setup", "gate-setup", "create": return .setup
        case "unlock", "gate-unlock": return .unlock
        case "home-empty", "homeempty": return .homeEmpty
        case "home": return .home
        case "add-client", "addclient": return .addClient
        case "profile": return .profile
        case "session": return .session
        case "summary": return .summary
        case "settings": return .settings
        default: return nil
        }
    }
}
#endif
