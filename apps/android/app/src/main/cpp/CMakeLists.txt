cmake_minimum_required(VERSION 3.22.1)
project(safeworld_relay C)

# -O2 rather than -O3: this code is dominated by syscalls and memcpy, not arithmetic, and -O3's
# extra inlining only grows the .so for no measurable gain on a packet path.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wall -Wextra -fvisibility=hidden")

add_library(safeworld_relay SHARED relay.c)
find_library(log-lib log)
target_link_libraries(safeworld_relay ${log-lib})
