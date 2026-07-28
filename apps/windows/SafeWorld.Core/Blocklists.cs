using System.Reflection;
using System.Text.Json;

namespace SafeWorld.Core;

/// <summary>
/// Mirrors <c>packages/core/src/blocklists.ts</c>. Domains are bundled as embedded JSON resources
/// written by <c>npm run build:windows</c>, <b>scrambled</b> — the published single-file .exe
/// yields no readable list of blocked sites. They are reversed here, in memory, on first access.
/// </summary>
public static class Blocklists
{
    private sealed class BlocklistFile
    {
        public string Category { get; set; } = "";
        public string Source { get; set; } = "";
        /// <summary>Absent on a file predating scrambling; see <see cref="Domains"/>.</summary>
        public string? Format { get; set; }
        public List<string> Domains { get; set; } = new();
    }

    private static readonly Dictionary<CategoryId, List<string>> Cache = new();

    /// <summary>Bundled domains for a category (as authored, not normalized).</summary>
    public static IReadOnlyList<string> Domains(CategoryId category)
    {
        if (Cache.TryGetValue(category, out var cached)) return cached;

        var assembly = Assembly.GetExecutingAssembly();
        var resourceName = $"SafeWorld.Core.Resources.{category.ToRawValue()}.json";
        using var stream = assembly.GetManifestResourceStream(resourceName);
        if (stream is null)
        {
            Cache[category] = new List<string>();
            return Cache[category];
        }

        var file = JsonSerializer.Deserialize<BlocklistFile>(stream, new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true,
        });
        var raw = file?.Domains ?? new List<string>();

        // A file with no Format predates scrambling and is already plaintext; a
        // format this build can't reverse yields nothing rather than garbage
        // domains that would silently block the wrong sites.
        var domains = file?.Format switch
        {
            null => raw,
            Scramble.Format => raw.Select(Scramble.UnscrambleDomain)
                                  .Where(d => !string.IsNullOrEmpty(d))
                                  .ToList(),
            _ => new List<string>(),
        };

        Cache[category] = domains;
        return domains;
    }

    /// <summary>Bundled domains for every category as sets, for use with <see cref="Matcher.Decide"/>.</summary>
    public static Dictionary<CategoryId, HashSet<string>> All()
    {
        var result = new Dictionary<CategoryId, HashSet<string>>();
        foreach (var id in Enum.GetValues<CategoryId>()) result[id] = new HashSet<string>(Domains(id));
        return result;
    }
}
