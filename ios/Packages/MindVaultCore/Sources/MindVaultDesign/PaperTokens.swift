import Foundation

public struct RGBColor: Hashable, Sendable {
    public let red: Int
    public let green: Int
    public let blue: Int

    public init(red: Int, green: Int, blue: Int) {
        self.red = min(255, max(0, red))
        self.green = min(255, max(0, green))
        self.blue = min(255, max(0, blue))
    }

    public init?(hex: String) {
        var value = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.hasPrefix("#") { value.removeFirst() }
        guard value.count == 6, let number = Int(value, radix: 16) else { return nil }
        self.init(
            red: (number >> 16) & 0xFF,
            green: (number >> 8) & 0xFF,
            blue: number & 0xFF
        )
    }

    public var hex: String {
        String(format: "#%02X%02X%02X", red, green, blue)
    }

    public func blended(over background: RGBColor, alpha: Double) -> RGBColor {
        let clamped = min(1, max(0, alpha))
        func mix(_ foreground: Int, _ behind: Int) -> Int {
            Int((Double(foreground) * clamped + Double(behind) * (1 - clamped)).rounded())
        }
        return RGBColor(
            red: mix(red, background.red),
            green: mix(green, background.green),
            blue: mix(blue, background.blue)
        )
    }

    public var relativeLuminance: Double {
        func channel(_ value: Int) -> Double {
            let normalized = Double(value) / 255
            return normalized <= 0.04045
                ? normalized / 12.92
                : pow((normalized + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
    }

    public func contrastRatio(to other: RGBColor) -> Double {
        let lighter = max(relativeLuminance, other.relativeLuminance)
        let darker = min(relativeLuminance, other.relativeLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }
}

public enum PaperPalette {
    public static let paper = RGBColor(red: 0xED, green: 0xE6, blue: 0xD9)
    public static let ink = RGBColor(red: 0x2B, green: 0x22, blue: 0x1B)
    public static let tonal = RGBColor(red: 0xDE, green: 0xD5, blue: 0xC4)
    public static let rec = RGBColor(red: 0xC9, green: 0x36, blue: 0x1F)
    public static let rev = RGBColor(red: 0x2C, green: 0x7A, blue: 0x62)
    public static let onColor = RGBColor(red: 0xFF, green: 0xFF, blue: 0xFF)

    public static let inkSoftAlpha = 0.72
    public static let inkFaintAlpha = 0.55
    public static let hairlineAlpha = 0.16
    public static let scrimAlpha = 0.22

    public static var inkSoft: RGBColor { ink.blended(over: paper, alpha: inkSoftAlpha) }
    public static var inkFaint: RGBColor { ink.blended(over: paper, alpha: inkFaintAlpha) }
    public static var hairline: RGBColor { ink.blended(over: paper, alpha: hairlineAlpha) }
    public static var scrim: RGBColor { ink.blended(over: paper, alpha: scrimAlpha) }
}

public enum PaperSpacing {
    public static let xxs: Double = 2
    public static let xs: Double = 4
    public static let sm: Double = 8
    public static let md: Double = 12
    public static let lg: Double = 16
    public static let xl: Double = 24
    public static let xxl: Double = 32
    public static let xxxl: Double = 48
}

public enum PaperRadius {
    public static let sm: Double = 8
    public static let md: Double = 12
    public static let lg: Double = 18
    public static let xl: Double = 24
    public static let pill: Double = 999
}

public enum PaperFontFamily: String, Sendable {
    case fraunces = "Fraunces"
    case geist = "Geist"
    case geistMono = "GeistMono"
}

public struct PaperTextStyle: Hashable, Sendable {
    public let family: PaperFontFamily
    public let size: Double
    public let weight: Int
    public let italic: Bool
    public let tracking: Double
    public let lineHeight: Double
    public let uppercase: Bool

    public init(
        family: PaperFontFamily,
        size: Double,
        weight: Int = 400,
        italic: Bool = false,
        tracking: Double = 0,
        lineHeight: Double,
        uppercase: Bool = false
    ) {
        self.family = family
        self.size = size
        self.weight = weight
        self.italic = italic
        self.tracking = tracking
        self.lineHeight = lineHeight
        self.uppercase = uppercase
    }
}

public enum PaperTypography {
    public static let hero = PaperTextStyle(
        family: .fraunces, size: 64, weight: 260, tracking: -0.04, lineHeight: 60
    )
    public static let statement = PaperTextStyle(
        family: .fraunces, size: 40, weight: 260, tracking: -0.03, lineHeight: 42
    )
    public static let title = PaperTextStyle(
        family: .fraunces, size: 26, weight: 260, tracking: -0.02, lineHeight: 31
    )
    public static let lead = PaperTextStyle(
        family: .fraunces, size: 18, weight: 340, italic: true, lineHeight: 24
    )
    public static let body = PaperTextStyle(
        family: .geist, size: 17, weight: 400, tracking: -0.005, lineHeight: 26
    )
    public static let bodySmall = PaperTextStyle(
        family: .geist, size: 15, weight: 400, lineHeight: 22
    )
    public static let action = PaperTextStyle(
        family: .geist, size: 17, weight: 500, tracking: -0.005, lineHeight: 20
    )
    public static let label = PaperTextStyle(
        family: .geistMono, size: 12, weight: 400, tracking: 0.05, lineHeight: 16, uppercase: true
    )
}

public struct SpringToken: Hashable, Sendable {
    public let stiffness: Double
    public let dampingRatio: Double

    public init(stiffness: Double, damping: Double) {
        self.stiffness = stiffness
        self.dampingRatio = damping / (2 * stiffness.squareRoot())
    }
}

public enum PaperMotion {
    public static let pull = SpringToken(stiffness: 300, damping: 26)
    public static let pop = SpringToken(stiffness: 300, damping: 12)
    public static let sheet = SpringToken(stiffness: 300, damping: 31)
    public static let `throw` = SpringToken(stiffness: 420, damping: 26)
    public static let reel = SpringToken(stiffness: 150, damping: 21)
    public static let fade = SpringToken(stiffness: 200, damping: 28.2842712475)
    public static let arrive = SpringToken(stiffness: 70, damping: 16.7332005307)
    public static let reducedFadeDuration: Double = 0.15
}

public enum PaperMetadata {
    public static let displayName = "MindVault"
    public static let marketingVersion = "0.1.0"
    public static let bundleIdentifier = "com.therapylocal.app"
}
