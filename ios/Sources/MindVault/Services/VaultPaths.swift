import Foundation

struct VaultPaths: Sendable {
    let root: URL

    var vaultURL: URL {
        root.appendingPathComponent("vault.json", isDirectory: false)
    }

    var pendingAudioDirectory: URL {
        root.appendingPathComponent("pending-audio", isDirectory: true)
    }

    static func live() -> VaultPaths {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return VaultPaths(root: base.appendingPathComponent("MindVault", isDirectory: true))
    }

    static func preview() -> VaultPaths {
        VaultPaths(root: FileManager.default.temporaryDirectory.appendingPathComponent("MindVaultPreview", isDirectory: true))
    }

    func prepare() throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: pendingAudioDirectory, withIntermediateDirectories: true)
        #if os(iOS)
        try? FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUnlessOpen],
            ofItemAtPath: root.path
        )
        try? FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUnlessOpen],
            ofItemAtPath: pendingAudioDirectory.path
        )
        #endif
    }

    func erase() throws {
        let manager = FileManager.default
        if manager.fileExists(atPath: vaultURL.path) {
            try manager.removeItem(at: vaultURL)
        }
        if manager.fileExists(atPath: pendingAudioDirectory.path) {
            try manager.removeItem(at: pendingAudioDirectory)
        }
    }
}
