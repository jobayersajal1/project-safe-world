using SafeWorld.Core;
using Xunit;

namespace SafeWorld.Core.Tests;

/// <summary>Mirrors packages/core/test/matcher.test.ts — keep the two in sync.</summary>
public class MatcherTests
{
    private static Dictionary<CategoryId, HashSet<string>> Lists(
        IEnumerable<string>? scam = null,
        IEnumerable<string>? gambling = null,
        IEnumerable<string>? adult = null) => new()
    {
        [CategoryId.Scam] = new HashSet<string>(scam ?? Array.Empty<string>()),
        [CategoryId.Gambling] = new HashSet<string>(gambling ?? new[] { "bet365.com" }),
        [CategoryId.Adult] = new HashSet<string>(adult ?? new[] { "pornhub.com" }),
    };

    [Fact]
    public void NormalizeHost_StripsSchemePathPortWwwAndLowercases()
    {
        Assert.Equal("bet365.com", Matcher.NormalizeHost("HTTPS://WWW.Bet365.com:443/path?x=1"));
    }

    [Fact]
    public void NormalizeHost_StripsUserinfoAndTrailingDot()
    {
        Assert.Equal("sub.example.com", Matcher.NormalizeHost("http://user:pass@sub.example.com./a"));
    }

    [Fact]
    public void NormalizeHost_ReturnsEmptyForBlankInput()
    {
        Assert.Equal("", Matcher.NormalizeHost("   "));
    }

    [Fact]
    public void HostMatchesDomain_MatchesExactAndSubdomainsButNotSiblings()
    {
        Assert.True(Matcher.HostMatchesDomain("bet365.com", "bet365.com"));
        Assert.True(Matcher.HostMatchesDomain("m.bet365.com", "bet365.com"));
        Assert.False(Matcher.HostMatchesDomain("notbet365.com", "bet365.com"));
    }

    [Fact]
    public void Decide_BlocksAListedDomainInAnEnabledCategory()
    {
        var d = Matcher.Decide("www.bet365.com", Settings.Defaults(), Lists());
        Assert.True(d.Blocked);
        Assert.Equal("list2", d.Reason);
    }

    [Fact]
    public void Decide_BlocksSubdomainsOfAListedDomain()
    {
        Assert.True(Matcher.Decide("sports.bet365.com", Settings.Defaults(), Lists()).Blocked);
    }

    [Fact]
    public void Decide_DoesNotBlockWhenMasterSwitchIsOff()
    {
        var s = Settings.Defaults();
        s.Enabled = false;
        Assert.False(Matcher.Decide("bet365.com", s, Lists()).Blocked);
    }

    [Fact]
    public void Decide_DoesNotBlockWhenTheCategoryIsDisabled()
    {
        var s = Settings.Defaults();
        s.Categories[CategoryId.Gambling] = false;
        Assert.False(Matcher.Decide("bet365.com", s, Lists()).Blocked);
    }

    [Fact]
    public void Decide_CustomAllowOverridesACategoryBlock()
    {
        var s = Settings.Defaults();
        s.CustomAllow = new List<string> { "bet365.com" };
        Assert.False(Matcher.Decide("bet365.com", s, Lists()).Blocked);
    }

    [Fact]
    public void Decide_CustomBlockBlocksAnOtherwiseAllowedDomain()
    {
        var s = Settings.Defaults();
        s.CustomBlock = new List<string> { "example.com" };
        var d = Matcher.Decide("app.example.com", s, Lists());
        Assert.True(d.Blocked);
        Assert.Equal("custom", d.Reason);
    }

    [Fact]
    public void Decide_AllowsUnlistedDomains()
    {
        Assert.False(Matcher.Decide("wikipedia.org", Settings.Defaults(), Lists()).Blocked);
    }
}
