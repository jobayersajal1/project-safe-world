using System.Net;
using System.Net.Sockets;
using SafeWorld.Core;

namespace SafeWorld.App;

/// <summary>
/// A DNS resolver that listens on loopback, answers blocked names locally, and forwards the rest.
///
/// This is what lets Windows block the full uncapped list. The hosts file can only hold so many
/// entries before the DNS Client service slows down, and it needs literal domains; a proxy puts
/// our own code in the path, so it can match against a <see cref="FuseFilter"/> and carry millions
/// of domains in ~19 MB.
///
/// <b>Fails open, never closed.</b> Every failure path here forwards or drops rather than
/// blocking: if the filter can't be read, the upstream is unreachable, or a packet is malformed,
/// the user keeps working DNS. A blocker that breaks the internet when it breaks is worse than one
/// that stops blocking, because the second is recoverable by the person affected.
/// </summary>
public sealed class DnsProxy : IDisposable
{
    /// <summary>Where Windows will be pointed. Loopback only — this is never reachable off-box.</summary>
    public static readonly IPAddress ListenAddress = IPAddress.Loopback;
    public const int Port = 53;

    private readonly FilterEngine _engine;
    private readonly IPEndPoint _upstream;
    private readonly UdpClient _listener;
    private readonly CancellationTokenSource _cancellation = new();
    private Task? _loop;

    /// <summary>Raised when a lookup is blocked, so the UI can count it.</summary>
    public event Action<string>? Blocked;

    public DnsProxy(FilterEngine engine, IPAddress upstream)
    {
        _engine = engine;
        _upstream = new IPEndPoint(upstream, Port);

        // Bound before anything touches the system's DNS settings, so a port conflict surfaces
        // while DNS still works rather than after it has been redirected here.
        _listener = new UdpClient(new IPEndPoint(ListenAddress, Port));
    }

    /// <summary>
    /// True if something else already holds port 53 on loopback — Docker Desktop, Internet
    /// Connection Sharing, or another DNS filter. Checked before redirecting the system.
    /// </summary>
    public static bool PortIsAvailable()
    {
        try
        {
            using var probe = new UdpClient(new IPEndPoint(ListenAddress, Port));
            return true;
        }
        catch (SocketException)
        {
            return false;
        }
    }

    public void Start()
    {
        _loop = Task.Run(() => ServeAsync(_cancellation.Token));
    }

    private async Task ServeAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            UdpReceiveResult request;
            try
            {
                request = await _listener.ReceiveAsync(token);
            }
            catch (OperationCanceledException) { break; }
            catch (SocketException) { continue; }

            // Each query is handled independently; one slow upstream must not stall the rest.
            _ = Task.Run(() => HandleAsync(request, token), token);
        }
    }

    private async Task HandleAsync(UdpReceiveResult request, CancellationToken token)
    {
        try
        {
            var name = DnsMessage.QuestionName(request.Buffer);
            // The list first, then the model's second opinion for a name no list
            // covers. IsBlocked stays the whole of Matcher.Decide; the advisory
            // is a separate call so that equivalence keeps holding.
            if (name is not null && (_engine.IsBlocked(name) || _engine.AdvisoryBlocks(name)))
            {
                var nx = DnsMessage.NxDomainResponse(request.Buffer);
                if (nx is not null)
                {
                    await _listener.SendAsync(nx, nx.Length, request.RemoteEndPoint);
                    Blocked?.Invoke(name);
                    return;
                }
            }

            await ForwardAsync(request, token);
        }
        catch (Exception)
        {
            // Never let one bad query take the proxy down: the whole machine's DNS depends on this
            // loop staying alive.
        }
    }

    private async Task ForwardAsync(UdpReceiveResult request, CancellationToken token)
    {
        using var upstream = new UdpClient();
        upstream.Client.ReceiveTimeout = 4000;
        await upstream.SendAsync(request.Buffer, request.Buffer.Length, _upstream);

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeout.CancelAfter(TimeSpan.FromSeconds(4));
        try
        {
            var answer = await upstream.ReceiveAsync(timeout.Token);
            await _listener.SendAsync(answer.Buffer, answer.Buffer.Length, request.RemoteEndPoint);
        }
        catch (OperationCanceledException)
        {
            // Upstream did not answer. Staying silent lets the client retry or fall back to its
            // secondary resolver, which is the behaviour it already expects from a flaky network.
        }
    }

    public void Dispose()
    {
        _cancellation.Cancel();
        try { _loop?.Wait(TimeSpan.FromSeconds(2)); } catch (Exception) { /* shutting down */ }
        _listener.Dispose();
        _cancellation.Dispose();
    }
}
