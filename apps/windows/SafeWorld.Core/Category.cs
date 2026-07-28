namespace SafeWorld.Core;

/// <summary>
/// Mirrors <c>packages/core/src/categories.ts</c>. Keep in sync with that file — it is the source
/// of truth for category ids and labels across platforms. Raw values are deliberately opaque —
/// they are the keys in the published payload and the bundled resource file names, both of which
/// are visible in an unpacked app, and "adult" would announce a list's contents even though its
/// domains are encoded. The case names stay meaningful so the code reads normally, and
/// <see cref="CategoryMeta.Label"/> is what the UI shows.
/// </summary>
public enum CategoryId
{
    Scam,
    Gambling,
    Adult,
    Social,
    Entertainment,
}

public static class CategoryIdExtensions
{
    /// <summary>The opaque wire/resource-file id, e.g. "list1".</summary>
    public static string ToRawValue(this CategoryId id) => id switch
    {
        CategoryId.Scam => "list1",
        CategoryId.Gambling => "list2",
        CategoryId.Adult => "list3",
        CategoryId.Social => "list4",
        CategoryId.Entertainment => "list5",
        _ => throw new ArgumentOutOfRangeException(nameof(id)),
    };

    public static CategoryId? FromRawValue(string raw) => raw switch
    {
        "list1" => CategoryId.Scam,
        "list2" => CategoryId.Gambling,
        "list3" => CategoryId.Adult,
        "list4" => CategoryId.Social,
        "list5" => CategoryId.Entertainment,
        _ => null,
    };
}

public sealed record CategoryMeta(
    CategoryId Id,
    /// <summary>Short human label for UI.</summary>
    string Label,
    /// <summary>One-line description shown in settings.</summary>
    string Description,
    /// <summary>Whether the category is enabled by default on first install.</summary>
    bool DefaultEnabled,
    /// <summary>
    /// Whether the user may turn this category off. The three protection categories are the
    /// reason the app exists; the opt-in ones are lifestyle choices, off until asked for. See
    /// categories.ts for the full note.
    /// </summary>
    bool Optional
);

public static class Categories
{
    public static readonly IReadOnlyList<CategoryMeta> All = new List<CategoryMeta>
    {
        new(CategoryId.Scam, "Scam & Malware", "Phishing, fraud, and malware-hosting sites.", true, false),
        new(CategoryId.Gambling, "Gambling", "Casinos, betting, and gambling sites.", true, false),
        new(CategoryId.Adult, "Adult Content", "Pornography and explicit adult sites.", true, false),
        new(CategoryId.Social, "Social Media", "Facebook, Instagram, X/Twitter, TikTok, and similar feeds.", false, true),
        new(CategoryId.Entertainment, "Entertainment", "YouTube, Netflix, and other streaming and video sites.", false, true),
    };

    /// <summary>Categories the user opts into, presented as a question rather than as part of the app's baseline promise.</summary>
    public static readonly IReadOnlyList<CategoryMeta> Optional = All.Where(c => c.Optional).ToList();

    public static CategoryMeta Meta(CategoryId id) => All.First(c => c.Id == id);
}
