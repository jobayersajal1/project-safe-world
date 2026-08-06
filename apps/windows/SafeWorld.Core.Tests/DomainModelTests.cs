using SafeWorld.Core;
using Xunit;

namespace SafeWorld.Core.Tests;

/// <summary>
/// The cross-port contract for the advisory feature map.
/// </summary>
/// <remarks>
/// <para>
/// These numbers were produced by <c>scripts/domain_features.py</c>, which is
/// the definition; <c>packages/core/test/advisory.test.ts</c>,
/// <c>DomainModelTests.swift</c> and <c>DomainModelTest.kt</c> pin the same
/// table. A port that hashes differently, normalises differently, or forgets the
/// L2 step fails here rather than shipping a platform that quietly disagrees
/// about the same domain.
/// </para>
/// <para>
/// The feature vector is pinned rather than the score, deliberately: the
/// hashing, the normalisation, the structural tokens and the L2 step are the
/// fragile parts and this catches all of them, while the score would also drag
/// 341 KB of weights into an assembly that has nothing to score for yet.
/// </para>
/// <para>
/// Every made-up name here is absent from all seven blocklists and the real ones
/// are famous sites or platform hosts, so this table publishes nothing.
/// </para>
/// </remarks>
public class DomainModelTests
{
    /// <summary>
    /// host, non-zero count, sum of indexes, count of negative values.
    /// The negative count is what pins the sign bit. An earlier version of this
    /// table used the sum of absolute values instead, which is worth nothing:
    /// with no collisions every value is ±1/‖v‖, so that sum is just √nnz and
    /// agrees no matter how the hash behaves.
    /// </summary>
    public static TheoryData<string, int, int, int> Pinned() => new()
    {
        { "best-casino-slots-bonus.com", 85, 12_369_141, 47 },
        { "adult-xxx-tube-videos.com", 79, 10_123_884, 36 },
        { "github.com", 34, 4_750_261, 20 },
        { "wikipedia.org", 43, 5_594_899, 17 },
        { "nhs.uk", 22, 2_508_037, 10 },
        { "acme-plumbing-services.com", 82, 11_115_176, 37 },
        { "xn--test-punycode-9za.net", 79, 9_862_878, 41 },
        { "a.b.c.example.co.uk", 61, 7_332_332, 25 },
        { "3.bp.blogspot.com", 55, 7_031_982, 21 },
        { "www.Example.COM", 37, 4_971_527, 16 },
        { "", 0, 0, 0 },
    };

    [Theory]
    [MemberData(nameof(Pinned))]
    public void FeaturesMatchTheOtherPorts(string host, int nnz, int indexSum, int negatives)
    {
        var f = DomainModel.Features(host);
        Assert.Equal(nnz, f.Count);
        Assert.Equal(indexSum, f.Keys.Sum());
        Assert.Equal(negatives, f.Values.Count(v => v < 0));
    }

    [Theory]
    [InlineData("bet365.com")]
    [InlineData("a.b.c.example.co.uk")]
    [InlineData("x.io")]
    public void FeaturesAreL2Normalised(string host)
    {
        var sum = DomainModel.Features(host).Values.Sum(v => v * v);
        Assert.Equal(1.0, sum, 10);
    }

    [Fact]
    public void IndexesStayInsideTheTable()
    {
        foreach (var idx in DomainModel.Features("some-long-hyphenated-name-99.example.co.uk").Keys)
        {
            Assert.InRange(idx, 0, DomainModel.TableSize - 1);
        }
    }

    [Fact]
    public void LeadingWwwAndCaseAreIgnored()
    {
        // Matches NormalizeHost in Matcher, which the model must not diverge
        // from — the same host reaching two different verdicts by way of a
        // prefix would be indefensible.
        Assert.Equal(DomainModel.Features("Example.COM"), DomainModel.Features("www.example.com"));
        Assert.Equal("example.com", DomainModel.Normalize("  WWW.Example.com.  "));
    }

    [Fact]
    public void EmptyHostHasNoFeatures()
    {
        Assert.Empty(DomainModel.Features(""));
        Assert.Empty(DomainModel.Features("   "));
    }

    [Fact]
    public void SharedPlatformsAreNeverScored()
    {
        // The guard exists because the adult model learned *.blogspot.com and
        // then flagged the image host serving every Blogger blog there is.
        Assert.True(DomainModel.IsSharedPlatformHost("1.bp.blogspot.com"));
        Assert.True(DomainModel.IsSharedPlatformHost("3.bp.blogspot.com"));
        Assert.True(DomainModel.IsSharedPlatformHost("videoseriesbiblicas.blogspot.com"));
        Assert.True(DomainModel.IsSharedPlatformHost("someone.github.io"));
        Assert.False(DomainModel.IsSharedPlatformHost("github.com"));
        Assert.False(DomainModel.IsSharedPlatformHost("blogspot.com.evil.example"));
    }

    [Fact]
    public void AdviseHonoursTheGuardAndTheThreshold()
    {
        // A weight table of zeroes scores every host at the bias, so the
        // threshold alone decides — enough to check the plumbing without
        // depending on a generated model being present.
        var always = new DomainModel.Weights(
            CategoryId.Gambling, 1.0, 1.0, 0.0, new sbyte[DomainModel.TableSize]);
        Assert.Equal(CategoryId.Gambling, DomainModel.Advise("anything.example", new[] { always })?.Category);
        Assert.Null(DomainModel.Advise("anyone.blogspot.com", new[] { always }));
        Assert.Null(DomainModel.Advise("", new[] { always }));

        var never = new DomainModel.Weights(
            CategoryId.Gambling, 1.0, 0.0, 1.0, new sbyte[DomainModel.TableSize]);
        Assert.Null(DomainModel.Advise("anything.example", new[] { never }));
    }

    [Fact]
    public void OnlyWarnsUntilBlockingIsAskedFor()
    {
        // The stricter tier must stay inert unless the caller opts in: taking a
        // site away on a guess is the thing this model was not built for.
        var model = new DomainModel.Weights(
            CategoryId.Gambling, 1.0, 1.0, 0.0, new sbyte[DomainModel.TableSize], 0.5);
        Assert.Equal(DomainModel.Action.Warn, DomainModel.Advise("x.example", new[] { model })?.Action);
        Assert.Equal(
            DomainModel.Action.Block,
            DomainModel.Advise("x.example", new[] { model }, allowBlocking: true)?.Action);
    }

    [Fact]
    public void WarnsRatherThanBlocksBetweenTheThresholds()
    {
        var model = new DomainModel.Weights(
            CategoryId.Gambling, 1.0, 0.0, -1.0, new sbyte[DomainModel.TableSize], 1.0);
        Assert.Equal(
            DomainModel.Action.Warn,
            DomainModel.Advise("x.example", new[] { model }, allowBlocking: true)?.Action);
    }

    [Fact]
    public void MissingBlockTierNeverBlocks()
    {
        // The default is infinity, not zero — a model file predating the block
        // tier must not mean "block everything".
        var model = new DomainModel.Weights(
            CategoryId.Gambling, 1.0, 1.0, 0.0, new sbyte[DomainModel.TableSize]);
        Assert.Equal(
            DomainModel.Action.Warn,
            DomainModel.Advise("x.example", new[] { model }, allowBlocking: true)?.Action);
    }
}
