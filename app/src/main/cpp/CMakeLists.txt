# CMakeLists.txt for the lofipod_fft native library (PFFFT JNI bridge).
#
# This module bundles the PFFFT FFT library (BSD-3-Clause, Julien Pommier,
# obtained via the marton78/pffft fork) compiled to a small JNI-callable
# shared object. UpcConvolver.kt on the Kotlin side calls into this library
# via PffftBridge.kt to run forward/inverse real-FFTs and the canonical
# packed-complex multiply-accumulate inner loop of partitioned convolution.
#
# Replaces the JTransforms 3.1 (pure-JVM) FFT path used pre-v0.9.6. PFFFT's
# advantage on ARM is NEON-SIMD acceleration (3-5x faster than JTransforms
# at the sizes we use, fftSize=2048) and a dedicated
# pffft_zconvolve_accumulate inner loop that's faster than rewriting the
# multiply-accumulate in Kotlin.
#
# License attribution: see app/src/main/assets/licenses/LICENSE-PFFFT.txt
# for the BSD-3-Clause text + copyright notice.

cmake_minimum_required(VERSION 3.22.1)
project(lofipod_fft LANGUAGES C CXX)

# Default to C++17 for the JNI bridge file; PFFFT itself is C.
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- Vendor PFFFT via FetchContent ----
#
# We fetch the marton78/pffft fork (CMake-friendly maintained mirror of
# Julien Pommier's original PFFFT). We only compile pffft.c directly into
# our JNI library rather than using marton78's CMakeLists (which builds
# extra test/bench targets we don't need).
#
# GIT_TAG: pinned to a master commit for reproducibility. Bump deliberately
# when we want a newer PFFFT version. Using `master` here for the v0.9.6
# initial cut; will pin to a specific commit hash in a follow-up tag once
# the build is proven green.
include(FetchContent)
FetchContent_Declare(
    pffft_src
    GIT_REPOSITORY https://github.com/marton78/pffft.git
    GIT_TAG master
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(pffft_src)
if(NOT pffft_src_POPULATED)
    FetchContent_Populate(pffft_src)
endif()

# ---- The lofipod_fft shared library ----
add_library(lofipod_fft SHARED
    lofipod_fft.cpp
    ${pffft_src_SOURCE_DIR}/pffft.c
)

target_include_directories(lofipod_fft PRIVATE
    ${pffft_src_SOURCE_DIR}
)

# PFFFT detects SIMD support via preprocessor: on ARMv7 it expects NEON,
# on ARM64 NEON is always available. The default compile flags AGP passes
# for arm64-v8a / armeabi-v7a target the right CPUs, so PFFFT auto-enables
# NEON when it sees __ARM_NEON. No extra flags needed.

# Link against Android's standard libs.
find_library(log-lib log)
target_link_libraries(lofipod_fft PRIVATE ${log-lib})
