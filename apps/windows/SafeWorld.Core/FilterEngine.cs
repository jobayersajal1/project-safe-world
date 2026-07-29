namespace SafeWorld.Core;

/// <summary>
/// Decides whether a hostname is blocked, using memory-mapped filters rather than in-memory
/// domain sets.
///
/// This is <see cref="Matcher.Decide"/>'s precedence expressed over filters — master off → allow;
/// custom allow → allow; custom block → block; otherwise the first enabled category whose filter
/// matches. <c>Decide</c> takes a dictionary of domain sets, which cannot represent 4.5M domains,
/// so the rules are restated here rather than the data being forced into a shape it doesn't fit.
///
/// Restating rules is how two specs quietly diverge, so <c>FilterEngineTests</c> drives the same
/// inputs through both and asserts they agree. <c>Decide</c> remains the spec.
/// </summary>
public sealed class FilterEngine : IDisposable
{
    private readonly Dictionary<CategoryId, FuseFilter> _filters = new();
    private Settings _settings;

    /// <summary>
    /// Open every category filter in <paramref name="directory"/>.
    ///
    /// Throws when none load. An engine with no filters answers "not blocked" to everything, which
    /// is indistinguishable from a healthy app that happens to be blocking nothing.
    /// </summary>
    public FilterEngine(string directory, Settings settings)
    {
        _settings = settings;
        foreach (var id in Enum.GetValues<CategoryId>())
        {
            var path = Path.Combine(directory, $"{id.ToRawValue()}.filter");
            if (!File.Exists(path)) continue;
            // One bad file shouldn't take the others down with it.
            try { _filters[id] = FuseFilter.Open(path); }
            catch (Exception) { /* skipped; the guard below catches "none loaded" */ }
        }

        if (_filters.Count == 0)
        {
            throw new InvalidOperationException($"no category filters could be loaded from {directory}");
        }
    }

    /// <summary>Re-read settings without reopening the filters, which are immutable.</summary>
    public void Update(Settings settings) => _settings = settings;

    /// <summary>How many domains are covered by the enabled categories.</summary>
    public int BlockedDomainCount =>
        _filters.Where(kv => _settings.Categories.TryGetValue(kv.Key, out var on) && on)
                .Sum(kv => kv.Value.Size);

    /// <summary>True if <paramref name="host"/> should be blocked. Mirrors <see cref="Matcher.Decide"/>.</summary>
    public bool IsBlocked(string host)
    {
        var h = Matcher.NormalizeHost(host);
        if (h.Length == 0 || !_settings.Enabled) return false;

        // The user's own lists are plaintext and always win, in this order. They are small, local,
        // and never published, so there is no reason to put them behind a filter.
        foreach (var allow in _settings.CustomAllow)
        {
            var d = Matcher.NormalizeHost(allow);
            if (d.Length > 0 && Matcher.HostMatchesDomain(h, d)) return false;
        }
        foreach (var block in _settings.CustomBlock)
        {
            var d = Matcher.NormalizeHost(block);
            if (d.Length > 0 && Matcher.HostMatchesDomain(h, d)) return true;
        }

        // Hashing destroys the suffix relationship, so subdomain matching is recovered by testing
        // the host and each parent.
        var keys = DomainHasher.Candidates(h).Select(DomainHasher.Key).ToArray();
        foreach (var category in Categories.All)
        {
            if (!_settings.Categories.TryGetValue(category.Id, out var on) || !on) continue;
            if (!_filters.TryGetValue(category.Id, out var filter)) continue;
            foreach (var key in keys)
            {
                if (filter.Contains(key)) return true;
            }
        }
        return false;
    }

    public void Dispose()
    {
        foreach (var filter in _filters.Values) filter.Dispose();
        _filters.Clear();
    }
}
