#if DEBUG
import Foundation
import MindVaultCore
import SwiftUI

/// DEBUG-only launch-argument harness for the CI screenshot job (issue #133).
///
/// Launch the app with `-MVShot <screen>` to open one screen deterministically, on the
/// tour data of `MindVaultCore.Fixtures` (the Android tour's fake clients), with no taps
/// and no UI-test runner:
///
///     xcrun simctl launch <udid> com.therapylocal.app -MVShot profile
///
/// The screen list is the raw values of [`Screen`]; the CI job derives its capture
/// loop from them (`.github/workflows/ios.yml`), so a screen added here is captured
/// there without edits. The names follow the Android screen tour (`ScreenList.kt`)
/// where the two apps have the same screen; screens only one app has are captured
/// anyway and reported as differences — that is the point of the parity pass.
///
/// A launch without the argument is untouched, and nothing here is compiled into Release.
enum ScreenshotHarness {
    /// Every capturable state of the DEBUG app. `rawValue` is the `-MVShot`
    /// argument and one line per case is what the CI job parses, so keep the
    /// `case x = "x"` layout on one line each.
    enum Screen: String, CaseIterable {
        case setup = "setup"                        // first run: "Create your vault"
        case setupFilled = "setup-filled"           // both passphrase fields typed
        case unlock = "unlock"                      // returning user: enter the passphrase
        case eraseVault = "erase-vault"             // "Erase everything?" dialog over the gate
        case homeEmpty = "home-empty"               // unlocked, no clients
        case home = "home"                          // unlocked, the tour clients
        case addClient = "add-client"               // the "New client" sheet over home
        case addClientFilled = "add-client-filled"  // the sheet with a name typed in
        case notice = "notice"                      // a notice banner over home
        case profile = "profile"                    // Ana's page: facts, sessions
        case profileSearch = "profile-search"       // Ana's page with a search under way
        case profileDelete = "profile-delete"       // "Delete Ana?" dialog over the profile
        case session = "session"                    // the session page, ready to record
        case sessionRecording = "session-recording" // recording: clock, meter, one cue
        case sessionDiscard = "session-discard"     // "Discard this recording?" dialog
        case summary = "summary"                    // the session review sheet
        case settings = "settings"                  // the settings sheet over home
    }

    /// The screen asked for on the command line. Reads both `-MVShot home`
    /// (the `simctl launch` form) and `MVShot=home`. An unrecognized value is
    /// a CI job bug: it fails the launch loudly with the valid names instead
    /// of silently opening the app's first screen, which the job would
    /// screenshot and the parity compare would misread.
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

    private static func parse(_ raw: String) -> Screen {
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let screen = Screen(rawValue: name) {
            return screen
        }
        fatalError("unknown -MVShot screen '\(raw)'; valid screens: \(Screen.allCases.map(\.rawValue).joined(separator: ", "))")
    }

    /// The environment to run in when a screen was asked for, else nil (a normal launch
    /// uses `AppEnvironment.live()`). The vault screens get an empty vault, so their data
    /// is never fabricated; the rest get the tour clients, sessions and facts.
    @MainActor
    static func environment() -> AppEnvironment? {
        guard let screen else { return nil }
        switch screen {
        case .setup, .setupFilled:
            return AppEnvironment.screenshot(vaultState: .needsSetup, seeded: false)
        case .unlock, .eraseVault:
            return AppEnvironment.screenshot(vaultState: .locked, seeded: false)
        case .homeEmpty:
            return AppEnvironment.screenshot(vaultState: .unlocked, seeded: false)
        case .home, .addClient, .addClientFilled, .notice, .profile, .profileSearch,
             .profileDelete, .session, .sessionRecording, .sessionDiscard, .summary, .settings:
            return AppEnvironment.screenshot(vaultState: .unlocked, seeded: true)
        }
    }

    /// Drives the router (and the home sheet) to the requested screen. Called from
    /// `RootView.init`, before the first body, so the destination is there on first draw.
    @MainActor
    static func configure(app: AppEnvironment, router: AppRouter, home: HomeViewModel) {
        guard let screen else { return }
        switch screen {
        case .setup, .setupFilled, .unlock, .eraseVault, .homeEmpty, .home:
            break
        case .addClient:
            home.showingAddClient = true
        case .addClientFilled:
            home.showingAddClient = true
            home.firstName = "Dana"
            home.lastName = "Lewis"
        case .notice:
            app.notices.success("Ana added")
        case .profile, .profileSearch, .profileDelete:
            router.openProfile(Fixtures.anaID)
        case .session, .sessionRecording, .sessionDiscard:
            router.startSession(Fixtures.clients[0])
        case .summary:
            router.summarySession = Fixtures.sessions[0]
        case .settings:
            router.showingSettings = true
        }
    }

    // MARK: view-level seeds

    /// Seeds the gate's `@State` fields for `setup-filled` and `erase-vault`
    /// (the setup screen shows what a filled-in form looks like, and the erase
    /// dialog is the iOS side of Android's "Forgot password" flow). The
    /// passphrase is the tour's `PASSWORD` (12345678), never submitted.
    @MainActor
    static func seedVaultGate(
        password: (String) -> Void,
        confirmation: (String) -> Void,
        showErase: () -> Void
    ) {
        guard let screen else { return }
        switch screen {
        case .setupFilled:
            password("12345678")
            confirmation("12345678")
        case .eraseVault:
            showErase()
        default:
            break
        }
    }

    /// Seeds the session page for `session-recording` (a recording 22:05 in,
    /// one cue, level meter alive — the Android tour's recording state) and
    /// `session-discard` (the discard dialog over a ready session).
    @MainActor
    static func seedSession(record: () -> Void, showDiscard: () -> Void) {
        guard let screen else { return }
        switch screen {
        case .sessionRecording:
            record()
        case .sessionDiscard:
            showDiscard()
        default:
            break
        }
    }

    /// The tour cue for `session-recording`: the fact the Android tour cues on
    /// ("Runs again", `cue = Cue(HEADING, CONTENT)`).
    static func recordingCue() -> MemoryCue? {
        guard let fact = Fixtures.facts.first else { return nil }
        return MemoryCue(
            factID: fact.id,
            clientID: fact.clientID,
            sessionID: fact.sessionID,
            category: fact.category,
            text: fact.text,
            score: 0.92
        )
    }
}
#endif
