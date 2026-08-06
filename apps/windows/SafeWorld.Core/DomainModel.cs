using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace SafeWorld.Core;

/// <summary>
/// The advisory model's feature map and scoring — the C# half of a spec that
/// exists four times.
/// </summary>
/// <remarks>
/// <para>
/// <c>packages/core/src/advisory.ts</c> is the definition; this,
/// <c>DomainModel.swift</c> and <c>DomainModel.kt</c> reimplement it by hand,
/// and all four pin the same vectors in their tests the way <see cref="Scramble"/>
/// and <see cref="DomainHasher"/> already do. Four platforms disagreeing about
/// the same domain is the failure that discipline exists to prevent, so
/// <b>nothing here may change without changing all of them.</b>
/// </para>
/// <para>
/// What it is for: the blocklists are exact-membership sets, so a gambling site
/// registered this morning is not in them and stays reachable until someone
/// upstream adds it, we re-fetch, and a delta arrives. This scores the hostname
/// itself and guesses. It never blocks — <c>Matcher.Decide</c> remains the whole
/// decision — and the strongest thing it can say is "worth warning about".
/// </para>
/// <para>
/// The DNS proxy uses it. Windows runs a real resolver, so our code sees every
/// query and can score a name nobody has listed — but a DNS reply is yes or no,
/// with nowhere to put the "continue anyway" Chrome offers, so only the strict
/// tier applies here and most of the recall is given up for it.
/// </para>
/// <para>
/// The hosts-file fallback cannot carry this. A hosts file sinkholes names known
/// in advance, and the whole point is a name nobody knew about — so when the
/// resolver cannot start, the advisory tier goes quiet along with the rest of
/// the full list.
/// </para>
/// </remarks>
public static class DomainModel
{
    public const int HashBits = 18;
    public const int TableSize = 1 << HashBits;
    private const int TableMask = TableSize - 1;
    private static readonly int[] Ngrams = { 3, 4, 5 };

    private const uint FnvOffset = 0x811C9DC5;
    private const uint FnvPrime = 0x01000193;

    /// <summary>
    /// Matches <c>NormalizeHost</c> in <see cref="Matcher"/> plus stripping a
    /// leading <c>www.</c>, which appears on blocked and allowed names alike and
    /// would otherwise be learned as a signal.
    /// </summary>
    public static string Normalize(string host)
    {
        var s = host.Trim().ToLowerInvariant();
        while (s.EndsWith('.')) s = s[..^1];
        if (s.StartsWith("www.", StringComparison.Ordinal)) s = s[4..];
        return s;
    }

    private static uint Fnv1a(byte[] bytes, int start, int end)
    {
        var h = FnvOffset;
        for (var i = start; i < end; i++)
        {
            h = unchecked((h ^ bytes[i]) * FnvPrime);
        }
        return h;
    }

    private static int Bucket(int value, int[] edges)
    {
        for (var i = 0; i < edges.Length; i++)
        {
            if (value <= edges[i]) return i;
        }
        return edges.Length;
    }

    /// <summary>
    /// Whole-name properties no n-gram window can express.
    /// </summary>
    /// <remarks>
    /// A 5-gram sees <c>.xyz</c> only together with whatever precedes it, so a
    /// cheap TLD costs thousands of separate weights to learn. Same for "forty
    /// characters of digits and hyphens over two labels". Hashed into the same
    /// table as the n-grams, so they cost nothing in format or porting effort.
    /// </remarks>
    internal static string[] StructuralTokens(string host)
    {
        var labels = host.Split('.');
        var digits = 0;
        var hyphens = 0;
        foreach (var c in host)
        {
            if (c is >= '0' and <= '9') digits++;
            else if (c == '-') hyphens++;
        }

        var longest = 0;
        foreach (var l in labels) longest = Math.Max(longest, l.Length);
        var length = Encoding.UTF8.GetByteCount(host);

        // The \u0001 prefix namespaces these away from the n-grams. It was a
        // literal control byte in the TypeScript source once, which worked and
        // was invisible to anyone reading or reviewing it; every port now writes
        // the escape.
        return new[]
        {
            "\u0001tld=" + (labels.Length > 1 ? labels[^1] : ""),
            "\u0001sld=" + (labels.Length > 1 ? labels[^2] : ""),
            "\u0001labels=" + Math.Min(labels.Length, 6),
            "\u0001len=" + Bucket(length, new[] { 8, 12, 16, 20, 26, 34, 48 }),
            "\u0001digits=" + Bucket(100 * digits / Math.Max(1, length), new[] { 0, 5, 15, 30, 50 }),
            "\u0001hyphens=" + Bucket(hyphens, new[] { 0, 1, 2, 4 }),
            "\u0001longest=" + Bucket(longest, new[] { 4, 7, 10, 14, 20, 30 }),
        };
    }

