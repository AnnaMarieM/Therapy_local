import SwiftUI
import MindVaultCore

struct LabelText: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text.uppercased())
            .paperText(PaperTypography.label)
            .foregroundStyle(Color.inkSoft)
    }
}

struct SectionHeader: View {
    let title: String
    var detail: String? = nil

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: PaperSpacing.md) {
            LabelText(title)
            Rectangle()
                .fill(Color.hairline)
                .frame(height: 1)
            if let detail {
                Text(detail)
                    .paperText(PaperTypography.label)
                    .foregroundStyle(Color.inkFaint)
            }
        }
    }
}

struct PaperCard<Content: View>: View {
    var padding: Double = PaperSpacing.lg
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: PaperRadius.lg, style: .continuous)
                    .fill(Color.tonal.opacity(0.32))
            )
            .overlay(
                RoundedRectangle(cornerRadius: PaperRadius.lg, style: .continuous)
                    .stroke(Color.hairline, lineWidth: 1)
            )
    }
}

enum PaperButtonKind {
    case primary
    case record
    case secondary
    case quiet
    case destructive
}

struct PaperButtonStyle: ButtonStyle {
    let kind: PaperButtonKind

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .paperText(PaperTypography.action)
            .padding(.horizontal, PaperSpacing.xl)
            .padding(.vertical, PaperSpacing.md)
            .frame(maxWidth: .infinity)
            .foregroundStyle(foreground)
            .background(background.opacity(configuration.isPressed ? 0.82 : 1))
            .clipShape(RoundedRectangle(cornerRadius: PaperRadius.md, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.spring(response: 0.24, dampingFraction: 0.78), value: configuration.isPressed)
    }

    private var foreground: Color {
        switch kind {
        case .primary, .record:
            return .onColor
        case .secondary, .quiet:
            return .ink
        case .destructive:
            return .rec
        }
    }

    private var background: Color {
        switch kind {
        case .primary:
            return .ink
        case .record:
            return .rec
        case .secondary:
            return .tonal
        case .quiet, .destructive:
            return .clear
        }
    }
}

struct PaperField: View {
    let placeholder: String
    @Binding var text: String
    var isSecure: Bool = false
    var axis: Axis = .horizontal

    var body: some View {
        Group {
            if isSecure {
                SecureField(placeholder, text: $text)
            } else if axis == .vertical {
                TextField(placeholder, text: $text, axis: .vertical)
                    .lineLimit(3...6)
            } else {
                TextField(placeholder, text: $text)
            }
        }
        .font(.paper(PaperTypography.body))
        .foregroundStyle(Color.ink)
        .tint(Color.ink)
        .padding(.horizontal, PaperSpacing.md)
        .padding(.vertical, PaperSpacing.md)
        .background(
            RoundedRectangle(cornerRadius: PaperRadius.md, style: .continuous)
                .fill(Color.tonal.opacity(0.42))
        )
        .overlay(
            RoundedRectangle(cornerRadius: PaperRadius.md, style: .continuous)
                .stroke(Color.hairline, lineWidth: 1)
        )
    }
}

struct PrivacyBanner: View {
    var compact: Bool = false

    var body: some View {
        HStack(spacing: PaperSpacing.sm) {
            Image(systemName: "lock")
                .font(.system(size: 11, weight: .semibold))
            Text(compact ? "On-device · encrypted" : "On-device · encrypted · nothing uploaded")
                .paperText(PaperTypography.label)
                .foregroundStyle(Color.inkSoft)
        }
        .accessibilityElement(children: .combine)
    }
}

struct TagPill: View {
    let text: String
    var tint: Color = .inkSoft

    var body: some View {
        Text(text.uppercased())
            .paperText(PaperTypography.label)
            .foregroundStyle(tint)
            .padding(.horizontal, PaperSpacing.sm)
            .padding(.vertical, PaperSpacing.xs)
            .background(
                Capsule().fill(Color.tonal.opacity(0.55))
            )
    }
}

struct PulseDot: View {
    let color: Color
    @State private var pulse = false

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 8, height: 8)
            .scaleEffect(pulse ? 1.25 : 1)
            .opacity(pulse ? 0.6 : 1)
            .onAppear {
                withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
                    pulse = true
                }
            }
    }
}

struct EmptyState: View {
    let title: String
    let message: String

