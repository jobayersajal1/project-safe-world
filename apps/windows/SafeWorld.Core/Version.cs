namespace SafeWorld.Core;

/// <summary>
/// Ordering for the app's own version strings, used to decide whether a published release is
/// actually newer than what's installed.
/// <para>
/// A third port, alongside <c>apps/ios/SafeWorldCore/.../Version.swift</c> and
/// <c>apps/android/core/.../Version.kt</c>. All three pin the same vectors in their tests, because
/// the sides answering differently would mean one platform nagging about an update that isn't one
/// — or worse, staying quiet about one that is.
/// </para>
/// <para>
/// Deliberately tolerant rather than strict semver: the input is a git tag (<c>v0.5.0</c>) on one
/// side and an assembly version on the other, and neither is guaranteed to be well-formed.
/// Anything unparseable reads as 0 rather than throwing, so a malformed tag can't break the update
/// check — it just fails to look newer.
/// </para>
/// </summary>
public static class Version
{
    /// <summary>
    /// Standard three-way compare: negative when <paramref name="a"/> is older than
    /// <paramref name="b"/>, zero when they are the same release, positive when it is newer.
    /// </summary>
    public static int Compare(string a, string b)
    {
        var (coreA, preA) = Split(a);
        var (coreB, preB) = Split(b);

        for (var i = 0; i < Math.Max(coreA.Count, coreB.Count); i++)
        {
            // Missing trailing components are 0, so "1.2" == "1.2.0".
            var lhs = i < coreA.Count ? coreA[i] : 0;
            var rhs = i < coreB.Count ? coreB[i] : 0;
            if (lhs != rhs) return lhs < rhs ? -1 : 1;
        }

        // Same numeric core: a pre-release precedes the release it leads up to, so
        // 1.0.0-beta < 1.0.0. Without this an installed 1.0.0 would be offered 1.0.0-beta as an
        // "update".
        if (preA is null && preB is null) return 0;
        if (preA is null) return 1;
        if (preB is null) return -1;

        // Two pre-releases of the same core compare lexically. A simplification — real semver
        // compares dot-separated identifiers, numeric ones numerically — but this project has
        // never shipped one, and the fallback only ever mis-orders two pre-releases against each
        // other. Ordinal, not culture-aware: this is a wire format, not display text.
        return string.CompareOrdinal(preA, preB) switch
        {
            0 => 0,
            < 0 => -1,
            _ => 1,
        };
    }

    /// <summary>True when <paramref name="candidate"/> is a release worth offering to someone on
    /// <paramref name="current"/>.</summary>
    public static bool IsNewer(string candidate, string current) => Compare(candidate, current) > 0;

    /// <summary>
    /// Splits "v1.2.3-beta.1+build5" into ([1, 2, 3], "beta.1"). Build metadata is dropped:
    /// semver says it takes no part in precedence.
    /// </summary>
    private static (List<int> Core, string? Pre) Split(string raw)
    {
        var s = (raw ?? "").Trim();
        if (s.StartsWith('v') || s.StartsWith('V')) s = s[1..];

        var plus = s.IndexOf('+');
        if (plus >= 0) s = s[..plus];

        string? pre = null;
        var hyphen = s.IndexOf('-');
        if (hyphen >= 0)
        {
            var tail = s[(hyphen + 1)..];
            pre = tail.Length == 0 ? null : tail;
            s = s[..hyphen];
        }

        var core = new List<int>();
        foreach (var part in s.Split('.'))
        {
            // Take the leading digits only, so "3rc" reads as 3 rather than collapsing to 0 and
            // silently losing an ordering the string plainly implies.
            var digits = 0;
            while (digits < part.Length && char.IsAsciiDigit(part[digits])) digits++;
            core.Add(digits > 0 && int.TryParse(part[..digits], out var n) ? n : 0);
        }

        return (core, pre);
    }
}
