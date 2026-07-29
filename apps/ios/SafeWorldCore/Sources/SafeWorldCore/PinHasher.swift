import Foundation
import CommonCrypto

/// Salted PBKDF2 hashing for the user's PIN.
///
/// The PIN exists to add friction to the user's own future self (this is a
/// self-control app), not to defend against an attacker with the device — anyone
/// willing to delete the app is rid of it entirely, and on iOS that is one long
/// press. It is still stored hashed and salted rather than in plaintext, because
/// a 4-6 digit PIN is very likely reused elsewhere.
///
/// Ported from `apps/android/app/src/main/kotlin/com/safeworld/app/security/PinHasher.kt`
/// and deliberately kept parameter-for-parameter identical, so the friction is
/// the same on both platforms. The *hashes* are not interchangeable and never
/// travel between devices, so no shared test vector is pinned here — unlike
/// `Scramble` and `DomainHasher`, where the bytes really do cross platforms.
///
/// Lives in `SafeWorldCore` rather than the app target for the same reason the
/// Kotlin one is in a plain JVM module: `swift test` covers it with no simulator
/// and no Xcode.
public enum PinHasher {

    /// Chosen so a single verify is imperceptible (~100ms on a modern phone)
    /// while brute-forcing the whole 4-digit space offline still costs real
    /// time. Stored alongside each hash so this can be raised later without
    /// invalidating existing PINs.
    public static let defaultIterations = 120_000

    private static let keyLengthBytes = 32
    private static let saltBytes = 16

    /// A stored PIN record. Salt and hash are Base64; nothing here is
    /// secret-by-obscurity.
    public struct Record: Equatable, Codable, Sendable {
        public let salt: String
        public let hash: String
        public let iterations: Int

        public init(salt: String, hash: String, iterations: Int) {
            self.salt = salt
            self.hash = hash
            self.iterations = iterations
        }
    }

    public static func hash(_ pin: String, iterations: Int = defaultIterations) -> Record {
        var salt = [UInt8](repeating: 0, count: saltBytes)
        // Fall back to `SystemRandomNumberGenerator` only if the CSPRNG refuses,
        // which it does not in practice; never to a fixed salt.
        if SecRandomCopyBytes(kSecRandomDefault, salt.count, &salt) != errSecSuccess {
            salt = (0..<saltBytes).map { _ in UInt8.random(in: .min ... .max) }
        }
        return Record(
            salt: Data(salt).base64EncodedString(),
            hash: Data(derive(pin, salt: salt, iterations: iterations)).base64EncodedString(),
            iterations: iterations
        )
    }

    /// True if `pin` matches `record`. Comparison is constant-time.
    public static func verify(_ pin: String, _ record: Record) -> Bool {
        guard
            let salt = Data(base64Encoded: record.salt),
            let expected = Data(base64Encoded: record.hash)
        else { return false }

        let actual = derive(pin, salt: [UInt8](salt), iterations: record.iterations)
        guard actual.count == expected.count else { return false }

        // Accumulate every difference rather than returning early, so the time
        // taken doesn't reveal how many leading bytes were right.
        var difference: UInt8 = 0
        for (a, b) in zip(actual, expected) { difference |= a ^ b }
        return difference == 0
    }

    private static func derive(_ pin: String, salt: [UInt8], iterations: Int) -> [UInt8] {
        var derived = [UInt8](repeating: 0, count: keyLengthBytes)
        let pinBytes = [UInt8](pin.utf8)

        // `CCKeyDerivationPBKDF` wants the password as bytes reinterpreted as
        // Int8; passing the String directly would go through a temporary the
        // caller can't clear.
        _ = pinBytes.withUnsafeBufferPointer { password in
            password.baseAddress!.withMemoryRebound(to: Int8.self, capacity: password.count) { chars in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    chars, password.count,
                    salt, salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    UInt32(iterations),
                    &derived, derived.count
                )
            }
        }
        return derived
    }
}
