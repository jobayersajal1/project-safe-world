using System.Text.RegularExpressions;

namespace SafeWorld.Core;

/// <summary>
/// Mirrors <c>packages/core/src/matcher.ts</c>. This is the tested spec of blocking behavior —
/// the hosts-file sinkhole must stay consistent with it.
/// </summary>
public static partial class Matcher
{
    [GeneratedRegex(@"^[a-z][a-z0-9+.-]*://")]
    private static partial Regex SchemeRegex();

    /// <summary>
    /// Normalize a hostname or URL into a bare, lowercased registrable-ish host: strips scheme,
    /// path, port, userinfo, a leading "www.", and trailing dot. Returns "" for input that has no
    /// host.
    /// </summary>
    public static string NormalizeHost(string input)
    {
        var s = input.Trim().ToLowerInvariant();
        if (s.Length == 0) return "";

        s = SchemeRegex().Replace(s, "");

        var at = s.IndexOf('@');
        if (at != -1) s = s[(at + 1)..];

        var cut = s.IndexOfAny(new[] { '/', '?', '#' });
        if (cut != -1) s = s[..cut];

        var colon = s.IndexOf(':');
        if (colon != -1) s = s[..colon];

        if (s.EndsWith('.')) s = s[..^1];
        if (s.StartsWith("www.")) s = s[4..];
        return s;
    }

    /// <summary>True if <paramref name="host"/> equals <paramref name="domain"/> or is a subdomain of it. Both are expected to be normalized.</summary>
    public static bool HostMatchesDomain(string host, string domain)
    {
        if (host == domain) return true;
        return host.EndsWith("." + domain, StringComparison.Ordinal);
    }

    /// <summary>True if <paramref name="host"/> matches any domain in the list (exact or subdomain).</summary>
    public static bool HostInList(string host, IEnumerable<string> domains)
    {
        foreach (var d in domains)
        {
            if (!string.IsNullOrEmpty(d) && HostMatchesDomain(host, d)) return true;
        }
        return false;
    }

    public readonly record struct BlockDecision(bool Blocked, string? Reason);

    /// <summary>
    /// Decide whether a host should be blocked given the user's settings and the per-category
    /// blocklists. Precedence: master off -&gt; allow; custom allow -&gt; allow; custom block -&gt;
    /// block; otherwise first enabled category that lists it.
    /// </summary>
    public static BlockDecision Decide(string host, Settings settings, IReadOnlyDictionary<CategoryId, HashSet<string>> blocklists)
    {
        var h = NormalizeHost(host);
        if (h.Length == 0 || !settings.Enabled) return new BlockDecision(false, null);

        if (HostInList(h, settings.CustomAllow.Select(NormalizeHost)))
        {
            return new BlockDecision(false, null);
        }
        if (HostInList(h, settings.CustomBlock.Select(NormalizeHost)))
        {
            return new BlockDecision(true, "custom");
        }

        foreach (var c in Categories.All)
        {
            if (!settings.Categories.TryGetValue(c.Id, out var on) || !on) continue;
            if (blocklists.TryGetValue(c.Id, out var domains) && HostInList(h, domains))
            {
                return new BlockDecision(true, c.Id.ToRawValue());
            }
        }
        return new BlockDecision(false, null);
    }
}
