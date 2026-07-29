using System.Diagnostics;
using System.Net;
using System.Text.Json;

namespace SafeWorld.App;

/// <summary>
/// Points Windows at the local <see cref="DnsProxy"/> and — more importantly — puts it back.
///
/// This is the most dangerous code in the Windows app. The hosts file fails safe: if the app dies,
/// the entries remain and DNS still works. Redirecting the system resolver does not. Get it wrong
/// and the machine has no name resolution at all: no browsing, no email, no way to look up how to
/// fix it. Three things guard against that.
///
/// <b>1. The real resolver stays configured as the secondary.</b> Windows queries the first server
/// and only falls back on a timeout or network error — a valid NXDOMAIN is an answer, not a
/// failure, so blocking still works exactly as intended. But if the proxy dies, stalls, or is
/// killed, queries fall through to the original resolver after a short delay and the machine keeps
/// working. Blocking degrades; connectivity does not.
///
/// <b>2. The previous configuration is written to disk before anything changes.</b> If the process
/// is killed outright, the next launch finds the record and restores it.
///
/// <b>3. Restore runs on normal exit and on unhandled exceptions.</b>
///
/// The cost of (1) is honest: someone who kills the process gets working DNS with no filtering.
/// For a self-control app that is a real weakening — the hosts file could not be bypassed that
/// way. It is the right trade anyway, because the alternative failure is bricking someone's
/// internet, and that harms people who did nothing wrong.
/// </summary>
public sealed class DnsSettingsManager
{
    private sealed record AdapterState(string Name, List<string> Servers);

    private static string StatePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "SafeWorld",
        "dns-restore.json");

    private List<AdapterState> _previous = new();

    /// <summary>
    /// Restore anything a previous run left behind. Called at startup, before doing anything else,
    /// so a crash never outlives one session.
    /// </summary>
    public static void RecoverFromPreviousRun()
    {
        if (!File.Exists(StatePath)) return;
        try
        {
            var saved = JsonSerializer.Deserialize<List<AdapterState>>(File.ReadAllText(StatePath));
            if (saved is not null)
            {
                foreach (var adapter in saved) Apply(adapter.Name, adapter.Servers);
            }
        }
        catch (Exception)
        {
            // Nothing better to do; leaving the file would retry forever.
        }
        finally
        {
            try { File.Delete(StatePath); } catch (Exception) { /* best effort */ }
        }
    }

    /// <summary>
    /// Redirect every active adapter to the proxy, keeping its current resolver as the fallback.
    /// Returns false if nothing could be changed.
    /// </summary>
    public bool Redirect()
    {
        _previous = ReadCurrent();
        if (_previous.Count == 0) return false;

        Directory.CreateDirectory(Path.GetDirectoryName(StatePath)!);
        // Written *before* the change, so a kill between the two leaves a recoverable record.
        File.WriteAllText(StatePath, JsonSerializer.Serialize(_previous));

        foreach (var adapter in _previous)
        {
            var fallback = adapter.Servers.FirstOrDefault(s => s != DnsProxy.ListenAddress.ToString());
            var servers = fallback is null
                ? new List<string> { DnsProxy.ListenAddress.ToString() }
                : new List<string> { DnsProxy.ListenAddress.ToString(), fallback };
            Apply(adapter.Name, servers);
        }
        return true;
    }

    /// <summary>Put every adapter back the way it was. Safe to call more than once.</summary>
    public void Restore()
    {
        foreach (var adapter in _previous) Apply(adapter.Name, adapter.Servers);
        _previous = new List<AdapterState>();
        try { if (File.Exists(StatePath)) File.Delete(StatePath); } catch (Exception) { /* best effort */ }
    }

    /// <summary>The upstream resolver to forward allowed queries to — whatever the machine used before.</summary>
    public IPAddress UpstreamResolver()
    {
        foreach (var adapter in _previous)
        {
            foreach (var server in adapter.Servers)
            {
                if (server == DnsProxy.ListenAddress.ToString()) continue;
                if (IPAddress.TryParse(server, out var parsed) && parsed.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
                {
                    return parsed;
                }
            }
        }
        // The adapter was on DHCP with no static servers recorded. Falling back to a public
        // resolver keeps queries answered rather than dropping them.
        return IPAddress.Parse("1.1.1.1");
    }

    private static List<AdapterState> ReadCurrent()
    {
        var output = RunPowerShell(
            "Get-DnsClientServerAddress -AddressFamily IPv4 | " +
            "Where-Object { $_.ServerAddresses.Count -gt 0 } | " +
            "Select-Object InterfaceAlias,ServerAddresses | ConvertTo-Json -Compress");

        if (string.IsNullOrWhiteSpace(output)) return new List<AdapterState>();

        try
        {
            using var document = JsonDocument.Parse(output);
            var root = document.RootElement;
            var elements = root.ValueKind == JsonValueKind.Array
                ? root.EnumerateArray().ToList()
                : new List<JsonElement> { root };

            return elements
                .Select(e => new AdapterState(
                    e.GetProperty("InterfaceAlias").GetString() ?? "",
                    e.GetProperty("ServerAddresses").EnumerateArray()
                        .Select(s => s.GetString() ?? "")
                        .Where(s => s.Length > 0)
                        .ToList()))
                .Where(a => a.Name.Length > 0 && a.Servers.Count > 0)
                .ToList();
        }
        catch (Exception)
        {
            return new List<AdapterState>();
        }
    }

    private static void Apply(string adapter, List<string> servers)
    {
        var list = string.Join(",", servers.Select(s => $"'{s}'"));
        RunPowerShell($"Set-DnsClientServerAddress -InterfaceAlias '{adapter}' -ServerAddresses {list}");
        // Stale entries would otherwise keep resolving names we now block, or vice versa.
        RunPowerShell("Clear-DnsClientCache");
    }

    private static string RunPowerShell(string command)
    {
        try
        {
            using var process = Process.Start(new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = $"-NoProfile -NonInteractive -Command \"{command}\"",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            });
            if (process is null) return "";
            var output = process.StandardOutput.ReadToEnd();
            process.WaitForExit(15000);
            return output;
        }
        catch (Exception)
        {
            return "";
        }
    }
}
