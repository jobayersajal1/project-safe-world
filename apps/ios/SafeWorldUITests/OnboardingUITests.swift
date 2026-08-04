import XCTest

/// Walks the first-run wizard the way a person would.
///
/// **Why UI tests exist for this app at all.** Everything the wizard does is a
/// sequence — answer, apply, advance — and a screenshot of the first screen
/// proves only that the first screen renders. The failures worth catching are
/// the ones further in: a step that applies nothing, a branch that skips a
/// question, a "finish" that leaves setup unfinished. Those need the app driven,
/// and `xcodebuild test` drives it from inside the simulator, where no macOS
/// accessibility permission is involved.
///
/// State is forced through launch arguments rather than by deleting the app
/// between tests: they land in `NSArgumentDomain`, which outranks anything
/// stored, so each test starts from a known step no matter what the last one
/// left behind.
final class OnboardingUITests: XCTestCase {

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    private func launchAtOnboarding() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += [
            "-onboardingComplete", "NO",
            "-onboardingStep", "0",
            // Pinned so the test reads buttons by their English labels rather
            // than by whatever the simulator's language happens to be.
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US",
        ]
        app.launch()
        return app
    }

    /// The whole flow, declining everything optional.
    ///
    /// Declining is the more interesting path: it exercises the two jumps — past
    /// the PIN detour and past Safari — that a naive "advance by one" would get
    /// wrong.
    func testWizardCompletesWhenEverythingIsDeclined() {
        let app = launchAtOnboarding()

        XCTAssertTrue(app.staticTexts["Choose your language"].waitForExistence(timeout: 10))
        app.buttons["Continue"].tap()

        XCTAssertTrue(app.staticTexts["Set a PIN?"].waitForExistence(timeout: 5))
        app.buttons["Not now"].tap()

        // Declining the PIN must skip the recovery code too — there is nothing
        // to recover — and land on the first real question.
        XCTAssertTrue(
            app.staticTexts["Block social media and dating?"].waitForExistence(timeout: 5),
            "declining the PIN should skip straight to the first blocking question"
        )
        app.buttons["No"].tap()

        XCTAssertTrue(app.staticTexts["Block harmful games?"].waitForExistence(timeout: 5))
        app.buttons["No"].tap()

        XCTAssertTrue(app.staticTexts["Block entertainment?"].waitForExistence(timeout: 5))
        app.buttons["No"].tap()

        XCTAssertTrue(app.staticTexts["Turn on the Safari blocker"].waitForExistence(timeout: 5))
        app.buttons["Not now"].tap()

        XCTAssertTrue(app.staticTexts["You're set up"].waitForExistence(timeout: 5))
        app.buttons["Finish"].tap()

        // Finishing must reach the app itself, not loop back into setup.
        XCTAssertTrue(
            app.buttons["Home"].waitForExistence(timeout: 10),
            "finishing setup should land on the tabs"
        )
    }

    /// Answering yes must actually switch the category on, not just advance.
    ///
    /// Checked through the home screen rather than through storage, because the
    /// row reading "on" is the claim the user believes — and it is only true
    /// when both halves of the subject were applied.
    func testAnsweringYesTurnsTheCategoryOn() {
        let app = launchAtOnboarding()

        app.buttons["Continue"].tap()
        XCTAssertTrue(app.staticTexts["Set a PIN?"].waitForExistence(timeout: 5))
        app.buttons["Not now"].tap()

        XCTAssertTrue(app.staticTexts["Block social media and dating?"].waitForExistence(timeout: 5))
        app.buttons["Yes, block these"].tap()

        XCTAssertTrue(app.staticTexts["Block harmful games?"].waitForExistence(timeout: 5))
        app.buttons["No"].tap()
        XCTAssertTrue(app.staticTexts["Block entertainment?"].waitForExistence(timeout: 5))
        app.buttons["No"].tap()
        XCTAssertTrue(app.staticTexts["Turn on the Safari blocker"].waitForExistence(timeout: 5))
        app.buttons["Not now"].tap()
        app.buttons["Finish"].tap()

        XCTAssertTrue(app.buttons["Home"].waitForExistence(timeout: 10))

        // SwiftUI folds a Toggle's title and its description into a single
        // accessibility label, so an exact-string query never matches.
        let social = app.switches.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Block social media")
        ).firstMatch
        XCTAssertTrue(social.waitForExistence(timeout: 5))
        XCTAssertEqual(
            social.value as? String, "1",
            "answering yes in setup should leave the row switched on"
        )

        let games = app.switches.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Block harmful games")
        ).firstMatch
        XCTAssertTrue(games.waitForExistence(timeout: 5))
        XCTAssertEqual(
            games.value as? String, "0",
            "declining should leave the row off — setup must not turn on what was refused"
        )
    }

    /// The bug that shipped on Android first: closing the app after the first
    /// answer came back with setup considered finished, silently skipping the
    /// blocking questions.
    func testProgressResumesInsteadOfSkipping() {
        let app = launchAtOnboarding()
        app.buttons["Continue"].tap()
        XCTAssertTrue(app.staticTexts["Set a PIN?"].waitForExistence(timeout: 5))

        app.terminate()

        // Relaunched without forcing the *step*, so it has to come from
        // storage. `onboardingComplete` is still forced, because tests share one
        // app container and whichever test finishes setup would otherwise decide
        // this one's result — a shared-state failure dressed up as a bug.
        let resumed = XCUIApplication()
        resumed.launchArguments += [
            "-onboardingComplete", "NO",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
        ]
        resumed.launch()

        XCTAssertTrue(
            resumed.staticTexts["Set a PIN?"].waitForExistence(timeout: 10),
            "setup should resume at the step it stopped on, not restart from the first question"
        )
    }
}
