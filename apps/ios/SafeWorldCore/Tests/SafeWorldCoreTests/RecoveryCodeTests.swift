import XCTest
@testable import SafeWorldCore

/// Mirrors `apps/android/app/src/test/kotlin/com/safeworld/app/security/RecoveryCodeTest.kt`,
/// including the folding vectors — a code written down off an Android phone and
/// typed into an iPhone has to normalize the same way, and the only thing that
/// keeps the two `normalize` implementations honest is pinning the same cases on
/// both sides.
final class RecoveryCodeTests: XCTestCase {

    func testAGeneratedCodeIsWellFormedAndSelfConsistent() {
        let code = RecoveryCode.generate()
        XCTAssertTrue(code.contains("-"), "should be dash-grouped for reading: \(code)")
        XCTAssertTrue(RecoveryCode.isWellFormed(code))
        XCTAssertEqual(20, RecoveryCode.normalize(code).count)
        XCTAssertEqual("XXXXX-XXXXX-XXXXX-XXXXX".count, code.count)
    }

    func testCodesDoNotRepeat() {
        let codes = (0..<50).map { _ in RecoveryCode.generate() }
        XCTAssertEqual(codes.count, Set(codes).count, "generated codes must be unique")
    }

    func testTheAmbiguousLettersNeverAppearSoTheyCanOnlyBeMisreadings() {
        let generated = (0..<200).map { _ in RecoveryCode.normalize(RecoveryCode.generate()) }.joined()
        for character in "ILOU" {
            XCTAssertFalse(
                generated.contains(character),
                "'\(character)' is too easily misread to be in the alphabet"
            )
        }
    }

    func testACodeReadOffPaperVerifiesHoweverItWasTyped() {
        let code = RecoveryCode.generate()
        let canonical = RecoveryCode.normalize(code)

        XCTAssertEqual(canonical, RecoveryCode.normalize(code.lowercased()))
        XCTAssertEqual(canonical, RecoveryCode.normalize(code.replacingOccurrences(of: "-", with: "")))
        XCTAssertEqual(canonical, RecoveryCode.normalize(code.replacingOccurrences(of: "-", with: " ")))
        XCTAssertEqual(canonical, RecoveryCode.normalize("  \(code)  "))
    }

    /// The same two vectors `RecoveryCodeTest.kt` pins.
    func testTheCharactersPeopleConfuseAreFoldedOntoWhatTheyMeant() {
        XCTAssertEqual("101", RecoveryCode.normalize("IO1"))
        XCTAssertEqual("101", RecoveryCode.normalize("LOl"))
    }

    func testAnIncompleteOrOverlongCodeIsRejectedBeforeHashing() {
        XCTAssertFalse(RecoveryCode.isWellFormed(""))
        XCTAssertFalse(RecoveryCode.isWellFormed("ABCDE-FGHJK"))
        XCTAssertFalse(RecoveryCode.isWellFormed(RecoveryCode.generate() + "X"))
        // 'U' is dropped rather than mapped, so a code containing one comes out
        // short instead of silently verifying as something else.
        XCTAssertFalse(RecoveryCode.isWellFormed(String(RecoveryCode.generate().dropLast()) + "U"))
    }

    func testAStoredCodeCannotBeReadBackOutOfItsHash() {
        let code = RecoveryCode.generate()
        let record = PinHasher.hash(RecoveryCode.normalize(code), iterations: 1_000)

        XCTAssertTrue(PinHasher.verify(RecoveryCode.normalize(code), record))
        XCTAssertFalse(PinHasher.verify(RecoveryCode.normalize(RecoveryCode.generate()), record))
        XCTAssertNotEqual(RecoveryCode.normalize(code), record.hash)
    }
}
