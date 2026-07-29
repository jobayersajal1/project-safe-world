using System.Security.Cryptography;
using System.Text;

namespace SafeWorld.Core;

/// <summary>
/// Salted digests of blocklist domains, mirroring <c>DomainHasher.kt</c> and
/// <c>DomainHasher.swift</c>.
///
/// Used only by the DNS proxy, which matches against a <see cref="FuseFilter"/>. The hosts-file
/// path takes a different route entirely — it needs literal domains, so it reads the scrambled
/// lists via <see cref="Blocklists"/>.
///
/// Must stay byte-identical to the other ports and to the generator that builds the filters both
/// read; <c>FuseFilterTests</c> pins the shared vector.
/// </summary>
public static class DomainHasher
{
    /// <summary>
    /// Prefixed before the domain so digests can't be looked up in a generic precomputed SHA-256
    /// table. Ships in the app; not a secret.
    /// </summary>
    public const string Salt = "safe-world:v1:";

    /// <summary>
    /// The first 8 bytes of the salted digest as a big-endian ulong — the key a
    /// <see cref="FuseFilter"/> stores.
    /// </summary>
    public static ulong Key(string domain)
    {
        var normalized = Matcher.NormalizeHost(domain);
        if (normalized.Length == 0) return 0;

        var digest = SHA256.HashData(Encoding.UTF8.GetBytes(Salt + normalized));
        ulong key = 0;
        for (var i = 0; i < 8; i++) key = (key << 8) | digest[i];
        return key;
    }

    /// <summary>
    /// The host plus every parent domain at a label boundary, stopping before a bare single-label
    /// name.
    ///
    /// Hashing destroys the suffix relationship <see cref="Matcher.HostMatchesDomain"/> relies on,
    /// so subdomain matching is recovered by enumerating candidates.
    ///
    /// The stop matters: no blocklist can contain "com" — the fetch script drops any entry without
    /// a dot — so looking one up can only ever be wrong. Against an exact set that was merely
    /// pointless; against a probabilistic filter a single collision on "com" would block every
    /// .com domain at once.
    /// </summary>
    public static List<string> Candidates(string host)
    {
        var normalized = Matcher.NormalizeHost(host);
        var result = new List<string>();
        if (normalized.Length == 0) return result;

        result.Add(normalized);
        var rest = normalized;
        while (true)
        {
            var dot = rest.IndexOf('.');
            if (dot < 0) break;
            var parent = rest[(dot + 1)..];
            if (parent.Length == 0 || !parent.Contains('.')) break;
            result.Add(parent);
            rest = parent;
        }
        return result;
    }
}
