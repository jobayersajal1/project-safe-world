namespace SafeWorld.Core;

/// <summary>Mirrors <c>packages/core/src/types.ts</c> (<c>Settings</c>) and <c>settings.ts</c>.</summary>
public sealed class Settings
{
    /// <summary>Master switch. When false, nothing is blocked.</summary>
    public bool Enabled { get; set; } = true;

    /// <summary>Per-category enable flags.</summary>
    public Dictionary<CategoryId, bool> Categories { get; set; } = new();

    /// <summary>Domains the user always allows, even if a category would block them.</summary>
    public List<string> CustomAllow { get; set; } = new();

    /// <summary>Domains the user always blocks, regardless of category.</summary>
    public List<string> CustomBlock { get; set; } = new();

    /// <summary>URL to fetch remote list updates from. Empty string disables remote updates.</summary>
    public string RemoteUpdateUrl { get; set; } = "";

    /// <summary>How often (in hours) to fetch remote updates.</summary>
    public int RemoteUpdateIntervalHours { get; set; } = 24;

    /// <summary>Timestamp (ms since epoch) of the last successful remote update, or 0 if never.</summary>
    public double LastRemoteUpdate { get; set; }

    /// <summary>
    /// Id of the last payload actually applied, so a fetch that returns an unchanged list can
    /// skip rewriting the hosts file. Empty until an update carrying an id has been applied.
    /// </summary>
    public string LastAppliedUpdateId { get; set; } = "";

    public static Settings Defaults()
    {
        var categories = new Dictionary<CategoryId, bool>();
        foreach (var c in Core.Categories.All) categories[c.Id] = c.DefaultEnabled;
        return new Settings { Categories = categories };
    }

    /// <summary>
    /// Merge stored (possibly partial/older) settings over the current defaults, the same
    /// precedence as <c>withDefaults</c> in packages/core/src/settings.ts.
    /// </summary>
    public static Settings WithDefaults(Settings? stored)
    {
        if (stored is null) return Defaults();
        var categories = Defaults().Categories;
        foreach (var (id, on) in stored.Categories) categories[id] = on;
        stored.Categories = categories;
        return stored;
    }
}

/// <summary>Mirrors <c>packages/core/src/types.ts</c> (<c>DailyStats</c>).</summary>
public sealed class DailyStats
{
    /// <summary>Local date key, e.g. "2026-07-26".</summary>
    public string Date { get; set; } = "";

    /// <summary>Number of navigations blocked on that date.</summary>
    public int Blocked { get; set; }
}
