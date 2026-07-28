namespace SafeWorld.Core;

/// <summary>Thrown when a fetched payload is encoded in a way this app can't use.</summary>
public sealed class RemoteUpdateFormatException : Exception
{
    public RemoteUpdateFormatException(string message) : base(message)
    {
    }

    public static RemoteUpdateFormatException HashedListForAndroid() => new(
        "That list is hashed for Android and can't be used here — " +
        "point this app at the scrambled list instead.");

    public static RemoteUpdateFormatException Unrecognized(string format) => new(
        $"Unrecognized list format \"{format}\".");
}

/// <summary>
/// Mirrors <c>packages/core/src/types.ts</c> (<c>RemoteUpdatePayload</c>). Domains are keyed by
/// the category's raw string id (e.g. "list2") so the JSON shape matches what the JS side of the
/// project produces/consumes.
/// </summary>
public sealed class RemoteUpdatePayload
{
    public int Version { get; set; }

    /// <summary>Identifies this exact set of domains, so a client can skip work it has already done. Absent on payloads published before the field existed.</summary>
    public string? UpdateId { get; set; }

    /// <summary>How <see cref="Domains"/> is encoded — see <see cref="Scramble"/>. Null means plaintext, so payloads published before this field keep working.</summary>
    public string? Format { get; set; }

    public Dictionary<string, List<string>> Domains { get; set; } = new();

    /// <summary>
    /// <see cref="Domains"/> re-keyed by <see cref="CategoryId"/> and decoded to plaintext,
    /// dropping any unrecognized category keys.
    ///
    /// Throws rather than returning an empty result for a format this app can't use: silently
    /// yielding nothing would leave the app looking healthy while blocking nothing new, which is
    /// the worst way for this to fail.
    /// </summary>
    public Dictionary<CategoryId, List<string>> DomainsByCategory()
    {
        if (Format == Scramble.HashedFormat) throw RemoteUpdateFormatException.HashedListForAndroid();
        if (Format is not null && Format != Scramble.Format) throw RemoteUpdateFormatException.Unrecognized(Format);

        var result = new Dictionary<CategoryId, List<string>>();
        foreach (var (key, value) in Domains)
        {
            var id = CategoryIdExtensions.FromRawValue(key);
            if (id is null) continue;
            result[id.Value] = Format == Scramble.Format
                ? value.Select(Scramble.UnscrambleDomain).Where(d => d.Length > 0).ToList()
                : value;
        }
        return result;
    }
}