    var body: some View {
        VStack(alignment: .leading, spacing: PaperSpacing.sm) {
            Text(title)
                .paperText(PaperTypography.title)
            Text(message)
                .paperText(PaperTypography.body)
                .foregroundStyle(Color.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, PaperSpacing.xl)
    }
}

struct HoldToFinishButton: View {
    let action: () -> Void
    @State private var progress: Double = 0
    @State private var task: Task<Void, Never>?

    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.hairline, lineWidth: 2)
            Circle()
                .trim(from: 0, to: progress)
                .stroke(Color.rec, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
            VStack(spacing: 2) {
                Image(systemName: "checkmark")
                    .font(.system(size: 18, weight: .semibold))
                Text("Hold")
                    .paperText(PaperTypography.label)
            }
            .foregroundStyle(Color.ink)
        }
        .frame(width: 88, height: 88)
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in begin() }
                .onEnded { _ in cancel() }
        )
        .onDisappear { cancel() }
        .accessibilityLabel("Hold to finish session")
        .accessibilityIdentifier("session.finish")
    }

    private func begin() {
        guard task == nil else { return }
        task = Task {
            for step in 1...40 {
                if Task.isCancelled { return }
                try? await Task.sleep(for: .milliseconds(20))
                if Task.isCancelled { return }
                await MainActor.run {
                    progress = Double(step) / 40
                }
                if step == 40 {
                    await MainActor.run {
                        action()
                        reset()
                    }
                }
            }
        }
    }

    private func cancel() {
        task?.cancel()
        task = nil
        withAnimation(.easeOut(duration: 0.16)) {
            progress = 0
        }
    }

    private func reset() {
        task = nil
        progress = 0
    }
}

struct PaperConfirmDialog: View {
    let title: String
    let message: String
    let confirmTitle: String
    var destructive: Bool = true
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        ZStack {
            Color.scrim.ignoresSafeArea()
                .onTapGesture(perform: onCancel)
            VStack(alignment: .leading, spacing: PaperSpacing.lg) {
                Text(title)
                    .paperText(PaperTypography.title)
                Text(message)
                    .paperText(PaperTypography.body)
                    .foregroundStyle(Color.inkSoft)
                HStack(spacing: PaperSpacing.sm) {
                    Button("Cancel", action: onCancel)
                        .buttonStyle(PaperButtonStyle(kind: .secondary))
                    Button(confirmTitle, action: onConfirm)
                        .buttonStyle(PaperButtonStyle(kind: destructive ? .record : .primary))
                }
            }
            .padding(PaperSpacing.xl)
            .background(
                RoundedRectangle(cornerRadius: PaperRadius.xl, style: .continuous)
                    .fill(Color.paper)
            )
            .overlay(
                RoundedRectangle(cornerRadius: PaperRadius.xl, style: .continuous)
                    .stroke(Color.hairline, lineWidth: 1)
            )
            .padding(PaperSpacing.xl)
        }
        .accessibilityAddTraits(.isModal)
    }
}

struct NoticeHost: View {
    @ObservedObject var center: NoticeCenter

    var body: some View {
        VStack {
            if let notice = center.current {
                HStack(alignment: .top, spacing: PaperSpacing.sm) {
                    Image(systemName: icon(for: notice.kind))
                        .font(.system(size: 13, weight: .semibold))
                    Text(notice.message)
                        .paperText(PaperTypography.bodySmall)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .foregroundStyle(Color.onColor)
                .padding(PaperSpacing.md)
                .background(
                    RoundedRectangle(cornerRadius: PaperRadius.md, style: .continuous)
                        .fill(color(for: notice.kind))
                )
                .padding(.horizontal, PaperSpacing.lg)
                .padding(.top, PaperSpacing.sm)
                .transition(.move(edge: .top).combined(with: .opacity))
                .accessibilityIdentifier("notice")
            }
            Spacer()
        }
        .animation(.spring(response: 0.3, dampingFraction: 0.9), value: center.current)
        .task(id: center.current?.id) {
            guard center.current != nil else { return }
            try? await Task.sleep(for: .seconds(3))
            center.dismiss()
        }
    }

    private func icon(for kind: NoticeCenter.Notice.Kind) -> String {
        switch kind {
        case .success: return "checkmark.circle"
        case .error: return "exclamationmark.triangle"
        case .info: return "info.circle"
        }
    }

    private func color(for kind: NoticeCenter.Notice.Kind) -> Color {
        switch kind {
        case .success: return .rev
        case .error: return .rec
        case .info: return .ink
        }
    }
}
