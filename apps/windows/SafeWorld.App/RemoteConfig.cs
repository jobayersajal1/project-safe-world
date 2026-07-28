namespace SafeWorld.App;

/// <summary>
/// Server-driven blocklist updates. Deliberately <b>not</b> exposed anywhere in the app's UI or
/// editable via <c>Settings</c> — end users can't see or change this endpoint from the app
/// itself; it ships baked into the binary and only changes via an app update. Mirrors
/// <c>RemoteConfig.swift</c> on iOS/macOS and <c>RemoteConfig.kt</c> on Android.
///
/// This hides the endpoint from the UI, not from the world: anyone inspecting the published
/// binary (e.g. `strings SafeWorld.exe`) can still read this string, same as any other
/// client-embedded config. Don't rely on it for secrecy.
/// </summary>
internal static class RemoteConfig
{
    /// <summary>
    /// Published from https://github.com/jobayersajal1/safe-world-block-list-update via GitHub
    /// Pages. Windows fetches the <b>scrambled</b> variant, the same one iOS/macOS/Chrome use:
    /// the hosts-file sinkhole needs literal domain names to write, so it can't use Android's
    /// one-way digests.
    ///
    /// Which bundled list this build shipped with, from `npm run baseline:snapshot`. <b>Update it
    /// whenever the bundled lists change</b>, or the app will fetch a delta computed against a
    /// list it doesn't have and miss whatever fell between the two.
    /// </summary>
    public const string ListBaseline = "3a7b028a5695";

    /// <summary>
    /// Only the domains added <i>since</i> <see cref="ListBaseline"/> — the full list ships
    /// inside the app and is never published. A 404 means no delta exists for this baseline yet,
    /// which degrades to "no new domains".
    ///
    /// Empty disables automatic fetching.
    /// </summary>
    public const string UpdateUrl =
        $"https://jobayersajal1.github.io/safe-world-block-list-update/delta-{ListBaseline}.json";

    /// <summary>Minimum time between automatic fetches.</summary>
    public const double UpdateIntervalHours = 24;
}
