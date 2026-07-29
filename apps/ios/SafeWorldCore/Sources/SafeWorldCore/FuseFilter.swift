import Foundation

/// Reader for the binary fuse32 filters produced by `scripts/binary-fuse.ts`.
///
/// Mirrors `FuseFilter.kt`. The blocklist only ever answers "is this host
/// listed?" and never needs the list back, so it is stored as an approximate
/// membership filter: 4.48M domains in ~19 MB rather than ~86 MB of digests.
///
/// **The error is one-directional.** Every domain that went in is *always*
/// found, so no blocked site can slip through. The cost is a false positive at
/// roughly 1 lookup in 4.3 billion.
///
/// ## Why this memory-maps instead of reading into an array
///
/// The Android reader copies the fingerprints into an `IntArray`, which is fine
/// inside a normal app process. On iOS this runs inside a Network Extension,
/// and those are killed for exceeding a hard memory cap far below the size of
/// this data. A heap copy of 19 MB would not survive.
///
/// `mmap` avoids that: the pages are file-backed and clean, so the kernel can
/// evict and re-read them under pressure instead of counting them as dirty
/// memory the process owns. Lookups touch three pages out of millions, so
/// almost none of the file is ever resident.
///
/// Must agree byte for byte with the generator and with `FuseFilter.kt`;
/// `FuseFilterTests` pins the same vector all three share.
public final class FuseFilter {
    private let seed: UInt64
    private let segmentLength: UInt32
    private let segmentLengthMask: UInt32
    private let segmentCountLength: UInt32
    private let arrayLength: UInt32

    /// How many domains went in. The filter itself cannot be counted.
    public let size: Int

    /// The mapped file. Held so the mapping outlives every lookup.
    private let region: UnsafeRawPointer
    private let regionBytes: Int
    /// Start of the fingerprint array inside `region`, already past the header.
    private let fingerprints: UnsafePointer<UInt32>
    private let ownsMapping: Bool

    private static let magic: UInt32 = 0x5357_4631 // "SWF1"
    private static let formatVersion: UInt8 = 2
    private static let headerBytes = 28

    public enum Failure: Error, CustomStringConvertible {
        case unreadable(String)
        case truncated(Int)
        case badMagic(UInt32)
        case unsupportedVersion(UInt8)
        case lengthMismatch(expected: Int, actual: Int)

        public var description: String {
            switch self {
            case .unreadable(let path): return "cannot map filter at \(path)"
            case .truncated(let n): return "filter truncated: \(n) bytes"
            case .badMagic(let m): return String(format: "not a Safe World filter (magic 0x%08x)", m)
            case .unsupportedVersion(let v): return "unsupported filter version \(v)"
            case .lengthMismatch(let e, let a): return "filter length \(a) does not match header (\(e))"
            }
        }
    }

    /// Memory-map a filter file. Throws rather than degrading, for the same
    /// reason `Blocklists` fails closed on an unknown format: a filter that
    /// silently matches nothing looks exactly like a healthy app.
    public convenience init(path: String) throws {
        let fd = open(path, O_RDONLY)
        guard fd >= 0 else { throw Failure.unreadable(path) }
        defer { close(fd) }

        var info = stat()
        guard fstat(fd, &info) == 0, info.st_size > 0 else { throw Failure.unreadable(path) }
        let length = Int(info.st_size)

        guard let raw = mmap(nil, length, PROT_READ, MAP_PRIVATE, fd, 0), raw != MAP_FAILED else {
            throw Failure.unreadable(path)
        }
        // Lookups are three scattered reads, so tell the kernel not to read ahead.
        madvise(raw, length, MADV_RANDOM)

        try self.init(region: UnsafeRawPointer(raw), bytes: length, ownsMapping: true)
    }

    /// How many domains a filter file covers, read from its header alone.
    ///
    /// The home screen shows this number and nothing else about the filter, and
    /// the app runs outside the App Group container the full engine needs. Nine
    /// bytes of header answer it, so don't map fourteen megabytes to ask.
    /// Returns nil for anything that isn't a readable filter of this version —
    /// the caller shows a smaller count, which is better than a wrong one.
    public static func entryCount(atPath path: String) -> Int? {
        guard
            let handle = FileHandle(forReadingAtPath: path),
            let header = try? handle.read(upToCount: headerBytes),
            header.count == headerBytes
        else { return nil }
        defer { try? handle.close() }

        return header.withUnsafeBytes { raw -> Int? in
            let magic = UInt32(bigEndian: raw.loadUnaligned(fromByteOffset: 0, as: UInt32.self))
            guard magic == Self.magic else { return nil }
            guard raw.load(fromByteOffset: 4, as: UInt8.self) == Self.formatVersion else { return nil }
            return Int(UInt32(bigEndian: raw.loadUnaligned(fromByteOffset: 16, as: UInt32.self)))
        }
    }