    /// <summary>
    /// The L2-normalised sparse feature vector, as index to value.
    /// </summary>
    /// <remarks>
    /// Normalisation is not cosmetic: hostnames differ in length by an order of
    /// magnitude, and an unnormalised count vector scores a long name highly for
    /// being long — precisely the false positive this must not make.
    /// The sign comes from bit 31 of the same hash, so two n-grams colliding in
    /// the table cancel as often as they reinforce and a collision costs noise
    /// rather than a lean toward "blocked".
    /// </remarks>
    public static Dictionary<int, double> Features(string host)
    {
        var clean = Normalize(host);
        var acc = new Dictionary<int, double>();
        if (clean.Length == 0) return acc;

        void Add(uint h)
        {
            var idx = (int)(h & TableMask);
            var sign = ((h >> 31) & 1) == 1 ? -1.0 : 1.0;
            acc[idx] = acc.GetValueOrDefault(idx) + sign;
        }

        var bytes = Encoding.UTF8.GetBytes("^" + clean + "$");
        foreach (var n in Ngrams)
        {
            for (var i = 0; i + n <= bytes.Length; i++) Add(Fnv1a(bytes, i, i + n));
        }
        foreach (var token in StructuralTokens(clean))
        {
            var t = Encoding.UTF8.GetBytes(token);
            Add(Fnv1a(t, 0, t.Length));
        }

        var sum = 0.0;
        foreach (var v in acc.Values) sum += v * v;
        if (sum == 0.0) return new Dictionary<int, double>();
        var inv = 1.0 / Math.Sqrt(sum);
        foreach (var k in acc.Keys.ToList()) acc[k] *= inv;
        return acc;
    }

    /// <summary>
    /// <paramref name="Threshold"/> is where warning is defensible;
    /// <paramref name="BlockThreshold"/> is where blocking outright is. Different
    /// claims, not degrees of one — only the second may take a site away without
    /// asking, so it is held far stricter than the hand review requires.
    /// Gambling gives up 24% recall for 8% to earn it. The default of infinity
    /// means "warn only", so a model file predating the tier cannot block.
    /// </summary>
    public sealed record Weights(
        CategoryId Category,
        double Scale,
        double Bias,
        double Threshold,
        sbyte[] Values,
        double BlockThreshold = double.PositiveInfinity);

    /// <summary>
    /// What the model is willing to claim. Warn and Block are different claims,
    /// not degrees of the same one.
    /// </summary>
    public enum Action { Warn, Block }

    public readonly record struct Verdict(Action Action, CategoryId Category, double Score);

    public static double Score(Weights weights, string host)
    {
        var sum = 0.0;
        foreach (var (idx, value) in Features(host))
        {
            if (idx < weights.Values.Length) sum += weights.Values[idx] * value;
        }
        return sum * weights.Scale + weights.Bias;
    }

    /// <summary>
    /// Hosts on a shared publishing platform are never scored.
    /// </summary>
    /// <remarks>
    /// Not a nicety — the failure that showed up in measurement. Adult Blogger
    /// blogs are heavily represented in the adult list, so the model learned
    /// that <c>*.blogspot.com</c> is adult and then flagged Blogger's own image
    /// CDN, <c>1.bp.blogspot.com</c> and <c>2.</c> and <c>3.</c>, which serve the
    /// pictures on every Blogger blog there is. A subdomain on shared hosting
    /// says something about that one blog and nothing about the platform, and
    /// the string cannot tell them apart, so we decline to guess.
    /// </remarks>
    private static readonly string[] SharedPlatforms =
    {
        "blogspot.com", "bp.blogspot.com", "wordpress.com", "tumblr.com", "medium.com",
        "github.io", "gitlab.io", "gitbook.io", "weebly.com", "weeblysite.com",
        "wixsite.com", "webflow.io", "pages.dev", "vercel.app", "netlify.app",
        "herokuapp.com", "duckdns.org", "000webhostapp.com", "blogger.com",
        "substack.com", "notion.site", "myshopify.com", "translate.goog",
        "googleusercontent.com", "cloudfront.net", "akamaized.net", "amazonaws.com",
    };

