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

    public SettingsStore()
    {
        Settings = Load();
        StartProxy();
        SyncHosts();
    }

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
                return Settings.WithDefaults(stored);
            }
        }
        catch
        {
            // Corrupt or unreadable settings file: fall back to defaults rather than crashing.
        }
        return Settings.Defaults();
    }

    private void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(SettingsPath)!);
        File.WriteAllText(SettingsPath, JsonSerializer.Serialize(Settings));
    }

    public void Update(Action<Settings> mutate)
    {
        mutate(Settings);
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
