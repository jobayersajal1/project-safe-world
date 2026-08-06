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

    /// The advisory models, and whether the user asked for them. Separate from
    /// <c>_settings</c> for the reason <see cref="AdvisorySettings"/> is a
    /// separate stored value.
    private readonly List<DomainModel.Weights> _models;
    private AdvisorySettings _advisory = new();
    private readonly Dictionary<string, bool> _advisoryCache = new();
    private const int AdvisoryCacheLimit = 4096;

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

        // Absent on a build that never ran `npm run build:model`, which must
        // behave exactly like a build from before the feature existed.
        _models = DomainModel.Load(directory);
    }

    /// <summary>Re-read settings without reopening the filters, which are immutable.</summary>
    public void Update(Settings settings)
    {
        _settings = settings;
        _advisoryCache.Clear();
    }

    /// <summary>Whether the advisory model may block, and for which categories.</summary>
    /// <remarks>
    /// Kept out of <see cref="IsBlocked"/> on purpose: that method mirrors
    /// <see cref="Matcher.Decide"/> exactly and the tests assert the two agree
    /// over a corpus. Folding a <i>guess</i> into it would make that equivalence
    /// false and quietly weaken the thing it protects.
    /// </remarks>
    public void UpdateAdvisory(AdvisorySettings advisory)
    {
        _advisory = advisory;
        _advisoryCache.Clear();
    }

    /// <summary>The model's second opinion on a name no list covers.</summary>
    /// <remarks>
    /// <b>Only ever the strict tier here.</b> A DNS reply is yes or no, with
    /// nowhere to put the "continue anyway" Chrome offers — so the resolver
    /// takes only the part of the ranking where a hand review of 1.79M held-out
    /// names found nothing near the boundary, and gives up most of the recall
    /// for it. Blocking a site with no way through is a far worse failure here
    /// than letting one past.
    /// Cached per host: scoring is orders of magnitude dearer than a filter
    /// lookup, and a name's verdict cannot change between queries unless the
    /// settings do.
    /// </remarks>
    public bool AdvisoryBlocks(string host)
    {
        if (!_advisory.Enabled || _models.Count == 0) return false;
        var h = Matcher.NormalizeHost(host);
        if (h.Length == 0) return false;

        if (_advisoryCache.TryGetValue(h, out var hit)) return hit;

        var enabled = _models.Where(m => _advisory.EnabledFor(m.Category)).ToList();
        var verdict = DomainModel.Advise(h, enabled, allowBlocking: true);
        var blocked = verdict?.Action == DomainModel.Action.Block;

        // Cleared wholesale rather than evicted one at a time: this is a hot
        // path, the working set of names a machine resolves is small, and a rare
        // cold rebuild costs one score per name.
        if (_advisoryCache.Count >= AdvisoryCacheLimit) _advisoryCache.Clear();
        _advisoryCache[h] = blocked;
        return blocked;
    }

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
