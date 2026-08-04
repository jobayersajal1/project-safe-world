using System.Text.Json;
using SafeWorld.Core;

namespace SafeWorld.App;

/// <summary>
/// JSON-file-backed store for <see cref="Settings"/>, the Windows analogue of <c>storage.ts</c> in
/// the Chrome extension. Whenever settings change it recomputes the managed block in the hosts
/// file and applies it via <see cref="HostsManager"/>.
/// </summary>
public sealed class SettingsStore
{
    public event Action? Changed;

    public Settings Settings { get; private set; }
    public string? LastSyncError { get; private set; }

    /// <summary>Domains fetched from <see cref="RemoteConfig.UpdateUrl"/>, merged into the bundled blocklists in <see cref="SyncHosts"/>. Empty until the first successful fetch.</summary>
    public Dictionary<CategoryId, List<string>> RemoteDomains { get; private set; } = new();

    private static readonly string SettingsPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "SafeWorld",
        "settings.json");

    /// <summary>
    /// The local DNS proxy, which carries the full uncapped list. Null when it could not start —
    /// see <see cref="ProxyController.Unavailable"/> — in which case the hosts file below is still
    /// applied and blocking continues with the smaller capped list.
    /// </summary>
    public ProxyController Proxy { get; } = new();

    /// <summary>True when the uncapped proxy is doing the blocking rather than the hosts file.</summary>
    public bool ProxyActive => Proxy.IsRunning;

    /// <summary>
    /// Whether first-run setup has been finished.
    /// <para>
    /// Stored beside the settings rather than inside them, because it is a fact about this
    /// installation rather than a blocking preference — <see cref="Settings"/> is the shape the
    /// hosts builder and the proxy consume, and it is shared with the other platforms' wire
    /// format.
    /// </para>
    /// <para>
    /// An install that predates the wizard has a settings file and no marker, and is treated as
    /// done rather than dragged back through setup — the same carry-forward rule the other three
    /// platforms use.
    /// </para>
    /// </summary>
    public bool OnboardingComplete { get; private set; }

    private static readonly string OnboardingPath = Path.Combine(
        Path.GetDirectoryName(SettingsPath)!, "setup-complete");

    public SettingsStore()
    {
        Settings = Load();
        OnboardingComplete = File.Exists(OnboardingPath) || File.Exists(SettingsPath);
        StartProxy();
        SyncHosts();
    }

    public void CompleteOnboarding()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(OnboardingPath)!);
        File.WriteAllText(OnboardingPath, "1");
        OnboardingComplete = true;
        Save();
        SyncHosts();
        Changed?.Invoke();
    }

    /// <summary>
    /// Forces every non-optional category on.
    /// <para>
    /// Port of <c>enforceMandatoryCategories</c> on Android, iOS, and macOS. Scam, gambling, and
    /// adult are the protection promise — installing the app is the decision to block them — so
    /// they are not a thing the UI offers to turn off, and a stored value that says otherwise (an
    /// older build of this app, which drew a switch for all six, or a hand-edited settings.json)
    /// must not be able to leave one off.
    /// </para>
    /// <para>Applied on load <i>and</i> on every mutation, so there is no window where a write puts
    /// settings into a state the loader would have corrected.</para>
    /// </summary>
    private static Settings EnforceMandatoryCategories(Settings settings)
    {
        foreach (var category in Categories.All.Where(c => !c.Optional))
        {
            settings.Categories[category.Id] = true;
        }
        return settings;
    }

    /// <summary>
    /// Domains covered by the currently enabled categories, plus the user's own — the number the
    /// tray menu states.
    /// <para>
    /// Read from the fuse filters' entry counts rather than by counting the bundled JSON, for the
    /// same reason the phones do it: the filters carry the full uncapped corpus while the JSON is
    /// the capped subset the hosts file can take, and the proxy — which is what actually does the
    /// blocking here — matches against the filters.
    /// </para>
    /// </summary>
    public int BlockedDomainCount(bool? ifEnabled = null)
    {
        if (!(ifEnabled ?? Settings.Enabled)) return 0;

        var bundled = FilterCounts.Value
            .Where(kv => Settings.Categories.TryGetValue(kv.Key, out var on) && on)
            .Sum(kv => kv.Value);
        var remote = RemoteDomains
            .Where(kv => Settings.Categories.TryGetValue(kv.Key, out var on) && on)
            .Sum(kv => kv.Value.Count);
        return bundled + remote + Settings.CustomBlock.Count;
    }

    /// <summary>
    /// Computed once — the staged filters cannot change under a running app, so only the enabled
    /// set has to be re-read.
    /// </summary>
    private static readonly Lazy<Dictionary<CategoryId, int>> FilterCounts = new(() =>
    {
        var counts = new Dictionary<CategoryId, int>();
        foreach (var id in Enum.GetValues<CategoryId>())
        {
            try
            {
                var path = Path.Combine(ProxyController.FilterDirectory, $"{id.ToRawValue()}.filter");
                using var filter = FuseFilter.Open(path);
                counts[id] = filter.Size;
            }
            catch
            {
                // A filter that cannot be read — not yet extracted, or unreadable — means a
                // smaller headline, which is better than a wrong one and much better than a crash
                // on the way to drawing a menu.
                counts[id] = 0;
            }
        }
        return counts;
    });

    /// <summary>
    /// Bring the proxy up if protection is on. Deliberately tolerant: a failure here is reported
    /// but never fatal, because the hosts file still provides blocking.
    /// </summary>
    private void StartProxy()
    {
        if (!Settings.Enabled)
        {
            Proxy.Stop();
            return;
        }
        Proxy.Start(Settings);
        Proxy.UpdateSettings(Settings);
    }

    private static Settings Load()
    {
        try
        {
            if (File.Exists(SettingsPath))
            {
                var json = File.ReadAllText(SettingsPath);
                var stored = JsonSerializer.Deserialize<Settings>(json);
                return EnforceMandatoryCategories(Settings.WithDefaults(stored));
            }
        }
        catch
        {
            // Corrupt or unreadable settings file: fall back to defaults rather than crashing.
        }
        return EnforceMandatoryCategories(Settings.Defaults());
    }

    private void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(SettingsPath)!);
        File.WriteAllText(SettingsPath, JsonSerializer.Serialize(Settings));
    }

    public void Update(Action<Settings> mutate)
    {
        mutate(Settings);
        EnforceMandatoryCategories(Settings);
        Save();
        SyncHosts();
        Changed?.Invoke();
    }

    public void SyncHosts()
    {
        // Keep the proxy's view of settings current, and start or stop it with the master switch.
        StartProxy();

        var blocklists = new Dictionary<CategoryId, List<string>>();
        foreach (var id in Enum.GetValues<CategoryId>())
        {
            var bundled = Blocklists.Domains(id);
            var remote = RemoteDomains.TryGetValue(id, out var r) ? r : new List<string>();
            blocklists[id] = bundled.Concat(remote).Distinct().ToList();
        }
        var block = HostsFileBuilder.RenderBlock(Settings, blocklists);

        try
        {
            var existing = HostsManager.CurrentContents();
            var updated = HostsFileBuilder.Apply(block, existing);
            HostsManager.Apply(updated);
            LastSyncError = null;
        }
        catch (Exception ex)
        {
            LastSyncError = ex.Message;
        }
    }

    /// <summary>
    /// Fetches and applies a remote update if one is configured and the last fetch is older than
    /// <see cref="RemoteConfig.UpdateIntervalHours"/>. Safe to call repeatedly (e.g. on a timer)
    /// — it no-ops when not due.
    ///
    /// Deliberately silent on failure, the same as iOS/Android: nothing in the UI reports remote
    /// update state, so a failure just means we retry next time it's due. Bundled lists remain
    /// the offline baseline; remote updates only ever add domains.
    /// </summary>
    public async Task RefreshRemoteIfDueAsync()
    {
        if (string.IsNullOrEmpty(RemoteConfig.UpdateUrl)) return;
        var dueAt = Settings.LastRemoteUpdate + RemoteConfig.UpdateIntervalHours * 3_600_000;
        if (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() < dueAt) return;

        try
        {
            var payload = await RemoteUpdateService.FetchAsync(RemoteConfig.UpdateUrl);

            // Mark the check done either way, so an unchanged list isn't re-fetched every tick.
            Settings.LastRemoteUpdate = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

            // Already applied this exact set — rewriting the hosts file for identical domains
            // costs an admin-privileged write for nothing. A payload with no UpdateId always
            // applies, which is how older feeds behaved.
            if (payload.UpdateId is not null && payload.UpdateId == Settings.LastAppliedUpdateId)
            {
                Save();
                return;
            }

            RemoteDomains = payload.DomainsByCategory();
            if (payload.UpdateId is not null) Settings.LastAppliedUpdateId = payload.UpdateId;
            Save();
            SyncHosts();
            Changed?.Invoke();
        }
        catch
        {
            // Silent by design — see the doc comment above.
        }
    }
}
