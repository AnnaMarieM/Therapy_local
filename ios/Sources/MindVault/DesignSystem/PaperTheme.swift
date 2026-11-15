import SwiftUI
import MindVaultCore

extension Color {
    init(paper color: RGBColor) {
        self.init(
            .sRGB,
            red: Double(color.red) / 255,
            green: Double(color.green) / 255,
            blue: Double(color.blue) / 255,
            opacity: 1
        )
    }

    static let paper = Color(paper: PaperPalette.paper)
    static let ink = Color(paper: PaperPalette.ink)
    static let tonal = Color(paper: PaperPalette.tonal)
    static let rec = Color(paper: PaperPalette.rec)
    static let rev = Color(paper: PaperPalette.rev)
    static let onColor = Color(paper: PaperPalette.onColor)
    static let inkSoft = Color(paper: PaperPalette.inkSoft)
    static let inkFaint = Color(paper: PaperPalette.inkFaint)
    static let hairline = Color(paper: PaperPalette.hairline)
    static let scrim = Color(paper: PaperPalette.scrim)
}

extension Font {
    static func paper(_ style: PaperTextStyle) -> Font {
        let name: String
        switch style.family {
        case .fraunces:
            name = style.italic ? "Fraunces-Italic" : "Fraunces-Regular"
        case .geist:
            name = "Geist-Regular"
        case .geistMono:
            name = "GeistMono-Regular"
        }
        let base = Font.custom(name, size: CGFloat(style.size))
        let weighted = base.weight(weight(for: style.weight))
        return style.italic ? weighted.italic() : weighted
    }

    private static func weight(for value: Int) -> Font.Weight {
        switch value {
        case ..<250: return .ultraLight
        case 250..<350: return .light
        case 350..<450: return .regular
        case 450..<550: return .medium
        case 550..<650: return .semibold
        default: return .bold
        }
    }
}

struct PaperTextModifier: ViewModifier {
    let style: PaperTextStyle

    func body(content: Content) -> some View {
        content
            .font(.paper(style))
            .tracking(CGFloat(style.tracking * style.size))
            .lineSpacing(CGFloat(max(0, style.lineHeight - style.size) * 0.5))
    }
}

extension View {
    func paperText(_ style: PaperTextStyle) -> some View {
        modifier(PaperTextModifier(style: style))
    }
}

struct PaperGrain: View {
    var body: some View {
        Canvas { context, size in
            var state: UInt64 = 0x9E3779B97F4A7C15
            for _ in 0..<520 {
                state = state &* 6364136223846793005 &+ 1442695040888963407
                let x = CGFloat(Double(state % 10_000) / 10_000) * size.width
                state = state &* 6364136223846793005 &+ 1442695040888963407
                let y = CGFloat(Double(state % 10_000) / 10_000) * size.height
                let dot = CGRect(x: x, y: y, width: 1, height: 1)
                context.fill(Path(ellipseIn: dot), with: .color(Color.ink.opacity(0.035)))
            }
        }
        .allowsHitTesting(false)
    }
}

struct PaperScreen<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ZStack {
            Color.paper.ignoresSafeArea()
            PaperGrain().ignoresSafeArea()
            content
        }
        .foregroundStyle(Color.ink)
        .tint(Color.ink)
    }
}

struct PaperDivider: View {
    var body: some View {
        Rectangle()
            .fill(Color.hairline)
            .frame(height: 1)
    }
}
