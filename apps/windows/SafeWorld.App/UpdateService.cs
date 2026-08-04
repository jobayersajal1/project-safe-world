using System.Diagnostics;
using System.Reflection;
using SafeWorld.Core;

namespace SafeWorld.App;

/// <summary>
/// Checks for a newer build, downloads its .exe, and hands it to the user — the Windows half of
/// macOS's <c>UpdateService.swift</c> and Android's <c>UpdateManager.kt</c>.
/// <para>
/// <b>Windows was the one platform with no updater at all.</b> Android, iOS, and macOS have had
/// one since 0.2.0; this app has been shipping as a downloaded .exe with no way of knowing it was
/// six releases stale, which is the worst case for a blocklist product — the app keeps working, so
/// nothing ever prompts the user to notice.
/// </para>
/// <para>
/// <b>It stops at "the installer is downloaded and revealed", deliberately.</b> The release is a
/// self-contained single-file .exe, so updating means replacing the very file this process is
/// running from — Windows holds an exclusive lock on it, so the swap has to be done by something
/// that outlives us: a detached script that waits for the process to exit, moves the new file over
/// the old, and relaunches. That is a real technique, and it is also the technique that leaves a
/// user with no app at all if it dies between the delete and the move. This app is unsigned (see
/// the README), so there is nothing to verify the replacement against either. macOS made the same
/// call for the same reason and stops at mounting the .dmg.
/// </para>
/// </summary>
internal sealed class UpdateService
{
    internal enum Status { Idle, Checking, UpToDate, Available, Downloading, Downloaded, Failed }

    /// <summary>Raised whenever <see cref="State"/> changes, so the tray menu can redraw.</summary>
    public event Action? Changed;

    public Status State { get; private set; } = Status.Idle;
    public AvailableUpdate? Update { get; private set; }
    /// <summary>0..100 while downloading, or -1 while the size is still unknown.</summary>
    public int Percent { get; private set; } = -1;
    public string? Message { get; private set; }

    /// <summary>
    /// The running build's assembly version, shown in the menu and compared against the newest
    /// release. Trimmed to three components: the assembly version is always stamped as four
    /// ("0.5.0.0") and comparing that against the tag "0.5.0" is only equal by luck of the
    /// missing-components-are-zero rule — better to compare what we mean.
    /// </summary>
    public static string InstalledVersion { get; } = ReadInstalledVersion();

    private static string ReadInstalledVersion()
    {
        var raw = Assembly.GetExecutingAssembly().GetName().Version;
        return raw is null ? "0.0.0" : $"{raw.Major}.{raw.Minor}.{raw.Build}";
    }

    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        var client = new HttpClient();
        client.DefaultRequestHeaders.Add("Accept", "application/vnd.github+json");
        // GitHub rejects API requests that don't identify themselves.
        client.DefaultRequestHeaders.Add("User-Agent", "SafeWorld-Windows");
        return client;
    }

    private DateTime? _lastCheckedAt;

    private void Set(Status state, string? message = null)
    {
        State = state;
        Message = message;
        Changed?.Invoke();
    }

    // MARK: Checking

    /// <summary>
    /// Looks for a newer release. <paramref name="force"/> runs even when one ran recently — the
    /// throttle exists so reopening the tray menu doesn't spend the unauthenticated rate limit,
    /// but a button the user pressed should always do something.
    /// </summary>
    public async Task CheckAsync(bool force = false)
    {
        if (State is Status.Checking or Status.Downloading) return;
        if (!force && _lastCheckedAt is { } last
            && (DateTime.UtcNow - last).TotalHours < ReleaseChecker.CheckIntervalHours)
        {
            return;
        }

        Set(Status.Checking);
        try
        {
            var json = await Http.GetStringAsync(ReleaseChecker.LatestReleaseUrl);
            var release = ReleaseChecker.Parse(json);
            _lastCheckedAt = DateTime.UtcNow;

            Update = release is null
                ? null
                : ReleaseChecker.Select(release, InstalledVersion, ReleaseChecker.AssetExtension);
            Set(Update is null ? Status.UpToDate : Status.Available);
        }
        catch (Exception)
        {
            // Network, HTTP, and malformed JSON all mean the same thing to the user: we couldn't
            // ask right now. Never surfaced as "no update", which would be a wrong answer rather
            // than an absent one.
            Set(Status.Failed, "Couldn't check for updates.");
        }
    }

    // MARK: Downloading

    /// <summary>
    /// Downloads the installer to the Downloads folder and reveals it in Explorer. A release with
    /// no .exe attached (the .apk and .dmg live in the same release) still has a web page worth
    /// going to, so that case opens the page instead of failing.
    /// </summary>
    public async Task DownloadAsync(AvailableUpdate update)
    {
        if (update.AssetUrl is null)
        {
            OpenInBrowser(update.PageUrl);
            return;
        }
        if (State == Status.Downloading) return;

        Update = update;
        Percent = -1;
        Set(Status.Downloading);

        try
        {
            var path = await DownloadToDownloadsAsync(update.AssetUrl, update.SizeBytes);
            Set(Status.Downloaded);
            RevealInExplorer(path);
        }
        catch (Exception)
        {
            Set(Status.Failed, "The download didn't finish.");
        }
    }

    private async Task<string> DownloadToDownloadsAsync(string url, long knownSize)
    {
        using var response = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead);
        response.EnsureSuccessStatusCode();

        // The release metadata's size is the more reliable of the two: the asset download
        // redirects, and the final response doesn't always carry a length.
        var total = knownSize > 0 ? knownSize : response.Content.Headers.ContentLength ?? 0;

        var target = Path.Combine(DownloadsFolder(), Path.GetFileName(new Uri(url).LocalPath));
        // Written to a temporary name and moved into place, so an interrupted download cannot
        // leave something that looks like a complete installer sitting in Downloads.
        var partial = target + ".part";

        await using (var source = await response.Content.ReadAsStreamAsync())
        await using (var file = File.Create(partial))
        {
            var buffer = new byte[81920];
            long read = 0;
            var lastReported = -1;
            int n;
            while ((n = await source.ReadAsync(buffer)) > 0)
            {
                await file.WriteAsync(buffer.AsMemory(0, n));
                read += n;
                if (total <= 0) continue;

                var percent = (int)(read * 100 / total);
                // Rebuilding the menu per chunk would repaint it thousands of times for a 140 MB
                // download; whole percent steps are past what the eye resolves anyway.
                if (percent == lastReported) continue;
                lastReported = percent;
                Percent = Math.Min(percent, 100);
                Changed?.Invoke();
            }
        }

        // Replace a previous attempt rather than piling up "file (1).exe".
        if (File.Exists(target)) File.Delete(target);
        File.Move(partial, target);
        return target;
    }

    private static string DownloadsFolder()
    {
        // There is no Environment.SpecialFolder for Downloads. The profile-relative path is what
        // the shell uses by default, and falling back to the profile root is better than throwing
        // for someone who has relocated it.
        var profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var downloads = Path.Combine(profile, "Downloads");
        return Directory.Exists(downloads) ? downloads : profile;
    }

    private static void RevealInExplorer(string path)
    {
        // Selects the file rather than just opening the folder, so it is obvious which of the
        // user's downloads is the one that was just fetched.
        Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{path}\"") { UseShellExecute = true });
    }

    public static void OpenInBrowser(string url)
    {
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
    }
}
