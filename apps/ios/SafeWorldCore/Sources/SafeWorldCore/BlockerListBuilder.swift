import Foundation

/// A Safari Content Blocker rule, per Apple's content-rule-list JSON format.
/// See https://developer.apple.com/documentation/safariservices/creating-a-content-blocker
public struct ContentBlockerRule: Codable, Equatable {
    public struct Trigger: Codable, Equatable {
        public var urlFilter: String
        public var ifDomain: [String]?

        enum CodingKeys: String, CodingKey {
            case urlFilter = "url-filter"
            case ifDomain = "if-domain"
        }
    }

    public struct Action: Codable, Equatable {
        public var type: String
    }

    public var trigger: Trigger
    public var action: Action
}

/// Builds the Safari Content Blocker rule list from the user's settings and the
/// per-category blocklists, mirroring the precedence in `Matcher.decide` and the
/// declarativeNetRequest rule generation in `packages/core/src/rules.ts`.
///
/// Safari only loads a single active rule list per content blocker, so instead of
/// toggling several static rulesets (as the Chrome extension does), this emits one
/// combined list: a `block` trigger per enabled category, then the user's custom
/// block list, then an `ignore-previous-rules` trigger for the custom allow list
/// last so it always wins — matching `decide()`'s "custom allow beats everything"
/// precedence regardless of rule order elsewhere in the list.
public enum BlockerListBuilder {
    /// `if-domain` uses a `*` prefix to match a domain and all of its subdomains,
    /// which is the same match semantics as `Matcher.hostMatchesDomain`.
    private static func ifDomainPatterns(_ domains: [String]) -> [String] {
        domains.map { "*" + $0 }
    }

    public static func build(
        settings: Settings,
        blocklists: [CategoryId: [String]],
        remoteDomains: [CategoryId: [String]] = [:]
    ) -> [ContentBlockerRule] {
        guard settings.enabled else { return [] }

        var rules: [ContentBlockerRule] = []

        for c in Categories.all {
            guard settings.categories[c.id] == true else { continue }
            let domains = Set(blocklists[c.id] ?? []).union(remoteDomains[c.id] ?? []).sorted()
            guard !domains.isEmpty else { continue }
            rules.append(
                ContentBlockerRule(
                    trigger: .init(urlFilter: ".*", ifDomain: ifDomainPatterns(domains)),
                    action: .init(type: "block")
                )
            )
        }

        let customBlock = settings.customBlock.map(Matcher.normalizeHost).filter { !$0.isEmpty }
        if !customBlock.isEmpty {
            rules.append(
                ContentBlockerRule(
                    trigger: .init(urlFilter: ".*", ifDomain: ifDomainPatterns(customBlock)),
                    action: .init(type: "block")
                )
            )
        }

        let customAllow = settings.customAllow.map(Matcher.normalizeHost).filter { !$0.isEmpty }
        if !customAllow.isEmpty {
            rules.append(
                ContentBlockerRule(
                    trigger: .init(urlFilter: ".*", ifDomain: ifDomainPatterns(customAllow)),
                    action: .init(type: "ignore-previous-rules")
                )
            )
        }

        return rules
    }

    /// Convenience overload that pulls domains from the bundled blocklists.
    public static func build(
        settings: Settings,
        remoteDomains: [CategoryId: [String]] = [:]
    ) -> [ContentBlockerRule] {
        var blocklists: [CategoryId: [String]] = [:]
        for id in CategoryId.allCases { blocklists[id] = Blocklists.domains(for: id) }
        return build(settings: settings, blocklists: blocklists, remoteDomains: remoteDomains)
    }

    public static func encode(_ rules: [ContentBlockerRule]) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(rules)
    }
}
