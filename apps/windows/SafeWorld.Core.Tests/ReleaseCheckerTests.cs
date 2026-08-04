using Xunit;

namespace SafeWorld.Core.Tests;

/// <summary>
/// Parsing and asset selection, against the real shape of a GitHub release response. Mirrors
/// <c>ReleaseCheckerTests.swift</c> and <c>UpdateCheckerTest.kt</c>.
/// <para>
/// The payload is trimmed from an actual reply for the v0.1.0 release — abbreviated, but with its
/// unknown fields left in on purpose, because the one thing that would silently break this is
/// GitHub adding a field and the decoder refusing the whole document over it.
/// </para>
/// </summary>
public class ReleaseCheckerTests
{
    private static GitHubRelease Release(string? json = null) =>
        ReleaseChecker.Parse(json ?? LatestReleaseJson)!;

    [Fact]
    public void ParsesARealReleasePayloadIgnoringFieldsItDoesNotKnow()
    {
        var r = Release();
        Assert.Equal("v0.1.0", r.TagName);
        Assert.Equal(4, r.Assets.Count);
    }

    /// <summary>
    /// The reason <see cref="ReleaseChecker.Select"/> may not just take the first asset: one
    /// release carries every platform's download, and picking the wrong one would have Windows
    /// downloading a 20 MB Android package and trying to run it.
    /// </summary>
    [Fact]
    public void PicksTheExeOutOfAReleaseThatAlsoCarriesApkDmgAndZip()
    {
        var update = ReleaseChecker.Select(Release(), "0.0.9", ".exe");
        Assert.EndsWith("SafeWorld-0.1.0-win-x64.exe", update!.AssetUrl);
        Assert.Equal(144246169, update.SizeBytes);
    }

    [Fact]
    public void StripsTheLeadingVSoTheTagComparesAgainstAnAssemblyVersion()
    {
        Assert.Equal("0.1.0", ReleaseChecker.Select(Release(), "0.0.9", ".exe")!.Version);
    }

    [Fact]
    public void TheReleaseTheUserIsAlreadyOnIsNotAnUpdate()
    {
        Assert.Null(ReleaseChecker.Select(Release(), "0.1.0", ".exe"));
    }

    [Fact]
    public void ANewerInstallIsNotOfferedAnOlderRelease()
    {
        Assert.Null(ReleaseChecker.Select(Release(), "0.2.0", ".exe"));
    }

    [Fact]
    public void DraftsAndPreReleasesAreNotOffered()
    {
        var draft = Release(LatestReleaseJson.Replace("\"draft\": false", "\"draft\": true"));
        Assert.Null(ReleaseChecker.Select(draft, "0.0.9", ".exe"));

        var pre = Release(LatestReleaseJson.Replace("\"prerelease\": false", "\"prerelease\": true"));
        Assert.Null(ReleaseChecker.Select(pre, "0.0.9", ".exe"));
    }

    /// <summary>
    /// A release that carried no .exe must still report itself, so the tray can send the user to
    /// the page rather than silently concluding there is no update.
    /// </summary>
    [Fact]
    public void AReleaseWithNoMatchingAssetStillOffersItsPage()
    {
        var noExe = Release(LatestReleaseJson.Replace("SafeWorld-0.1.0-win-x64.exe", "SafeWorld-0.1.0-win-x64.txt"));
        var update = ReleaseChecker.Select(noExe, "0.0.9", ".exe");
        Assert.NotNull(update);
        Assert.Null(update!.AssetUrl);
        Assert.Equal("https://github.com/jobayersajal1/project-safe-world/releases/tag/v0.1.0", update.PageUrl);
    }

    private const string LatestReleaseJson = """
    {
      "url": "https://api.github.com/repos/jobayersajal1/project-safe-world/releases/258",
      "id": 258,
      "html_url": "https://github.com/jobayersajal1/project-safe-world/releases/tag/v0.1.0",
      "tag_name": "v0.1.0",
      "target_commitish": "main",
      "name": "Safe World 0.1.0 — Android, macOS, Windows",
      "draft": false,
      "prerelease": false,
      "created_at": "2026-07-27T01:05:44Z",
      "published_at": "2026-07-27T01:30:42Z",
      "assets": [
        {
          "name": "SafeWorld-0.1.0-macOS.dmg",
          "content_type": "application/x-apple-diskimage",
          "state": "uploaded",
          "size": 2523649,
          "download_count": 3,
          "browser_download_url": "https://github.com/jobayersajal1/project-safe-world/releases/download/v0.1.0/SafeWorld-0.1.0-macOS.dmg"
        },
        {
          "name": "SafeWorld-0.1.0-win-x64.exe",
          "content_type": "application/x-msdownload",
          "state": "uploaded",
          "size": 144246169,
          "download_count": 1,
          "browser_download_url": "https://github.com/jobayersajal1/project-safe-world/releases/download/v0.1.0/SafeWorld-0.1.0-win-x64.exe"
        },
        {
          "name": "SafeWorld-0.1.0.apk",
          "content_type": "application/vnd.android.package-archive",
          "state": "uploaded",
          "size": 20243764,
          "download_count": 12,
          "browser_download_url": "https://github.com/jobayersajal1/project-safe-world/releases/download/v0.1.0/SafeWorld-0.1.0.apk"
        },
        {
          "name": "SafeWorld-chrome-0.1.0.zip",
          "content_type": "application/zip",
          "state": "uploaded",
          "size": 11699461,
          "download_count": 0,
          "browser_download_url": "https://github.com/jobayersajal1/project-safe-world/releases/download/v0.1.0/SafeWorld-chrome-0.1.0.zip"
        }
      ],
      "body": "First Android, macOS, and Windows releases."
    }
    """;
}
