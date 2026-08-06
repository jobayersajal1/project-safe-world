import Foundation

/// The advisory model's feature map and scoring — the Swift half of a spec that
/// exists four times.
///
/// `packages/core/src/advisory.ts` is the definition; this, `DomainModel.kt` and
/// `DomainModel.cs` reimplement it by hand, and all four pin the same vectors in
/// their tests the way `Scramble` and `DomainHasher` already do. Four platforms
/// disagreeing about the same domain is the failure that discipline exists to
/// prevent, so **nothing here may change without changing all of them.**
///
/// What it is for: the blocklists are exact-membership sets, so a gambling site
/// registered this morning is not in them and stays reachable until someone
/// upstream adds it, we re-fetch, and a delta arrives. This scores the hostname
/// itself and guesses. It never blocks — `Matcher.decide` remains the whole
/// decision — and the strongest thing it can say is "worth warning about".
///
/// **Nothing on Apple platforms is wired to it yet, and that is not an
/// oversight.** A warn tier needs somewhere to put "probably, but have a look",
/// and a Safari content-blocker rule list has no interstitial: it can only say
/// yes or no, before the page exists. Chrome has the blocked page and ships the
/// feature; here the arithmetic is ported and tested so that when a surface does
/// exist it is already known to agree with the others, rather than being written
/// under deadline against a model nobody re-verified.
public enum DomainModel {

    // MARK: - Shape

    public static let hashBits = 18
    public static let tableSize = 1 << hashBits
    static let tableMask = UInt32(tableSize - 1)
    static let ngrams = [3, 4, 5]

    static let fnvOffset: UInt32 = 0x811C_9DC5
    static let fnvPrime: UInt32 = 0x0100_0193

    // MARK: - Normalisation

    /// Matches `normalizeHost` in `Matcher.swift` plus stripping a leading
    /// `www.`, which appears on blocked and allowed names alike and would
    /// otherwise be learned as a signal.
    public static func normalize(_ host: String) -> String {
        var s = host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        while s.hasSuffix(".") { s.removeLast() }
        if s.hasPrefix("www.") { s.removeFirst(4) }
        return s
    }

    // MARK: - Hashing

    static func fnv1a(_ bytes: ArraySlice<UInt8>) -> UInt32 {
        var h = fnvOffset
        for b in bytes {
            h = (h ^ UInt32(b)) &* fnvPrime
        }
        return h
    }

    private static func bucket(_ value: Int, _ edges: [Int]) -> Int {
        for (i, e) in edges.enumerated() where value <= e { return i }
        return edges.count
    }

    /// Whole-name properties no n-gram window can express.
    ///
    /// A 5-gram sees `.xyz` only together with whatever precedes it, so a cheap
    /// TLD costs thousands of separate weights to learn. Same for "forty
    /// characters of digits and hyphens over two labels". Hashed into the same
    /// table as the n-grams, so they cost nothing in format or porting effort.
    static func structuralTokens(_ host: String) -> [String] {
        let labels = host.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        var digits = 0
        var hyphens = 0
        for c in host {
            if c.isNumber && c.isASCII { digits += 1 }
            else if c == "-" { hyphens += 1 }
        }
        let longest = labels.map(\.count).max() ?? 0
        let length = host.utf8.count

        // The \u{1} prefix namespaces these away from the n-grams. It was a
        // literal control byte in the TypeScript source once, which worked and
        // was invisible to anyone reading or reviewing it; every port now
        // writes the escape.
        return [
            "\u{1}tld=" + (labels.count > 1 ? labels[labels.count - 1] : ""),
            "\u{1}sld=" + (labels.count > 1 ? labels[labels.count - 2] : ""),
            "\u{1}labels=\(min(labels.count, 6))",
            "\u{1}len=\(bucket(length, [8, 12, 16, 20, 26, 34, 48]))",
            "\u{1}digits=\(bucket(100 * digits / max(1, length), [0, 5, 15, 30, 50]))",
            "\u{1}hyphens=\(bucket(hyphens, [0, 1, 2, 4]))",
            "\u{1}longest=\(bucket(longest, [4, 7, 10, 14, 20, 30]))",
        ]
    }

