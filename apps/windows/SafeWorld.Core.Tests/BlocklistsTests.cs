using SafeWorld.Core;
using Xunit;

namespace SafeWorld.Core.Tests;

public class BlocklistsTests
{
    [Fact]
    public void BundledDomains_LoadForEveryCategory()
    {
        foreach (var id in Enum.GetValues<CategoryId>())
        {
            Assert.NotEmpty(Blocklists.Domains(id));
        }
    }
}
