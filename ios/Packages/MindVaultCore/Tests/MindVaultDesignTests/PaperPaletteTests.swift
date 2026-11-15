import XCTest
@testable import MindVaultDesign

final class PaperPaletteTests: XCTestCase {
    func testHexParsing() {
        XCTAssertEqual(RGBColor(hex: "#EDE6D9")?.hex, "#EDE6D9")
        XCTAssertEqual(PaperPalette.ink.hex, "#2B221B")
        XCTAssertNil(RGBColor(hex: "#XYZ"))
    }

    func testContrastOnPaper() {
        XCTAssertGreaterThan(PaperPalette.ink.contrastRatio(to: PaperPalette.paper), 12)
        XCTAssertGreaterThan(PaperPalette.inkSoft.contrastRatio(to: PaperPalette.paper), 5)
        XCTAssertGreaterThan(PaperPalette.inkFaint.contrastRatio(to: PaperPalette.paper), 3)
        XCTAssertGreaterThan(PaperPalette.onColor.contrastRatio(to: PaperPalette.rec), 4.5)
        XCTAssertGreaterThan(PaperPalette.onColor.contrastRatio(to: PaperPalette.rev), 4.5)
    }

    func testSpringRatios() {
        XCTAssertEqual(PaperMotion.pull.dampingRatio, 26 / (2 * 300.0.squareRoot()), accuracy: 0.0001)
        XCTAssertEqual(PaperMotion.fade.dampingRatio, 1, accuracy: 0.0001)
        XCTAssertEqual(PaperMotion.arrive.dampingRatio, 1, accuracy: 0.0001)
    }

    func testTypographyVoices() {
        XCTAssertEqual(PaperTypography.hero.family, .fraunces)
        XCTAssertEqual(PaperTypography.body.family, .geist)
        XCTAssertEqual(PaperTypography.label.family, .geistMono)
        XCTAssertTrue(PaperTypography.label.uppercase)
    }
}
