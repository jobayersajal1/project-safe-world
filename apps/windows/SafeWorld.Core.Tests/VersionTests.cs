using Xunit;

namespace SafeWorld.Core.Tests;

/// <summary>
/// These cases are duplicated in the Swift and Kotlin ports
/// (<c>apps/ios/SafeWorldCore/Tests/.../VersionTests.swift</c>,
/// <c>apps/android/core/src/test/.../VersionTest.kt</c>). They are the contract between the three
/// update checkers: if one side orders a pair differently, that platform either nags about an
/// update that isn't one or stays silent about one that is.
/// </summary>
public class VersionTests
{
    [Fact]
    public void OrdersByNumericComponents()
    {
        Assert.True(Version.IsNewer("0.2.0", "0.1.0"));
        Assert.True(Version.IsNewer("1.0.0", "0.9.9"));
        Assert.True(Version.IsNewer("0.1.1", "0.1.0"));
        Assert.False(Version.IsNewer("0.1.0", "0.2.0"));
    }

    [Fact]
    public void TheSameVersionIsNotAnUpdate()
    {
        Assert.False(Version.IsNewer("0.1.0", "0.1.0"));
    }

    [Fact]
    public void IgnoresALeadingVSoAGitTagComparesAgainstAnAssemblyVersion()
    {
        Assert.False(Version.IsNewer("v0.1.0", "0.1.0"));
        Assert.True(Version.IsNewer("v0.2.0", "0.1.0"));
    }

    [Fact]
    public void TreatsMissingTrailingComponentsAsZero()
    {
        Assert.False(Version.IsNewer("1.2", "1.2.0"));
        Assert.True(Version.IsNewer("1.3", "1.2.9"));
    }

    [Fact]
    public void ComparesComponentsNumericallyNotAsText()
    {
        // The bug this pins: "10" sorts before "9" as a string.
        Assert.True(Version.IsNewer("0.10.0", "0.9.0"));
        Assert.True(Version.IsNewer("0.1.10", "0.1.9"));
    }

    [Fact]
    public void APreReleasePrecedesTheReleaseItLeadsUpTo()
    {
        Assert.True(Version.IsNewer("1.0.0", "1.0.0-beta"));
        Assert.False(Version.IsNewer("1.0.0-beta", "1.0.0"));
        // Still newer than the previous full release.
        Assert.True(Version.IsNewer("1.0.0-beta", "0.9.0"));
    }

    [Fact]
    public void BuildMetadataDoesNotAffectPrecedence()
    {
        Assert.False(Version.IsNewer("1.0.0+build9", "1.0.0"));
        Assert.False(Version.IsNewer("1.0.0", "1.0.0+build9"));
    }

    [Fact]
    public void MalformedInputReadsAsZeroRatherThanThrowing()
    {
        // A garbage tag must not break the update check, only fail to look new.
        Assert.False(Version.IsNewer("", "0.1.0"));
        Assert.False(Version.IsNewer("not-a-version", "0.1.0"));
        Assert.True(Version.IsNewer("0.1.0", "garbage"));
    }

    /// <summary>
    /// The specific comparison that shipped this release: Windows sat at 0.3.0 while the phones
    /// were six releases ahead, so the first thing the new updater ever has to get right is that
    /// 0.5.0 beats 0.3.0.
    /// </summary>
    [Fact]
    public void TheJumpThisReleaseMakes()
    {
        Assert.True(Version.IsNewer("v0.5.0", "0.3.0"));
        Assert.False(Version.IsNewer("v0.3.0", "0.5.0"));
    }
}