    /// The L2-normalised sparse feature vector, as index -> value.
    ///
    /// Normalisation is not cosmetic: hostnames differ in length by an order of
    /// magnitude, and an unnormalised count vector scores a long name highly for
    /// being long — precisely the false positive this must not make.
    ///
    /// The sign comes from bit 31 of the same hash, so two n-grams colliding in
    /// the table cancel as often as they reinforce and a collision costs noise
    /// rather than a lean toward "blocked".
    public static func features(_ host: String) -> [Int: Double] {
        let clean = normalize(host)
        if clean.isEmpty { return [:] }

        var acc: [Int: Double] = [:]
        func add(_ h: UInt32) {
            let idx = Int(h & tableMask)
            let sign: Double = (h >> 31) & 1 == 1 ? -1 : 1
            acc[idx, default: 0] += sign
        }

        let bytes = Array(("^" + clean + "$").utf8)
        for n in ngrams where bytes.count >= n {
            for i in 0...(bytes.count - n) {
                add(fnv1a(bytes[i..<(i + n)]))
            }
        }
        for token in structuralTokens(clean) {
            let t = Array(token.utf8)
            add(fnv1a(t[0..<t.count]))
        }

        var sum = 0.0
        for v in acc.values { sum += v * v }
        if sum == 0 { return [:] }
        let inv = 1.0 / sum.squareRoot()
        for (k, v) in acc { acc[k] = v * inv }
        return acc
    }

    // MARK: - Scoring

    /// `threshold` is where warning is defensible; `blockThreshold` is where
    /// blocking outright is. Different claims, not degrees of one — only the
    /// second may take a site away without asking, so it is held far stricter
    /// than the hand review requires. Gambling gives up 24% recall for 8% to
    /// earn it.
    public struct Weights {
        public let category: CategoryId
        public let scale: Double
        public let bias: Double
        public let threshold: Double
        public let blockThreshold: Double
        public let values: [Int8]

        public init(
            category: CategoryId,
            scale: Double,
            bias: Double,
            threshold: Double,
            blockThreshold: Double = .infinity,
            values: [Int8]
        ) {
            self.category = category
            self.scale = scale
            self.bias = bias
            self.threshold = threshold
            self.blockThreshold = blockThreshold
            self.values = values
        }
    }

    /// What the model is willing to claim about a host. `warn` and `block` are
    /// different claims, not degrees of the same one.
    public enum Action: Sendable { case warn, block }

    public struct Verdict: Sendable {
        public let action: Action
        public let category: CategoryId
        public let score: Double
    }

    public static func score(_ weights: Weights, _ host: String) -> Double {
        var sum = 0.0
        for (idx, value) in features(host) where idx < weights.values.count {
            sum += Double(weights.values[idx]) * value
        }
        return sum * weights.scale + weights.bias
    }

    /// Hosts on a shared publishing platform are never scored.
    ///
    /// Not a nicety — the failure that showed up in measurement. Adult Blogger
    /// blogs are heavily represented in the adult list, so the model learned
    /// that `*.blogspot.com` is adult and then flagged Blogger's own image CDN,
    /// `1.bp.blogspot.com` and `2.` and `3.`, which serve the pictures on every
    /// Blogger blog there is. A subdomain on shared hosting says something about
    /// that one blog and nothing about the platform, and the string cannot tell
    /// them apart, so we decline to guess.
    static let sharedPlatforms = [
        "blogspot.com", "bp.blogspot.com", "wordpress.com", "tumblr.com", "medium.com",
        "github.io", "gitlab.io", "gitbook.io", "weebly.com", "weeblysite.com",
        "wixsite.com", "webflow.io", "pages.dev", "vercel.app", "netlify.app",
        "herokuapp.com", "duckdns.org", "000webhostapp.com", "blogger.com",
        "substack.com", "notion.site", "myshopify.com", "translate.goog",
        "googleusercontent.com", "cloudfront.net", "akamaized.net", "amazonaws.com",
    ]

    public static func isSharedPlatformHost(_ host: String) -> Bool {
        let clean = normalize(host)
        for platform in sharedPlatforms {
            if clean == platform || clean.hasSuffix("." + platform) { return true }
        }
        return false
    }

    /// The second stage. Call only for a host `Matcher.decide` returned allowed.
    ///
    /// `allowBlocking` gates the stricter tier. A surface with no way to offer
    /// "continue anyway" — a DNS reply, a content-blocker rule — must pass true
    /// and accept the much lower recall that buys.
    public static func advise(
        _ host: String,
        models: [Weights],
        allowBlocking: Bool = false
    ) -> Verdict? {
        let clean = normalize(host)
        if clean.isEmpty || isSharedPlatformHost(clean) { return nil }
        for model in models {
            let s = score(model, clean)
            if allowBlocking && s >= model.blockThreshold {
                return Verdict(action: .block, category: model.category, score: s)
            }
            if s >= model.threshold {
                return Verdict(action: .warn, category: model.category, score: s)
            }
        }
        return nil
    }
}
