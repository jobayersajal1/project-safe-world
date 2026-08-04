namespace SafeWorld.App;

/// <summary>
/// Rounded counts for the blocked-count line: "4.5 million".
/// <para>
/// A reduced port of <c>apps/macos/SafeWorld/CountFormat.swift</c>, which is itself a reduced port
/// of the iOS one. The phones pull their scale words from a string catalog because they ship in
/// four languages, and Bengali counts in the Indian tradition — হাজার/লাখ/কোটি — where the
/// <i>tiers</i> differ, not just the words. <b>This app is English-only</b>, so the tier list is
/// fixed and the words are literals.
/// </para>
/// <para>
/// Note <c>InvariantGlobalization</c> is set in the .csproj, so grouping separators are the
/// invariant culture's regardless of the machine's locale. That is a deliberate constraint of the
/// single-file build, not a claim that commas are right everywhere.
/// </para>
/// </summary>
internal static class CountFormat
{
    private static readonly (double Divisor, string Word)[] Tiers =
    {
        (1e9, "billion"),
        (1e6, "million"),
        (1e3, "thousand"),
    };

    public static string Compact(int count)
    {
        double value = count;
        foreach (var (divisor, word) in Tiers)
        {
            if (value < divisor) continue;
            var scaled = value / divisor;
            // One decimal only when it says something: "4 million", not "4.0 million".
            var number = scaled < 10 ? scaled.ToString("0.#") : scaled.ToString("0");
            return $"{number} {word}";
        }
        // Below every tier there is no scale word to add, and the plain number is already the
        // shortest true form of it.
        return Exact(count);
    }

    /// <summary>The exact number, grouped: "4,536,263".</summary>
    public static string Exact(int count) => count.ToString("N0");
}
