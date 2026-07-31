cmake_minimum_required(VERSION 3.22.1)
project(safeworld_relay C)

# -O2 rather than -O3: this code is dominated by syscalls and memcpy, not arithmetic, and -O3's
# extra inlining only grows the .so for no measurable gain on a packet path.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2")

# Our own classifier. Small, fully tested, and the only part of the native build we wrote.
add_library(safeworld_relay SHARED relay.c)
target_compile_options(safeworld_relay PRIVATE -Wall -Wextra -fvisibility=hidden)

# The vendored forwarder. A separate library on purpose: it is GPL-3.0 third-party code (see
# THIRD_PARTY.md), and giving it its own .so keeps the boundary between what we wrote and what we
# took visible in the build rather than only in a comment.
#
# -Wall is deliberately off here. This is upstream code we do not intend to rewrite, and a wall of
# warnings from files we will never touch only buries the ones from files we will. Visibility stays
# default because the JNI entry points have to be exported.
add_library(safeworld_netguard SHARED
    netguard/netguard.c
    netguard/session.c
    netguard/ip.c
    netguard/tcp.c
    netguard/udp.c
    netguard/icmp.c
    netguard/tls.c
    netguard/dns.c
    netguard/dhcp.c
    netguard/pcap.c
    netguard/util.c)
target_include_directories(safeworld_netguard PRIVATE netguard)

find_library(log-lib log)
target_link_libraries(safeworld_relay ${log-lib})
target_link_libraries(safeworld_netguard ${log-lib})

# Some Android 15 devices use 16 KB pages, and a .so linked for 4 KB will not load on them.
target_link_options(safeworld_relay PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(safeworld_netguard PRIVATE "-Wl,-z,max-page-size=16384")
