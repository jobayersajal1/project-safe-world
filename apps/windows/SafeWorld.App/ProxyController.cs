using System.Net;
using SafeWorld.Core;

namespace SafeWorld.App;

/// <summary>
/// Owns the DNS proxy and the system DNS redirect, and guarantees the redirect is undone.
///
/// The proxy is what lets Windows carry the full uncapped list. The hosts file remains as the
/// fallback: if the proxy can't start — port 53 already taken by Docker Desktop, Internet
/// Connection Sharing, or another filter — the app keeps working exactly as it did before, just
/// with the smaller capped list. Blocking degrades rather than disappearing.
/// </summary>
public sealed class ProxyController : IDisposable
{
    private readonly DnsSettingsManager _dns = new();
    private FilterEngine? _engine;
    private DnsProxy? _proxy;

    /// <summary>Set when the proxy could not start, for the UI to explain why.</summary>
    public string? Unavailable { get; private set; }

    /// <summary>True when the proxy is serving and the system points at it.</summary>
    public bool IsRunning => _proxy is not null;

    /// <summary>Domains covered by the enabled categories, or 0 when the proxy isn't running.</summary>
    public int BlockedDomainCount => _engine?.BlockedDomainCount ?? 0;

    static ProxyController()
    {
        // Before anything else: undo a redirect a previous run died holding. A crash must never
        // outlive one session, because the symptom is a machine with no name resolution.
        DnsSettingsManager.RecoverFromPreviousRun();
    }

    /// <summary>Where the filters live once extracted from the executable.</summary>
    public static string FilterDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "SafeWorld",
        "filters");

    /// <summary>
    /// Write the embedded filters to disk if they aren't already there.
    ///
    /// They ship inside the single-file .exe so the download stays one artifact, but a
    /// memory-mapped file has to be a real file. Re-extracted whenever the size differs, which is
    /// how an app update ships a new list.
    /// </summary>
    private static void ExtractFilters()
    {
        Directory.CreateDirectory(FilterDirectory);
        var assembly = typeof(ProxyController).Assembly;

        foreach (var id in Enum.GetValues<CategoryId>())
        {
            var name = $"{id.ToRawValue()}.filter";
            using var source = assembly.GetManifestResourceStream($"filters.{name}");
            if (source is null) continue;

            var destination = Path.Combine(FilterDirectory, name);
            if (File.Exists(destination) && new FileInfo(destination).Length == source.Length) continue;

            using var target = File.Create(destination);
            source.CopyTo(target);
        }
    }

    public bool Start(Settings settings)
    {
        if (IsRunning) return true;
        Unavailable = null;

        try
        {
            ExtractFilters();
            if (!Directory.Exists(FilterDirectory) || Directory.GetFiles(FilterDirectory, "*.filter").Length == 0)
            {
                Unavailable = "Blocklist filters are missing from this install.";
                return false;
            }

            // Checked before touching system DNS, so a conflict is discovered while resolution
            // still works.
            if (!DnsProxy.PortIsAvailable())
            {
                Unavailable = "Another program is already using DNS port 53 on this computer.";
                return false;
            }

            _engine = new FilterEngine(FilterDirectory, settings);
            _engine.UpdateAdvisory(_advisory);

            if (!_dns.Redirect())
            {
                Unavailable = "No network adapter could be reconfigured.";
                _engine.Dispose();
                _engine = null;
                return false;
            }

            _proxy = new DnsProxy(_engine, _dns.UpstreamResolver());
            _proxy.Start();

            // Belt and braces on top of the on-disk record: these cover the ordinary exits.
            AppDomain.CurrentDomain.ProcessExit += OnProcessExit;
            AppDomain.CurrentDomain.UnhandledException += OnUnhandledException;
            return true;
        }
        catch (Exception ex)
        {
            Unavailable = ex.Message;
            Stop();
            return false;
        }
    }

    public void UpdateSettings(Settings settings) => _engine?.Update(settings);

    /// <summary>Applies to the running proxy at once — no restart, unlike macOS's daemon.</summary>
    public void UpdateAdvisory(AdvisorySettings advisory)
    {
        _advisory = advisory;
        _engine?.UpdateAdvisory(advisory);
    }

    /// Held so a proxy started later gets the current setting rather than the default.
    private AdvisorySettings _advisory = new();

    public void Stop()
    {
        AppDomain.CurrentDomain.ProcessExit -= OnProcessExit;
        AppDomain.CurrentDomain.UnhandledException -= OnUnhandledException;

        // Order matters: put DNS back *before* tearing down the listener, so there is never a
        // window where the system points at a socket that has stopped answering.
        _dns.Restore();

        _proxy?.Dispose();
        _proxy = null;
        _engine?.Dispose();
        _engine = null;
    }

    private void OnProcessExit(object? sender, EventArgs e) => Stop();

    private void OnUnhandledException(object? sender, UnhandledExceptionEventArgs e) => Stop();

    public void Dispose() => Stop();
}