    public static bool IsSharedPlatformHost(string host)
    {
        var clean = Normalize(host);
        foreach (var platform in SharedPlatforms)
        {
            if (clean == platform || clean.EndsWith("." + platform, StringComparison.Ordinal)) return true;
        }
        return false;
    }

    /// <summary>
    /// The bundled models, read from the same directory as the fuse filters.
    /// </summary>
    /// <remarks>
    /// A missing file is not an error worth raising. The models are a generated
    /// artifact, and a build without them must behave exactly like a build from
    /// before the feature existed.
    /// </remarks>
    public static List<Weights> Load(string directory)
    {
        var loaded = new List<Weights>();
        foreach (var category in new[] { CategoryId.Gambling, CategoryId.Adult })
        {
            var w = Load(directory, category);
            if (w is not null) loaded.Add(w);
        }
        return loaded;
    }

    private static Weights? Load(string directory, CategoryId category)
    {
        var path = Path.Combine(directory, $"{category.ToRawValue()}.model.json");
        if (!File.Exists(path)) return null;

        ModelFile? file;
        try
        {
            file = JsonSerializer.Deserialize<ModelFile>(File.ReadAllText(path));
        }
        catch (JsonException)
        {
            return null;
        }
        if (file is null) return null;

        // Refusing beats scoring against a mismatched table, which produces
        // confident nonsense rather than an obvious failure.
        if (file.TableSize != TableSize || file.Weights is null) return null;
        byte[] raw;
        try
        {
            raw = Convert.FromBase64String(file.Weights);
        }
        catch (FormatException)
        {
            return null;
        }
        if (raw.Length != TableSize) return null;

        var values = new sbyte[TableSize];
        Buffer.BlockCopy(raw, 0, values, 0, TableSize);

        return new Weights(
            category,
            file.Scale,
            file.Bias,
            file.Threshold,
            values,
            // A model file predating the block tier must never fall back to
            // zero, which would block everything.
            file.BlockThreshold ?? double.PositiveInfinity);
    }

    private sealed record ModelFile(
        string? Category,
        int TableSize,
        double Scale,
        double Bias,
        double Threshold,
        double? BlockThreshold,
        string? Weights);

    /// <summary>The second stage. Call only for a host <c>Matcher.Decide</c> returned allowed.</summary>
    /// <remarks>
    /// <paramref name="allowBlocking"/> gates the stricter tier. A surface with
    /// no way to offer "continue anyway" — a DNS reply, a resolver answer — must
    /// pass true and accept the much lower recall that buys.
    /// </remarks>
    public static Verdict? Advise(string host, IReadOnlyList<Weights> models, bool allowBlocking = false)
    {
        var clean = Normalize(host);
        if (clean.Length == 0 || IsSharedPlatformHost(clean)) return null;
        foreach (var model in models)
        {
            var s = Score(model, clean);
            if (allowBlocking && s >= model.BlockThreshold)
            {
                return new Verdict(Action.Block, model.Category, s);
            }
            if (s >= model.Threshold) return new Verdict(Action.Warn, model.Category, s);
        }
        return null;
    }
}

/// <summary>
/// Whether the advisory model is allowed to act, and for which categories.
/// </summary>
/// <remarks>
/// Its own stored value, never fields on <see cref="Settings"/> — mirrors
/// <c>ADVISORY_STORAGE_KEY</c> in <c>packages/core/src/advisory.ts</c>.
/// Ships <b>off</b>: everything else here answers from a list and is a fact,
/// this one guesses, and a guess should be opted into.
/// </remarks>
public sealed class AdvisorySettings
{
    public bool Enabled { get; set; }

    public Dictionary<string, bool> Categories { get; set; } = new()
    {
        ["list2"] = true,
        ["list3"] = true,
    };

    public bool EnabledFor(CategoryId category) =>
        Enabled && Categories.TryGetValue(category.ToRawValue(), out var on) && on;
}
