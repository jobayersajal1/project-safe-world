import Foundation

/// Thrown when a fetched payload is encoded in a way this app can't use.
public enum RemoteUpdateFormatError: LocalizedError {
    case hashedListForAndroid
    case unrecognized(String)

    public var errorDescription: String? {
        switch self {
        case .hashedListForAndroid:
            return "That list is hashed for Android and can't be used here — "
                + "point this app at the scrambled list instead."
        case .unrecognized(let format):
            return "Unrecognized list format \"\(format)\"."
        }
    }
}

/// Mirrors `packages/core/src/types.ts` (`RemoteUpdatePayload`). Domains are
/// keyed by the category's raw string id (e.g. "gambling") so the JSON shape
/// matches what the JS side of the project produces/consumes.
public struct RemoteUpdatePayload: Codable, Equatable {
    public var version: Int
    /// Identifies this exact set of domains, so a client can skip work it has already done. Absent on payloads published before the field existed.
    public var updateId: String?
    /// How `domains` is encoded — see `Scramble`. Absent means plaintext, so
    /// payloads published before this field keep working.
    public var format: String?
    public var domains: [String: [String]]

    public init(
        version: Int,
        updateId: String? = nil,
        format: String? = nil,
        domains: [String: [String]]
    ) {
        self.version = version
        self.updateId = updateId
        self.format = format
        self.domains = domains
    }

    /// `domains` re-keyed by `CategoryId` and decoded to plaintext, dropping any
    /// unrecognized category keys.
    ///
    /// Throws rather than returning an empty result for a format this app can't
    /// use: silently yielding nothing would leave the app looking healthy while
    /// blocking nothing new, which is the worst way for this to fail.
    public func domainsByCategory() throws -> [CategoryId: [String]] {
        if format == Scramble.hashedFormat { throw RemoteUpdateFormatError.hashedListForAndroid }
        if let format, format != Scramble.format {
            throw RemoteUpdateFormatError.unrecognized(format)
        }

        var result: [CategoryId: [String]] = [:]
        for (key, value) in domains {
            guard let id = CategoryId(rawValue: key) else { continue }
            result[id] = format == Scramble.format
                ? value.map(Scramble.unscramble).filter { !$0.isEmpty }
                : value
        }
        return result
    }
}
