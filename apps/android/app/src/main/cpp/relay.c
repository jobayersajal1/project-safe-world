// Native packet relay for the full-tunnel mode.
//
// WHY THIS IS NATIVE
// ------------------
// Today the tunnel routes only DNS, so the JVM handles maybe a hundred bytes per lookup. Full-tunnel
// mode routes 0.0.0.0/0, which means every packet the phone sends or receives crosses this code. At
// that volume a Kotlin loop costs too much CPU and battery, and every allocation it makes is a GC
// pause in the middle of somebody's video call. The established implementations of this pattern
// (NetGuard, Blokada) are native for exactly that reason.
//
// WHAT IT DOES, AND DELIBERATELY DOES NOT DO
// ------------------------------------------
// This stage handles the half of the problem that is genuinely simple: reading packets, classifying
// them, and dropping the ones belonging to blocked flows. Forwarding the rest needs a userspace TCP
// implementation — handshake, sequence numbers, windows, retransmission, teardown — and that is the
// part worth vendoring from a proven stack rather than writing here.
//
// So `relay_classify` is complete and testable, and the forwarding path is not yet wired. Until it
// is, full-tunnel mode must stay off: routing 0.0.0.0/0 without a forwarder would drop *everything*,
// which is a phone with no internet rather than a phone with YouTube blocked.

#include <jni.h>
#include <string.h>
#include <stdint.h>

#define IPV4_VERSION 4
#define IPV4_MIN_HEADER 20
#define IPV6_VERSION 6
#define IPV6_HEADER 40

#define PROTO_TCP 6
#define PROTO_UDP 17

// Mirrors SafeWorldRelay.Verdict on the Kotlin side. Kept as plain ints so the JNI boundary carries
// no objects — this is called once per packet.
#define VERDICT_MALFORMED 0
#define VERDICT_DNS       1
#define VERDICT_PASS      2

typedef struct {
    uint8_t version;
    uint8_t protocol;
    uint32_t source_address;
    uint32_t destination_address;
    uint16_t source_port;
    uint16_t destination_port;
} flow_t;

static uint32_t read_be32(const uint8_t *p) {
    return ((uint32_t) p[0] << 24) | ((uint32_t) p[1] << 16) | ((uint32_t) p[2] << 8) | p[3];
}

static uint16_t read_be16(const uint8_t *p) {
    return (uint16_t) (((uint16_t) p[0] << 8) | p[1]);
}

/**
 * Pulls the 5-tuple out of an IPv4 packet.
 *
 * Returns 0 when the packet cannot be trusted: too short for its own header, a header length that
 * points past the end, or a truncated transport header. Every one of those is a packet we must not
 * act on — misreading a length here would mean reading whatever happens to be after it in the
 * buffer and classifying traffic on that basis.
 *
 * IPv6 is recognised but not parsed. It matters that it is *recognised*: if full-tunnel mode ever
 * routes ::/0 while this returns "malformed" for v6, every v6 packet would be silently dropped, so
 * the caller needs to be able to tell "not IPv4" from "broken".
 */
static int parse_flow(const uint8_t *packet, int length, flow_t *out) {
    if (length < 1) return 0;

    uint8_t version = (uint8_t) (packet[0] >> 4);
    memset(out, 0, sizeof(*out));
    out->version = version;

    if (version == IPV6_VERSION) {
        // Enough to identify it; the extension-header walk belongs with the forwarder.
        if (length < IPV6_HEADER) return 0;
        out->protocol = packet[6];
        return 1;
    }

    if (version != IPV4_VERSION || length < IPV4_MIN_HEADER) return 0;

    int header_length = (packet[0] & 0x0F) * 4;
    // A header shorter than the minimum is malformed; one longer than the packet would send the
    // port reads off the end of the buffer.
    if (header_length < IPV4_MIN_HEADER || header_length > length) return 0;

    out->protocol = packet[9];
    out->source_address = read_be32(packet + 12);
    out->destination_address = read_be32(packet + 16);

    if (out->protocol == PROTO_TCP || out->protocol == PROTO_UDP) {
        // Ports live in the first four bytes of both TCP and UDP headers.
        if (length < header_length + 4) return 0;
        out->source_port = read_be16(packet + header_length);
        out->destination_port = read_be16(packet + header_length + 2);
    }

    return 1;
}

/**
 * Classifies one packet.
 *
 * Deliberately returns a verdict rather than acting: what to *do* about a blocked app depends on
 * settings that live in Kotlin, and this boundary is easier to test than a callback into the JVM
 * per packet would be.
 */
JNIEXPORT jint JNICALL
Java_com_safeworld_app_vpn_SafeWorldRelay_nativeClassify(
        JNIEnv *env, jclass clazz, jbyteArray packet, jint length) {
    (void) clazz;

    if (length <= 0) return VERDICT_MALFORMED;

    jbyte *bytes = (*env)->GetPrimitiveArrayCritical(env, packet, NULL);
    if (bytes == NULL) return VERDICT_MALFORMED;

    flow_t flow;
    int ok = parse_flow((const uint8_t *) bytes, length, &flow);

    // Released before any further work: a critical region blocks GC, so nothing that could take
    // time may happen while it is held.
    (*env)->ReleasePrimitiveArrayCritical(env, packet, bytes, JNI_ABORT);

    if (!ok) return VERDICT_MALFORMED;
    if (flow.version == IPV4_VERSION && flow.protocol == PROTO_UDP && flow.destination_port == 53) {
        return VERDICT_DNS;
    }
    return VERDICT_PASS;
}

/** Destination address of an IPv4 packet, or 0 when it cannot be read. For the drop-set check. */
JNIEXPORT jint JNICALL
Java_com_safeworld_app_vpn_SafeWorldRelay_nativeDestination(
        JNIEnv *env, jclass clazz, jbyteArray packet, jint length) {
    (void) clazz;

    if (length <= 0) return 0;

    jbyte *bytes = (*env)->GetPrimitiveArrayCritical(env, packet, NULL);
    if (bytes == NULL) return 0;

    flow_t flow;
    int ok = parse_flow((const uint8_t *) bytes, length, &flow);
    (*env)->ReleasePrimitiveArrayCritical(env, packet, bytes, JNI_ABORT);

    return ok ? (jint) flow.destination_address : 0;
}

/** Proves the library loaded and the ABI matches. Called once at startup. */
JNIEXPORT jint JNICALL
Java_com_safeworld_app_vpn_SafeWorldRelay_nativeAbiCheck(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    // sizeof checks that would fail loudly on an unexpected ABI rather than corrupting reads.
    return (sizeof(uint32_t) == 4 && sizeof(uint16_t) == 2) ? 1 : 0;
}
