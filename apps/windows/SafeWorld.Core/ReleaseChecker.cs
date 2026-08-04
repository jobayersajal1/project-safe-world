using System.Text.Json;
using System.Text.Json.Serialization;

namespace SafeWorld.Core;

/// <summary>A published release newer than what's installed.</summary>
/// <param name="Version">As published, e.g. "0.5.0" — the leading "v" of the tag is already stripped.</param>
/// <param name="AssetUrl">Direct link to this platform's installable asset, or null when the release carries none.</param>
/// <param name="SizeBytes">Bytes, for showing the download size before committing to it. 0 when unknown.</param>
/// <param name="PageUrl">The release's own page — where a user goes when there's nothing to install directly.</param>
public sealed record AvailableUpdate(string Version, string? AssetUrl, long SizeBytes, string PageUrl);

/// <summary>The subset of GitHub's release JSON the update check reads.</summary>
public sealed class GitHubRelease
{
    [JsonPropertyName("tag_name")] public string TagName { get; set; } = "";
    [JsonPropertyName("html_url")] public string HtmlUrl { get; set; } = "";
    [JsonPropertyName("draft")] public bool Draft { get; set; }
    [JsonPropertyName("prerelease")] public bool Prerelease { get; set; }
    [JsonPropertyName("assets")] public List<Asset> Assets { get; set; } = new();

    public sealed class Asset
    {
        [JsonPropertyName("name")] public string Name { get; set; } = "";
        [JsonPropertyName("browser_download_url")] public string BrowserDownloadUrl { get; set; } = "";
        [JsonPropertyName("size")] public long Size { get; set; }
    }
}

/// <summary>
/// Decides whether a published release is worth offering, and which asset this platform would
/// install.
/// <para>
/// Split from the networking so the wire format and the asset matching can be tested against a
/// real captured payload without a network call. Mirrors <c>ReleaseChecker.select</c> (Swift) and
/// <c>UpdateChecker.select</c> (Kotlin).
/// </para>
/// </summary>
public static class ReleaseChecker
{
    /// <summary>
    /// Where every platform looks for a newer build of itself.
    /// <para>
    /// This reads the GitHub Releases API rather than a hand-maintained version file,
    /// deliberately: publishing a release is already the act that makes a build available, so
    /// there is no second thing to remember to bump and nothing that can drift out of step with
    /// the actual download. The tradeoff is the unauthenticated rate limit (60/hour per IP) —
    /// generous for a check that runs at most once a day per install, and a limit hit degrades to
    /// "no update found", never to a wrong answer.
    /// </para>
    /// </summary>
    public const string LatestReleaseUrl =
        "https://api.github.com/repos/jobayersajal1/project-safe-world/releases/latest";

    public const string ReleasesPageUrl =
        "https://github.com/jobayersajal1/project-safe-world/releases/latest";

    /// <summary>The file extension this platform can install.</summary>
    public const string AssetExtension = ".exe";

    /// <summary>Minimum time between automatic checks, so reopening the menu doesn't spend the rate limit.</summary>
    public const double CheckIntervalHours = 24;

    public static GitHubRelease? Parse(string json) =>
        JsonSerializer.Deserialize<GitHubRelease>(json);

    /// <param name="assetExtension">
    /// The file extension this platform can install (".exe" here). Pass null where nothing is
    /// installable and the result carries only <see cref="AvailableUpdate.PageUrl"/>.
    /// </param>
    public static AvailableUpdate? Select(GitHubRelease release, string currentVersion, string? assetExtension)
    {
        // A draft isn't public and a pre-release isn't being offered to everyone; neither should
        // be pushed at users automatically.
        if (release.Draft || release.Prerelease) return null;

        var published = (release.TagName ?? "").Trim();
        if (published.StartsWith('v') || published.StartsWith('V')) published = published[1..];
        if (published.Length == 0) return null;
        if (!Version.IsNewer(published, currentVersion)) return null;

        // The same release carries the .apk, .dmg, .ipa, and Chrome .zip too, so this must pick
        // out this platform's asset rather than taking the first.
        GitHubRelease.Asset? asset = null;
        if (assetExtension is not null)
        {
            asset = release.Assets.FirstOrDefault(a =>
                a.Name.EndsWith(assetExtension, StringComparison.OrdinalIgnoreCase));
        }

        return new AvailableUpdate(
            published,
            asset?.BrowserDownloadUrl,
            asset?.Size ?? 0,
            string.IsNullOrEmpty(release.HtmlUrl) ? ReleasesPageUrl : release.HtmlUrl
        );
    }
}
