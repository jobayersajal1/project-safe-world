using System.Diagnostics;

namespace SafeWorld.App;

/// <summary>
/// Makes Safe World start with Windows, so protection survives a restart.
///
/// **Why this exists.** The DNS proxy runs inside this process — there is no service and no daemon —
/// so when the app is not running, nothing is blocking. Until this was added the app registered no
/// startup entry at all, which meant a reboot silently ended protection: the tray icon was gone, the
/// proxy was gone, and the only clue was that harmful sites started resolving again.
///
/// It is worse than "no protection", too. `DnsSettingsManager` points the adapters at 127.0.0.1 with
/// the real resolver as secondary, and a reboot is not a clean exit, so that configuration is still
/// in place on the next boot with nothing listening on 127.0.0.1. Every lookup then waits for the
/// dead primary to time out before falling back — unfiltered *and* slow.
/// <see cref="DnsSettingsManager.RecoverFromPreviousRun"/> puts it right, but only runs when the app
/// starts, which is exactly what was not happening.
///
/// **Why a scheduled task and not the Run key.** `app.manifest` requests `requireAdministrator`, and
/// Windows will not elevate anything launched from
/// `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` — it silently does nothing. A scheduled task
/// with "run with highest privileges" is the supported way to start an elevated app at logon without
/// a UAC prompt every time.
/// </summary>
public static class StartupManager
{
    private const string TaskName = "Safe World";

    /// <summary>
    /// The single-file executable's own path.
    ///
    /// `Environment.ProcessPath`, not `AppContext.BaseDirectory`: for a single-file publish the
    /// latter is the temporary extraction directory, which is a different place on every run and
    /// would leave the task pointing at something that no longer exists.
    /// </summary>
    private static string? ExecutablePath => Environment.ProcessPath;

    /// <summary>True when a logon task exists that points at this executable.</summary>
    public static bool IsRegistered()
    {
        var output = RunSchtasks($"/Query /TN \"{TaskName}\" /FO LIST /V", out var exitCode);
        if (exitCode != 0) return false;

        // Registered, but possibly pointing at an old location — someone moved the .exe, or this is
        // a second copy. Treated as not registered so the caller re-points it.
        var path = ExecutablePath;
        return path is null || output.Contains(path, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// Creates or repoints the logon task. Returns false if it could not be created.
    /// </summary>
    public static bool Register()
    {
        var path = ExecutablePath;
        if (string.IsNullOrEmpty(path)) return false;

        // /F overwrites an existing task, which is what makes this also the "repoint after the exe
        // moved" path. /RL HIGHEST is the whole point: without it the task runs unelevated and the
        // app cannot touch DNS settings or the hosts file.
        RunSchtasks(
            $"/Create /TN \"{TaskName}\" /TR \"\\\"{path}\\\"\" /SC ONLOGON /RL HIGHEST /F",
            out var exitCode);
        return exitCode == 0;
    }

    /// <summary>Removes the logon task. Safe to call when there isn't one.</summary>
    public static void Unregister() =>
        RunSchtasks($"/Delete /TN \"{TaskName}\" /F", out _);

    /// <summary>
    /// Registers on every launch unless already correct.
    ///
    /// Deliberately not a one-time "first run" flag: the executable can be moved, the task can be
    /// deleted by cleanup tools, and a stale task is indistinguishable from no task in its effect.
    /// Checking is cheap; being wrong is silent.
    /// </summary>
    public static void EnsureRegistered()
    {
        if (!IsRegistered()) Register();
    }

    private static string RunSchtasks(string arguments, out int exitCode)
    {
        try
        {
            using var process = Process.Start(new ProcessStartInfo
            {
                FileName = "schtasks.exe",
                Arguments = arguments,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            });

            if (process is null)
            {
                exitCode = -1;
                return string.Empty;
            }

            var output = process.StandardOutput.ReadToEnd();
            process.WaitForExit();
            exitCode = process.ExitCode;
            return output;
        }
        catch (Exception)
        {
            // Startup registration failing must never stop the app from running and blocking; it
            // only costs protection after the *next* reboot, and the tray reports the state.
            exitCode = -1;
            return string.Empty;
        }
    }
}
