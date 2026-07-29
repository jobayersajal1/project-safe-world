using Xunit;
using SafeWorld.Core;

namespace SafeWorld.Core.Tests;

/// <summary>
/// Pins the filter format against the TypeScript generator and the Kotlin and Swift readers,
/// using the <i>same</i> vector all four share.
///
/// A drift between them does not crash — it produces a filter that matches nothing, so the app
/// installs, runs, reports a healthy count, and blocks nothing at all.
/// </summary>
public class FuseFilterTests
{
    /// <summary>Built by <c>scripts/binary-fuse.ts</c> over exactly <see cref="InSet"/>.</summary>
    private const string Vector =
        "U1dGMQIDAAByayudQ4udTQAAAAUAAAABAAAAGAAAAAAAAAAA3DngrQAAAADitA3cAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFAQi1kAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAXZa79wAAAACjFf1+AAAAAA==";

    private static readonly string[] InSet =
    [
        "bet365.com", "pornhub.com", "facebook.com", "example.org", "a.b.c.example.net"
    ];

    private static FuseFilter Filter() => FuseFilter.Parse(Convert.FromBase64String(Vector));

    [Fact]
    public void GeneratorsFilterParsesHere()
    {
        using var filter = Filter();
        Assert.Equal(InSet.Length, filter.Size);
    }

    [Fact]
    public void EveryDomainThatWentInIsFound()
    {
        // False negatives are the failure this structure must never have: a blocked site slipping
        // through. They are impossible unless the implementations disagree.
        using var filter = Filter();
        foreach (var domain in InSet)
        {
            Assert.True(filter.Contains(DomainHasher.Key(domain)), $"{domain} must be found");
        }
    }

    [Fact]
    public void DomainsNeverAddedAreNotFound()
    {
        using var filter = Filter();
        foreach (var domain in new[] { "wikipedia.org", "github.com", "google.com" })
        {
            Assert.False(filter.Contains(DomainHasher.Key(domain)), $"{domain} must not match");
        }
    }

    [Fact]
    public void KeyDerivationAgreesWithTheGenerator()
    {
        // Pinned from the generator. If this drifts, every shipped filter stops matching with no
        // error raised anywhere.
        Assert.Equal(0xd658ebf1a97d66b1UL, DomainHasher.Key("bet365.com"));
        Assert.Equal(0x0291a62d8d8766ecUL, DomainHasher.Key("pornhub.com"));
    }

    [Fact]
    public void CorruptOrForeignFiltersAreRejected()
    {
        var bytes = Convert.FromBase64String(Vector);

        var wrongMagic = (byte[])bytes.Clone();
        wrongMagic[0] = 0;
        Assert.Throws<InvalidDataException>(() => FuseFilter.Parse(wrongMagic));

        Assert.Throws<InvalidDataException>(() => FuseFilter.Parse(bytes[..(bytes.Length - 4)]));
        Assert.Throws<InvalidDataException>(() => FuseFilter.Parse(new byte[4]));

        var wrongVersion = (byte[])bytes.Clone();
        wrongVersion[4] = 99;
        Assert.Throws<InvalidDataException>(() => FuseFilter.Parse(wrongVersion));
    }

    [Fact]
    public void CandidatesStopBeforeTheBareTld()
    {
        Assert.Equal(
            new List<string> { "a.b.example.com", "b.example.com", "example.com" },
            DomainHasher.Candidates("a.b.example.com"));
        Assert.Equal(new List<string> { "example.com" }, DomainHasher.Candidates("example.com"));
        Assert.Equal(new List<string> { "bet365.com" }, DomainHasher.Candidates("www.bet365.com"));
        Assert.Empty(DomainHasher.Candidates(""));

        // No blocklist can contain "com", so looking one up can only be wrong — and against a
        // probabilistic filter a single collision there would block every .com domain.
        foreach (var host in new[] { "example.com", "a.b.c.co.uk", "deep.sub.domain.org" })
        {
            Assert.All(DomainHasher.Candidates(host), c => Assert.Contains('.', c));
        }
    }

    [Fact]
    public void MappingAFileGivesTheSameAnswers()
    {
        var path = Path.Combine(Path.GetTempPath(), $"fuse-{Guid.NewGuid()}.filter");
        File.WriteAllBytes(path, Convert.FromBase64String(Vector));
        try
        {
            using var mapped = FuseFilter.Open(path);
            Assert.Equal(InSet.Length, mapped.Size);
            foreach (var domain in InSet) Assert.True(mapped.Contains(DomainHasher.Key(domain)));
            Assert.False(mapped.Contains(DomainHasher.Key("wikipedia.org")));
        }
        finally { File.Delete(path); }
    }
}
