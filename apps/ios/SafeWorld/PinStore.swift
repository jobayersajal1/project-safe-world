import Foundation
import Security
import SafeWorldCore

/// Owns the PIN and the recovery code: storage, the attempt limit, and the two
/// published flags the UI branches on.
///
/// The iOS counterpart of the PIN half of `SettingsStore.kt`. The policy itself
/// lives in `SafeWorldCore` (`PinHasher`, `PinLockout`, `RecoveryCode`) and is
/// unit-tested there; this file is only storage and sequencing.
///
/// **The records live in the Keychain, not in the App Group `UserDefaults`.**
/// Android backs its PIN with uninstall protection through a device-admin
/// receiver; iOS has no equivalent outside MDM, so deleting the app is always
/// available and always removes the blocking. What the Keychain buys is the
/// weaker but real case in between: *reinstalling* to shed the PIN while keeping
/// the app. `kSecAttrAccessibleAfterFirstUnlock` (no `ThisDeviceOnly`) survives
/// deletion, so that route ends at the same PIN prompt. Someone who has genuinely
/// lost both PIN and recovery code is never trapped — deleting the app still
/// works, it just also stops the protection, which is the honest trade.
///
/// The lockout counter deliberately stays in `UserDefaults`: it is not a secret,
/// it is written on every wrong entry, and a reinstall clearing it is fine when
/// the PIN it guards survives.
@MainActor
final class PinStore: ObservableObject {

    @Published private(set) var hasPin: Bool
    @Published private(set) var hasRecoveryCode: Bool
    @Published private(set) var lockout: PinLockout.State

    private let defaults: UserDefaults

    private static let pinAccount = "pin"
    private static let recoveryAccount = "recoveryCode"
    private static let failuresKey = "pinFailures"
    private static let lockedUntilKey = "pinLockedUntil"
    private static let lockedAtKey = "pinLockedAt"

    init() {
        let defaults = UserDefaults(suiteName: AppGroup.identifier) ?? .standard
        self.defaults = defaults
        self.hasPin = Self.load(account: Self.pinAccount) != nil
        self.hasRecoveryCode = Self.load(account: Self.recoveryAccount) != nil

        let stored = PinLockout.State(
            failures: defaults.integer(forKey: Self.failuresKey),
            lockedUntil: Int64(defaults.double(forKey: Self.lockedUntilKey)),
            lockedAt: Int64(defaults.double(forKey: Self.lockedAtKey))
        )
        self.lockout = PinLockout.expireIfElapsed(stored, now: Self.now())
    }

    private static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    // MARK: PIN

    func setPin(_ pin: String) {
        Self.save(PinHasher.hash(pin), account: Self.pinAccount)
        hasPin = true
        // Clearing here means a PIN set right after a cooldown works
        // immediately, rather than the user having to wait it out anyway.
        persist(PinLockout.onSuccess())
    }

    /// Checks `pin` and applies the attempt limit. The only PIN entry point, so
    /// the limit cannot be skipped by verifying somewhere else.
    func verifyPin(_ pin: String) -> PinAttempt {
        let now = Self.now()

        let current = PinLockout.expireIfElapsed(lockout, now: now)
        if current != lockout { persist(current) }

        let remaining = PinLockout.remaining(current, now: now)
        if remaining > 0 { return .lockedOut(millisecondsRemaining: remaining) }

        if let record = Self.load(account: Self.pinAccount), PinHasher.verify(pin, record) {
            persist(PinLockout.onSuccess())
            return .success
        }

        let next = PinLockout.onFailure(current, now: now)
        persist(next)
        let lockedFor = PinLockout.remaining(next, now: now)
        return lockedFor > 0
            ? .lockedOut(millisecondsRemaining: lockedFor)
            : .wrong(attemptsRemaining: PinLockout.attemptsRemaining(next))
    }

    /// Milliseconds left on the cooldown right now, for a UI that counts down.
    func lockoutRemaining() -> Int64 {
        PinLockout.remaining(lockout, now: Self.now())
    }

    private func persist(_ state: PinLockout.State) {
        defaults.set(state.failures, forKey: Self.failuresKey)
        defaults.set(Double(state.lockedUntil), forKey: Self.lockedUntilKey)
        defaults.set(Double(state.lockedAt), forKey: Self.lockedAtKey)
        lockout = state
    }

    // MARK: Recovery code

    /// Issues a fresh code, stores it hashed, and returns it **once**.
    ///
    /// Hashed like the PIN, so it can never be shown again — only replaced. That
    /// is the point: a code the app could redisplay would be no better than no
    /// code at all for anyone who lost the device it's on.
    func issueRecoveryCode() -> String {
        let code = RecoveryCode.generate()
        Self.save(PinHasher.hash(RecoveryCode.normalize(code)), account: Self.recoveryAccount)
        hasRecoveryCode = true
        return code
    }

    /// True if `code` is the current recovery code.
    ///
    /// Deliberately **not** subject to `PinLockout`: the cooldown exists to slow
    /// down guessing a 4-digit PIN, and being stuck in one is the usual reason to
    /// reach for this. At 100 bits it does not need an attempt limit to stay
    /// unguessable.
    func verifyRecoveryCode(_ code: String) -> Bool {
        guard let record = Self.load(account: Self.recoveryAccount) else { return false }
        return PinHasher.verify(RecoveryCode.normalize(code), record)
    }

    /// Sets a new PIN if `code` is the current recovery code. Returns false and
    /// changes nothing otherwise.
    func resetPin(withRecoveryCode code: String, newPin: String) -> Bool {
        guard verifyRecoveryCode(code) else { return false }
        // setPin also clears the lockout, so the new PIN works immediately.
        setPin(newPin)
        return true
    }

    // MARK: Keychain

    private static func query(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: AppGroup.identifier,
            kSecAttrAccount as String: account,
        ]
    }

    private static func save(_ record: PinHasher.Record, account: String) {
        guard let data = try? JSONEncoder().encode(record) else { return }
        // Delete-then-add rather than SecItemUpdate: one path instead of two,
        // and this runs about twice in the app's lifetime.
        SecItemDelete(query(account: account) as CFDictionary)

        var attributes = query(account: account)
        attributes[kSecValueData as String] = data
        // Not `ThisDeviceOnly`, so a restored backup keeps the PIN. Not
        // `WhenUnlocked` either — the tunnel extension may need to read this
        // while the device is locked.
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(attributes as CFDictionary, nil)
    }

    private static func load(account: String) -> PinHasher.Record? {
        var attributes = query(account: account)
        attributes[kSecReturnData as String] = true
        attributes[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        guard
            SecItemCopyMatching(attributes as CFDictionary, &result) == errSecSuccess,
            let data = result as? Data
        else { return nil }
        return try? JSONDecoder().decode(PinHasher.Record.self, from: data)
    }
}
