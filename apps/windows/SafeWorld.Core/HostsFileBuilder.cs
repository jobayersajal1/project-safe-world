namespace SafeWorld.Core;

/// <summary>
/// Builds the managed block of <c>C:\Windows\System32\drivers\etc\hosts</c> entries for the
/// domains <see cref="Matcher.Decide"/> would block, given the user's settings and blocklists.
///
/// Hosts-file blocking is coarser than <see cref="Matcher.Decide"/>: a hosts file can only
/// sinkhole exact names it lists, not "this domain and every subdomain" the way DNR/Safari
/// content-blocker rules can. To cover the common case, each blocked domain is emitted both bare
/// and with a "www." prefix.
///
/// Every candidate here is already a literal member of an enabled category's list or the
/// custom-block list, so unlike <see cref="Matcher.Decide"/> (built to answer "is this one host
/// blocked?", not "list every blocked host") there's no need to re-derive category membership by
/// scanning each category's full domain set per candidate — that would turn an O(candidates)
/// sweep into O(candidates &#215; total bundled domains), which is prohibitively slow against the
/// real, ~45k-domain bundled lists. Only custom-allow (small, user-entered) needs a membership
/// check per candidate.
/// </summary>
public static class HostsFileBuilder
{
    public const string BeginMarker = "# BEGIN SAFEWORLD";
    public const string EndMarker = "# END SAFEWORLD";
    private const string Sinkhole = "0.0.0.0";

    /// <summary>Sorted, deduplicated list of hostnames (bare + "www." variants) that should resolve to the sinkhole address under <paramref name="settings"/>.</summary>
    public static List<string> BlockedHosts(Settings settings, IReadOnlyDictionary<CategoryId, List<string>> blocklists)
    {
        if (!settings.Enabled) return new List<string>();

        var candidates = new HashSet<string>();
        foreach (var c in Categories.All)
        {
            if (!settings.Categories.TryGetValue(c.Id, out var on) || !on) continue;
            if (blocklists.TryGetValue(c.Id, out var domains)) candidates.UnionWith(domains);
        }
        candidates.UnionWith(settings.CustomBlock);

        var allow = settings.CustomAllow.Select(Matcher.NormalizeHost).Where(d => d.Length > 0).ToList();

        var blocked = new HashSet<string>();
        foreach (var domain in candidates)
        {
            var host = Matcher.NormalizeHost(domain);
            if (host.Length == 0 || Matcher.HostInList(host, allow)) continue;
            blocked.Add(host);
            blocked.Add("www." + host);
        }

        var result = blocked.ToList();
        result.Sort(StringComparer.Ordinal);
        return result;
    }

    /// <summary>Renders the managed block (including begin/end markers) to splice into a hosts file. Empty (marker-only) when nothing is blocked.</summary>
    public static string RenderBlock(Settings settings, IReadOnlyDictionary<CategoryId, List<string>> blocklists)
    {
        var hosts = BlockedHosts(settings, blocklists);
        var lines = new List<string> { BeginMarker };
        foreach (var host in hosts) lines.Add($"{Sinkhole} {host}");
        lines.Add(EndMarker);
        return string.Join("\n", lines) + "\n";
    }

    /// <summary>
    /// Replaces the marker-delimited managed block inside existing hosts-file content, appending
    /// it if no markers are present yet. Preserves everything else in the file untouched.
    /// </summary>
    public static string Apply(string managedBlock, string existingContents)
    {
        var lines = existingContents.Split('\n');
        var beginIndex = Array.FindIndex(lines, l => l.Trim() == BeginMarker);
        var endIndex = Array.FindIndex(lines, l => l.Trim() == EndMarker);

        if (beginIndex == -1 || endIndex == -1 || endIndex < beginIndex)
        {
            var baseContent = existingContents.Length == 0 || existingContents.EndsWith('\n')
                ? existingContents
                : existingContents + "\n";
            return baseContent + managedBlock;
        }

        var trimmedBlock = managedBlock.EndsWith('\n') ? managedBlock[..^1] : managedBlock;

        var result = new List<string>(lines[..beginIndex]);
        result.AddRange(trimmedBlock.Split('\n'));
        result.AddRange(lines[(endIndex + 1)..]);
        return string.Join("\n", result);
    }
}
