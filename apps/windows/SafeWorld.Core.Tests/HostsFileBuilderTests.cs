using SafeWorld.Core;
using Xunit;

namespace SafeWorld.Core.Tests;

public class HostsFileBuilderTests
{
    [Fact]
    public void DisabledSettings_ProduceNoHosts()
    {
        var s = Settings.Defaults();
        s.Enabled = false;
        var hosts = HostsFileBuilder.BlockedHosts(s, new Dictionary<CategoryId, List<string>>
        {
            [CategoryId.Gambling] = new() { "bet365.com" },
        });
        Assert.Empty(hosts);
    }

    [Fact]
    public void EmitsBareAndWwwVariants_ForEnabledCategoryDomains()
    {
        var s = Settings.Defaults();
        var hosts = HostsFileBuilder.BlockedHosts(s, new Dictionary<CategoryId, List<string>>
        {
            [CategoryId.Gambling] = new() { "bet365.com" },
        });
        Assert.Equal(new[] { "bet365.com", "www.bet365.com" }, hosts);
    }

    [Fact]
    public void CustomAllow_OverridesCategoryBlock()
    {
        var s = Settings.Defaults();
        s.CustomAllow = new List<string> { "bet365.com" };
        var hosts = HostsFileBuilder.BlockedHosts(s, new Dictionary<CategoryId, List<string>>
        {
            [CategoryId.Gambling] = new() { "bet365.com" },
        });
        Assert.Empty(hosts);
    }

    [Fact]
    public void CustomBlock_IsIncluded()
    {
        var s = Settings.Defaults();
        s.CustomBlock = new List<string> { "evil.example" };
        var hosts = HostsFileBuilder.BlockedHosts(s, new Dictionary<CategoryId, List<string>>());
        Assert.Equal(new[] { "evil.example", "www.evil.example" }, hosts);
    }

    [Fact]
    public void DisabledCategory_ContributesNoHosts()
    {
        var s = Settings.Defaults();
        s.Categories[CategoryId.Gambling] = false;
        var hosts = HostsFileBuilder.BlockedHosts(s, new Dictionary<CategoryId, List<string>>
        {
            [CategoryId.Gambling] = new() { "bet365.com" },
        });
        Assert.Empty(hosts);
    }

    [Fact]
    public void RenderBlock_IsMarkerDelimitedAndSorted()
    {
        var s = Settings.Defaults();
        var block = HostsFileBuilder.RenderBlock(s, new Dictionary<CategoryId, List<string>>
        {
            [CategoryId.Gambling] = new() { "bet365.com" },
        });
        Assert.StartsWith("# BEGIN SAFEWORLD\n", block);
        Assert.EndsWith("# END SAFEWORLD\n", block);
        Assert.Contains("0.0.0.0 bet365.com", block);
        Assert.Contains("0.0.0.0 www.bet365.com", block);
    }

    [Fact]
    public void Apply_AppendsBlockWhenNoMarkersPresent()
    {
        var existing = "127.0.0.1 localhost\n";
        var block = "# BEGIN SAFEWORLD\n0.0.0.0 bet365.com\n# END SAFEWORLD\n";
        var result = HostsFileBuilder.Apply(block, existing);
        Assert.Equal(existing + block, result);
    }

    [Fact]
    public void Apply_ReplacesExistingManagedBlockInPlace()
    {
        var existing = "127.0.0.1 localhost\n# BEGIN SAFEWORLD\n0.0.0.0 old.example\n# END SAFEWORLD\n255.255.255.255 broadcasthost\n";
        var block = "# BEGIN SAFEWORLD\n0.0.0.0 new.example\n# END SAFEWORLD\n";
        var result = HostsFileBuilder.Apply(block, existing);
        Assert.Equal("127.0.0.1 localhost\n# BEGIN SAFEWORLD\n0.0.0.0 new.example\n# END SAFEWORLD\n255.255.255.255 broadcasthost\n", result);
    }
}
