using System.Buffers.Binary;
using System.Text;

namespace SafeWorld.Core;

/// <summary>
/// Just enough DNS wire-format handling for the sinkhole: read the queried name out of a request,
/// and synthesize an NXDOMAIN answer for names we block.
///
/// A direct port of <c>DnsMessage.kt</c> / <c>DnsMessage.swift</c>. Unlike Android and iOS, which
/// read raw IP packets off a tunnel, Windows receives the DNS payload directly from a UDP socket —
/// so there is no IPv4/UDP layer to port here at all.
///
/// NXDOMAIN is used rather than answering 0.0.0.0 so the client fails fast with "host not found"
/// instead of hanging on a connection to a dead address.
/// </summary>
public static class DnsMessage
{
    private const int HeaderSize = 12;
    private const int QuestionTail = 4; // QTYPE + QCLASS
    private const int MaxLabelLength = 63;
    private const int MaxNameLength = 255;

    /// <summary>The queried name (no trailing dot), or null if this isn't a single-question standard query.</summary>
    public static string? QuestionName(ReadOnlySpan<byte> payload)
        => TryParseQuestion(payload, out var name, out _) ? name : null;

    /// <summary>An NXDOMAIN response echoing the query's id and question section, or null if unparseable.</summary>
    public static byte[]? NxDomainResponse(ReadOnlySpan<byte> query)
    {
        if (!TryParseQuestion(query, out _, out var end)) return null;

        var response = query[..end].ToArray();

        // QR=1 (response), preserve OPCODE and RD from the query.
        var flags0 = query[2];
        response[2] = (byte)(0x80 | (flags0 & 0x78) | (flags0 & 0x01));
        // RA=1 (recursion available), RCODE=3 (NXDOMAIN).
        response[3] = 0x83;

        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(4), 1); // QDCOUNT
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(6), 0); // ANCOUNT
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(8), 0); // NSCOUNT
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(10), 0); // ARCOUNT
        return response;
    }

    private static bool TryParseQuestion(ReadOnlySpan<byte> payload, out string name, out int endOffset)
    {
        name = "";
        endOffset = 0;
        if (payload.Length < HeaderSize) return false;
        // Must be a query (QR=0), standard opcode, with exactly one question.
        if ((payload[2] & 0x80) != 0) return false;
        if (((payload[2] >> 3) & 0x0F) != 0) return false;
        if (BinaryPrimitives.ReadUInt16BigEndian(payload[4..]) != 1) return false;

        var builder = new StringBuilder();
        var offset = HeaderSize;
        var nameLength = 0;

        while (true)
        {
            if (offset >= payload.Length) return false;
            int length = payload[offset];
            // A compression pointer is not legal in the question section, and following one would
            // risk a parsing loop.
            if ((length & 0xC0) != 0) return false;
            offset++;
            if (length == 0) break;
            if (length > MaxLabelLength) return false;
            if (offset + length > payload.Length) return false;

            nameLength += length + 1;
            if (nameLength > MaxNameLength) return false;

            if (builder.Length > 0) builder.Append('.');
            builder.Append(Encoding.ASCII.GetString(payload.Slice(offset, length)));
            offset += length;
        }

        var end = offset + QuestionTail;
        if (end > payload.Length) return false;

        name = builder.ToString();
        endOffset = end;
        return true;
    }
}
