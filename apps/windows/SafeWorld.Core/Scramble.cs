using System.Text;

namespace SafeWorld.Core;

/// <summary>
/// Mirrors <c>packages/core/src/scramble.ts</c>. Keep the key, algorithm, and format string in
/// sync by hand — <c>ScrambleTests</c> pins shared vectors so a drift fails loudly instead of
/// silently matching nothing.
///
/// <b>Obfuscation, not encryption.</b> The key ships inside the app, so anyone who unpacks it can
/// reverse this, and the unscrambled domains are written into the hosts file on the machine
/// regardless. What it buys: the file at the public URL isn't a readable list of gambling and
/// adult sites.
///
/// Windows needs the reversible form (unlike Android, which matches one-way digests) because the
/// hosts-file sinkhole needs literal domain names to write.
/// </summary>
public static class Scramble
{
    /// <summary>Payload <c>format</c> for scrambled domains.</summary>
    public const string Format = "xor-b64-v1";

    /// <summary>Payload <c>format</c> for Android's one-way digests, which are not domains.</summary>
    public const string HashedFormat = "sha256-128-hex";

    private static readonly byte[] Key = Encoding.UTF8.GetBytes("safe-world-list-v1");

    private static byte[] Xor(byte[] bytes)
    {
        var result = new byte[bytes.Length];
        for (var i = 0; i < bytes.Length; i++) result[i] = (byte)(bytes[i] ^ Key[i % Key.Length]);
        return result;
    }

    public static string ScrambleDomain(string domain)
    {
        var trimmed = domain.Trim().ToLowerInvariant();
        if (trimmed.Length == 0) return "";
        return Convert.ToBase64String(Xor(Encoding.UTF8.GetBytes(trimmed)));
    }

    /// <summary>Returns "" for input that isn't valid base64, so one bad entry can't take down a whole fetched list.</summary>
    public static string UnscrambleDomain(string scrambled)
    {
        if (scrambled.Length == 0) return "";
        byte[] data;
        try
        {
            data = Convert.FromBase64String(scrambled);
        }
        catch (FormatException)
        {
            return "";
        }
        return Encoding.UTF8.GetString(Xor(data));
    }
}
