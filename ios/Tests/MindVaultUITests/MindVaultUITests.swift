import XCTest

final class MindVaultUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testPreviewLaunchShowsHome() {
        let app = XCUIApplication()
        app.launchArguments += ["-ui-testing"]
        app.launch()

        let home = app.descendants(matching: .any)["home.screen"]
        XCTAssertTrue(home.waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["CLIENTS"].exists)
    }
}
