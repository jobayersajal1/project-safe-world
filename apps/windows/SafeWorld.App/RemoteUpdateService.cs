using System.Text.Json;
using SafeWorld.Core;

namespace SafeWorld.App;

internal sealed class RemoteUpdateException : Exception
{
    public RemoteUpdateException(string message) : base(message)
    {
    }
}

/// <summary>Fetches a <see cref="RemoteUpdatePayload"/> from the configured URL, the Windows analogue of <c>runRemoteUpdate</c> in the Chrome extension's background.ts.</summary>
internal static class RemoteUpdateService
{
    private static readonly HttpClient Client = new() { Timeout = TimeSpan.FromSeconds(15) };

    public static async Task<RemoteUpdatePayload> FetchAsync(string url)
    {
        if (string.IsNullOrEmpty(url)) throw new RemoteUpdateException("No remote update URL configured.");

        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.CacheControl = new System.Net.Http.Headers.CacheControlHeaderValue { NoCache = true };
        using var response = await Client.SendAsync(request);
        if (!response.IsSuccessStatusCode)
        {
            throw new RemoteUpdateException($"HTTP {(int)response.StatusCode}");
        }

        await using var stream = await response.Content.ReadAsStreamAsync();
        var payload = await JsonSerializer.DeserializeAsync<RemoteUpdatePayload>(stream, new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true,
        });
        return payload ?? throw new RemoteUpdateException("Empty response body.");
    }
}
