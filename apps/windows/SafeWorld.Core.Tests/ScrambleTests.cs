using SafeWorld.Core;
using Xunit;

namespace SafeWorld.Core.Tests;

public class ScrambleTests
{
    /// <summary>
    /// Pinned values produced by packages/core/src/scramble.ts. This is the contract between the
    /// TS generator and this C# decoder. If either side changes its key, encoding, or
    /// normalization these fail — without them the drift would be silent, and the app would fetch
    /// a list it decodes to garbage and blocks nothing with.
    /// </summary>
    private static readonly (string Plain, string Scrambled)[] Vectors =
    {
        ("bet365.com", "EQQSVhtCQREDCQ=="),
        ("pornhub.com", "Aw4UC0UCDVwPC0A="),
        ("example.com", "FhkHCF0bClwPC0A="),
    };

    [Fact]
    public void Scramble_MatchesTheGenerator()
    {
        foreach (var (plain, scrambled) in Vectors)
        {
            Assert.Equal(scrambled, Core.Scramble.ScrambleDomain(plain));
        }
    }

    [Fact]
    public void Unscramble_RecoversTheDomain()
    {
        foreach (var (plain, scrambled) in Vectors)
        {
            Assert.Equal(plain, Core.Scramble.UnscrambleDomain(scrambled));
        }
    }

    [Fact]
    public void ScrambledForm_IsNotReadable()
    {
        Assert.DoesNotContain("bet365", Core.Scramble.ScrambleDomain("bet365.com"));
    }

    [Fact]
    public void EmptyAndInvalidInput_DoNotThrowOrCorrupt()
    {
        Assert.Equal("", Core.Scramble.ScrambleDomain(""));
        Assert.Equal("", Core.Scramble.UnscrambleDomain(""));
        Assert.Equal("", Core.Scramble.UnscrambleDomain("!!! not base64 !!!"));
    }

    [Fact]
    public void Payload_UnscramblesScrambledDomains()
    {
        var payload = new RemoteUpdatePayload
        {
            Version = 1,
            Format = Core.Scramble.Format,
            Domains = new() { ["list2"] = new() { "EQQSVhtCQREDCQ==" } },
        };
        var result = payload.DomainsByCategory();
        Assert.Equal(new List<string> { "bet365.com" }, result[CategoryId.Gambling]);
    }

    [Fact]
    public void Payload_WithoutFormat_IsTreatedAsPlaintext()
    {
        var payload = new RemoteUpdatePayload
        {
            Version = 1,
            Domains = new() { ["list2"] = new() { "bet365.com" } },
        };
        var result = payload.DomainsByCategory();
        Assert.Equal(new List<string> { "bet365.com" }, result[CategoryId.Gambling]);
    }

    [Fact]
    public void Payload_RejectsAndroidsHashedList()
    {
        var payload = new RemoteUpdatePayload
        {
            Version = 1,
            Format = Core.Scramble.HashedFormat,
            Domains = new() { ["list2"] = new() { "d658ebf1a97d66b19cc925ece45b6255" } },
        };
        Assert.Throws<RemoteUpdateFormatException>(() => payload.DomainsByCategory());
    }

    [Fact]
    public void Payload_RejectsAnUnknownFormat()
    {
        var payload = new RemoteUpdatePayload { Version = 1, Format = "rot13-v9" };
        Assert.Throws<RemoteUpdateFormatException>(() => payload.DomainsByCategory());
    }

    [Fact]
    public void UnrecognizedCategoryKeys_AreDropped()
    {
        var payload = new RemoteUpdatePayload
        {
            Version = 1,
            Format = Core.Scramble.Format,
            Domains = new()
            {
                ["list2"] = new() { "EQQSVhtCQREDCQ==" },
                ["not-a-category"] = new() { "FhkHCF0bClwPC0A=" },
            },
        };
        var result = payload.DomainsByCategory();
        Assert.Single(result);
        Assert.Equal(new List<string> { "bet365.com" }, result[CategoryId.Gambling]);
    }
}
