import Foundation
import MindVaultModels

public enum IDs {
    public static func normalizedClientID(firstName: String, lastName: String) -> String {
        Client.normalizedID(firstName: firstName, lastName: lastName)
    }

    public static func sessionExternalID(
        firstName: String,
        lastName: String,
        date: Date,
        calendar: Calendar = .current,
        timeZone: TimeZone = .current
    ) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = calendar
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        let base = normalizedClientID(firstName: firstName, lastName: lastName)
        return "\(base)_\(formatter.string(from: date))"
    }

    public static func sessionExternalID(client: Client, date: Date) -> String {
        sessionExternalID(firstName: client.firstName, lastName: client.lastNameInitial ?? "", date: date)
    }
}
