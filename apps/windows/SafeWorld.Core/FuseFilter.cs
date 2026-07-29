using System.Buffers.Binary;
using System.IO.MemoryMappedFiles;

namespace SafeWorld.Core;

/// <summary>
/// Reader for the binary fuse32 filters produced by <c>scripts/binary-fuse.ts</c>.
///
/// Mirrors <c>FuseFilter.kt</c> and <c>FuseFilter.swift</c>. The blocklist only ever answers
/// "is this host listed?" and never needs the list back, so it is stored as an approximate
/// membership filter: 4.48M domains in ~19 MB rather than ~86 MB of digests.
///
/// <b>The error is one-directional.</b> Every domain that went in is <i>always</i> found, so no
/// blocked site can slip through. The cost is a false positive at roughly 1 lookup in 4.3 billion.
///
/// Memory-mapped rather than read into an array. Desktop Windows has no tight memory budget, so
/// this is less critical than on iOS — but a lookup touches three pages out of millions, so there
/// is no reason to fault in 19 MB and hold it on the heap for the life of the process.
///
/// Must agree byte for byte with the generator and the other two readers; <c>FuseFilterTests</c>
/// pins the same vector all four share.
/// </summary>
public sealed class FuseFilter : IDisposable
{
    private const uint Magic = 0x53574631; // "SWF1"
    private const byte FormatVersion = 2;
    private const int HeaderBytes = 28;

    private readonly MemoryMappedFile? _file;
    private readonly MemoryMappedViewAccessor? _view;
    private readonly byte[]? _buffer;

    private readonly ulong _seed;
    private readonly uint _segmentLength;
    private readonly uint _segmentLengthMask;
    private readonly uint _segmentCountLength;
    private readonly uint _arrayLength;

    /// <summary>How many domains went in. The filter itself cannot be counted.</summary>
    public int Size { get; }

    private FuseFilter(
        MemoryMappedFile? file,
        MemoryMappedViewAccessor? view,
        byte[]? buffer,
        ulong seed,
        uint segmentLength,
        uint segmentCountLength,
        uint arrayLength,
        int size)
    {
        _file = file;
        _view = view;
        _buffer = buffer;
        _seed = seed;
        _segmentLength = segmentLength;
        _segmentLengthMask = segmentLength - 1;
        _segmentCountLength = segmentCountLength;
        _arrayLength = arrayLength;
        Size = size;
    }

    /// <summary>
    /// Memory-map a filter file. Throws rather than degrading, for the same reason
    /// <see cref="Blocklists"/> fails closed on an unknown format: a filter that silently matches
    /// nothing looks exactly like a healthy app.
    /// </summary>
    public static FuseFilter Open(string path)
    {
        var length = new FileInfo(path).Length;
        if (length < HeaderBytes) throw new InvalidDataException($"filter truncated: {length} bytes");

        var file = MemoryMappedFile.CreateFromFile(path, FileMode.Open, null, 0, MemoryMappedFileAccess.Read);
        var view = file.CreateViewAccessor(0, 0, MemoryMappedFileAccess.Read);

        var header = new byte[HeaderBytes];
        view.ReadArray(0, header, 0, HeaderBytes);
        var (seed, size, segmentCount, arrayLength) = ParseHeader(header, length);
        var segmentLength = arrayLength / (segmentCount + 2);

        return new FuseFilter(file, view, null, seed, segmentLength, segmentCount * segmentLength, arrayLength, size);
    }

    /// <summary>Parse an in-memory copy. Used by tests; the app maps the file instead.</summary>
    public static FuseFilter Parse(byte[] bytes)
    {
        if (bytes.Length < HeaderBytes) throw new InvalidDataException($"filter truncated: {bytes.Length} bytes");
        var (seed, size, segmentCount, arrayLength) = ParseHeader(bytes, bytes.Length);
        var segmentLength = arrayLength / (segmentCount + 2);
        return new FuseFilter(null, null, bytes, seed, segmentLength, segmentCount * segmentLength, arrayLength, size);
    }

    private static (ulong Seed, int Size, uint SegmentCount, uint ArrayLength) ParseHeader(byte[] header, long totalLength)
    {
        var magic = BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(0, 4));
        if (magic != Magic) throw new InvalidDataException($"not a Safe World filter (magic 0x{magic:x8})");
        if (header[4] != FormatVersion) throw new InvalidDataException($"unsupported filter version {header[4]}");

        var seed = BinaryPrimitives.ReadUInt64BigEndian(header.AsSpan(8, 8));
        var size = (int)BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(16, 4));
        var segmentCount = BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(20, 4));
        var arrayLength = BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(24, 4));

        var expected = HeaderBytes + (long)arrayLength * 4;
        if (totalLength != expected)
        {
            throw new InvalidDataException($"filter length {totalLength} does not match header ({expected})");
        }
        return (seed, size, segmentCount, arrayLength);
    }

    private uint Fingerprint(uint index)
    {
        var offset = HeaderBytes + (long)index * 4;
        if (_view is not null)
        {
            // ReadUInt32 is little-endian on x64; the file is big-endian.
            return BinaryPrimitives.ReverseEndianness(_view.ReadUInt32(offset));
        }
        return BinaryPrimitives.ReadUInt32BigEndian(_buffer.AsSpan((int)offset, 4));
    }

    /// <summary>True if <paramref name="key"/> was in the built set, or is a rare false positive.</summary>
    public bool Contains(ulong key)
    {
        var hash = Mix64(key + _seed);
        var fp = (uint)(hash ^ (hash >> 32));

        var hi = (uint)Math.BigMul(hash, _segmentCountLength, out _);
        var h0 = hi;
        var h1 = (h0 + _segmentLength) ^ ((uint)(hash >> 18) & _segmentLengthMask);
        var h2 = (h0 + 2 * _segmentLength) ^ ((uint)hash & _segmentLengthMask);

        // Guard the mapping: a corrupt header could otherwise read past the view.
        if (h0 >= _arrayLength || h1 >= _arrayLength || h2 >= _arrayLength) return false;

        fp ^= Fingerprint(h0) ^ Fingerprint(h1) ^ Fingerprint(h2);
        return fp == 0;
    }

    /// <summary>splitmix64 finalizer — the same mix the generator and the other readers use.</summary>
    private static ulong Mix64(ulong value)
    {
        var z = value + 0x9e3779b97f4a7c15UL;
        z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9UL;
        z = (z ^ (z >> 27)) * 0x94d049bb133111ebUL;
        return z ^ (z >> 31);
    }

    public void Dispose()
    {
        _view?.Dispose();
        _file?.Dispose();
    }
}
