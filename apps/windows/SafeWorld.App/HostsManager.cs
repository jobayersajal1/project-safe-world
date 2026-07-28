using System.Diagnostics;

namespace SafeWorld.App;

/// <summary>
/// Applies the SafeWorldCore-managed block to the Windows hosts file. The app runs elevated (see
/// <c>app.manifest</c>'s <c>requireAdministrator</c>), so — unlike macOS, which stages a temp file
/// and shells out for a one-time admin prompt — it can write the file directly; Windows shows its
/// UAC prompt once, at process launch, rather than per write.
/// </summary>
internal static class HostsManager
{
    private static readonly string Path = System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.System),
        "drivers", "etc", "hosts");

    public static string CurrentContents()
    {
        return File.Exists(Path) ? File.ReadAllText(Path) : "";
    }

    /// <summary>Writes <paramref name="newContents"/> to the hosts file if it differs from what's on disk, then flushes the DNS resolver cache. No-ops when nothing changed.</summary>
    public static void Apply(string newContents)
    {
        var existing = CurrentContents();
        if (existing == newContents) return;

        File.WriteAllText(Path, newContents);

        var flush = Process.Start(new ProcessStartInfo("ipconfig", "/flushdns")
        {
            CreateNoWindow = true,
            UseShellExecute = false,
        });
        flush?.WaitForExit(5000);
    }
}
