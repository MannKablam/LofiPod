# CMakeLists.txt for the lofipod_fft native library (PFFFT JNI bridge).
#
# Consumes marton78/pffft as a CMake subproject via FetchContent and links
# against its `PFFFT::PFFFT` target. Source layout of marton78/pffft is
# split across multiple .c files inside src/ — we don't try to compile
# pffft sources directly; FetchContent_MakeAvailable runs their CMakeLists
# and exposes the proper target.
#
# Cache variables are set BEFORE the FetchContent fetches/configures the
# subproject so they take effect when their CMakeLists evaluates options.
#
# License attribution: app/src/main/assets/licenses/LICENSE-PFFFT.txt.

cmake_minimum_required(VERSION 3.22.1)
project(lofipod_fft LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- Configure marton78/pffft before fetching ----
# Default options per marton78/pffft v0.9-ish:
#   PFFFT_USE_TYPE_FLOAT  ON   (we keep — single precision is what we use)
#   PFFFT_USE_TYPE_DOUBLE ON   (we disable — 4 KB-ish savings, no double codepath needed)
#   PFFFT_USE_SIMD        ON   (we keep — auto-enables NEON on ARM via __ARM_NEON)
#   PFFFT_USE_SCALAR_VECT ON   (we keep — scalar 4-element fallback)
#   PFFFT_BUILD_TESTS / _BENCHMARKS / _EXAMPLES — default to ${PFFFT_IS_TOPLEVEL}
#     which is FALSE in our subproject consumption, but set explicitly to be sure.
#   INSTALL_PFFFT         ON   (we disable — we're packing into a single JNI .so,
#                              not installing to a prefix)
set(PFFFT_USE_TYPE_FLOAT ON CACHE BOOL "" FORCE)
set(PFFFT_USE_TYPE_DOUBLE OFF CACHE BOOL "" FORCE)
set(PFFFT_USE_SIMD ON CACHE BOOL "" FORCE)
set(PFFFT_USE_SCALAR_VECT ON CACHE BOOL "" FORCE)
set(PFFFT_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(PFFFT_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(PFFFT_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(INSTALL_PFFFT OFF CACHE BOOL "" FORCE)
set(INSTALL_PFDSP OFF CACHE BOOL "" FORCE)
set(INSTALL_PFFASTCONV OFF CACHE BOOL "" FORCE)
# Build PFFFT as a static lib that we then statically link into our JNI .so —
# avoids shipping a separate libPFFFT.so alongside liblofipod_fft.so in the APK.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

include(FetchContent)
# Pinned to commit a4b0359 (2026-04-22): this commit restricts PFFFT's
# uninstall target to top-level projects only, which fixes a conflict
# when consumed as a subproject (our exact case). v1.1.0 (Feb 2026)
# predates this fix and would error out at CMake configure time. Bump
# the SHA deliberately when a newer fix or feature is wanted.
FetchContent_Declare(
    pffft
    GIT_REPOSITORY https://github.com/marton78/pffft.git
    GIT_TAG a4b03590cc2a4bea56f9721996e3057835799179
)
FetchContent_MakeAvailable(pffft)

# ---- Our JNI bridge ----
add_library(lofipod_fft SHARED lofipod_fft.cpp)

# PFFFT::PFFFT is the float-precision FFT target (marton78's alias for the
# PFFFT library target). When PFFFT_USE_TYPE_DOUBLE=OFF, the public API
# symbols stay as `pffft_*` (no `pffftf_` prefix) — see pffft.h.
target_link_libraries(lofipod_fft PRIVATE PFFFT::PFFFT)

# Android log API for fallback error reporting.
find_library(log-lib log)
target_link_libraries(lofipod_fft PRIVATE ${log-lib})