    /// Parse an in-memory copy. Used by tests; the app maps the file instead.
    public convenience init(data: Data) throws {
        let bytes = data.count
        let buffer = UnsafeMutableRawPointer.allocate(byteCount: max(bytes, 1), alignment: 4)
        data.copyBytes(to: buffer.assumingMemoryBound(to: UInt8.self), count: bytes)
        do {
            try self.init(region: UnsafeRawPointer(buffer), bytes: bytes, ownsMapping: false)
        } catch {
            buffer.deallocate()
            throw error
        }
    }

    private init(region: UnsafeRawPointer, bytes: Int, ownsMapping: Bool) throws {
        guard bytes >= Self.headerBytes else { throw Failure.truncated(bytes) }

        func u32(_ offset: Int) -> UInt32 {
            UInt32(bigEndian: region.loadUnaligned(fromByteOffset: offset, as: UInt32.self))
        }
        func u64(_ offset: Int) -> UInt64 {
            UInt64(bigEndian: region.loadUnaligned(fromByteOffset: offset, as: UInt64.self))
        }

        let magic = u32(0)
        guard magic == Self.magic else { throw Failure.badMagic(magic) }
        let version = region.load(fromByteOffset: 4, as: UInt8.self)
        guard version == Self.formatVersion else { throw Failure.unsupportedVersion(version) }

        let seed = u64(8)
        let size = Int(u32(16))
        let segmentCount = u32(20)
        let arrayLength = u32(24)

        let expected = Self.headerBytes + Int(arrayLength) * 4
        guard bytes == expected else {
            throw Failure.lengthMismatch(expected: expected, actual: bytes)
        }

        let segmentLength = arrayLength / (segmentCount + 2)

        self.region = region
        self.regionBytes = bytes
        self.ownsMapping = ownsMapping
        self.seed = seed
        self.size = size
        self.segmentLength = segmentLength
        self.segmentLengthMask = segmentLength &- 1
        self.segmentCountLength = segmentCount &* segmentLength
        self.arrayLength = arrayLength
        self.fingerprints = region.advanced(by: Self.headerBytes)
            .bindMemory(to: UInt32.self, capacity: Int(arrayLength))
    }

    deinit {
        if ownsMapping {
            munmap(UnsafeMutableRawPointer(mutating: region), regionBytes)
        } else {
            UnsafeMutableRawPointer(mutating: region).deallocate()
        }
    }

    /// True if `key` was in the built set, or is a rare false positive.
    public func contains(_ key: UInt64) -> Bool {
        let hash = Self.mix64(key &+ seed)
        var fp = Self.fingerprint(hash)

        let hi = UInt32(truncatingIfNeeded: hash.multipliedFullWidth(by: UInt64(segmentCountLength)).high)
        let h0 = hi
        let h1 = (h0 &+ segmentLength) ^ (UInt32(truncatingIfNeeded: hash >> 18) & segmentLengthMask)
        let h2 = (h0 &+ 2 &* segmentLength) ^ (UInt32(truncatingIfNeeded: hash) & segmentLengthMask)

        // Guard the mapping: a corrupt header could otherwise index out of the
        // mapped region, which is a crash rather than a wrong answer.
        guard h0 < arrayLength, h1 < arrayLength, h2 < arrayLength else { return false }

        fp ^= UInt32(bigEndian: fingerprints[Int(h0)])
        fp ^= UInt32(bigEndian: fingerprints[Int(h1)])
        fp ^= UInt32(bigEndian: fingerprints[Int(h2)])
        return fp == 0
    }

    /// splitmix64 finalizer — the same mix the generator and Kotlin use.
    private static func mix64(_ value: UInt64) -> UInt64 {
        var z = value &+ 0x9e37_79b9_7f4a_7c15
        z = (z ^ (z >> 30)) &* 0xbf58_476d_1ce4_e5b9
        z = (z ^ (z >> 27)) &* 0x94d0_49bb_1331_11eb
        return z ^ (z >> 31)
    }

    private static func fingerprint(_ hash: UInt64) -> UInt32 {
        UInt32(truncatingIfNeeded: hash ^ (hash >> 32))
    }
}
