// JNI bridge between Kotlin's [com.lofipod.app.audio.PffftBridge] and the
// PFFFT FFT library (BSD-3-Clause, see assets/licenses/LICENSE-PFFFT.txt).
//
// Exposes a minimal C ABI over JNI:
//   - nativeNewSetup(n) -> handle (opaque pointer cast to jlong)
//   - nativeDestroySetup(handle)
//   - nativeTransform(handle, inOut[], scale, forward)
//   - nativeZconvolveAccumulate(handle, a[], b[], abAcc[], scaling)
//
// PFFFT requires 16-byte aligned input/output buffers; JNI's array element
// access doesn't guarantee that. We allocate aligned scratch buffers per
// setup (via pffft_aligned_malloc) and memcpy in/out at JNI boundaries.
// The memcpy overhead is O(N) while the FFT is O(N log N), so the
// performance tax is small and we gain PFFFT's NEON-SIMD speedup.
//
// Threading: callers (PffftBridge / UpcConvolver) hold the bridge on a
// single thread (the audio thread). No internal locking; if a caller ever
// shares a single Bridge instance across threads they own the
// synchronization.

#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <android/log.h>
#include "pffft.h"

#define LOG_TAG "lofipod_fft"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// One context per PffftBridge instance. Owns the PFFFT setup plus three
// 16-byte-aligned scratch buffers (input/output, second operand, work).
// PFFFT's pffft_aligned_malloc returns 16-byte-aligned memory on all
// platforms regardless of the system malloc's guarantees.
struct LofipodFftContext {
    PFFFT_Setup *setup;
    int n;
    float *bufA;     // size n floats
    float *bufB;     // size n floats
    float *bufAcc;   // size n floats
    float *work;     // size n floats — PFFFT scratch
};

LofipodFftContext *as_ctx(jlong handle) {
    return reinterpret_cast<LofipodFftContext *>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_lofipod_app_audio_PffftBridge_nativeNewSetup(
    JNIEnv * /* env */, jobject /* this */, jint n
) {
    if (n < 32 || (n & (n - 1)) != 0) {
        // PFFFT REAL requires N >= 32 and N a product of 2/3/5 powers.
        // For LofiPod's fixed UPC config we always pass 2048 = 2^11,
        // but guard anyway so a future config change fails cleanly.
        LOGE("nativeNewSetup: invalid N=%d (must be >=32, power-of-2)", n);
        return 0;
    }
    PFFFT_Setup *setup = pffft_new_setup(n, PFFFT_REAL);
    if (setup == nullptr) {
        LOGE("nativeNewSetup: pffft_new_setup failed for N=%d", n);
        return 0;
    }

    auto *ctx = static_cast<LofipodFftContext *>(std::malloc(sizeof(LofipodFftContext)));
    if (ctx == nullptr) {
        pffft_destroy_setup(setup);
        return 0;
    }
    ctx->setup = setup;
    ctx->n = n;
    ctx->bufA = static_cast<float *>(pffft_aligned_malloc(n * sizeof(float)));
    ctx->bufB = static_cast<float *>(pffft_aligned_malloc(n * sizeof(float)));
    ctx->bufAcc = static_cast<float *>(pffft_aligned_malloc(n * sizeof(float)));
    ctx->work = static_cast<float *>(pffft_aligned_malloc(n * sizeof(float)));
    if (ctx->bufA == nullptr || ctx->bufB == nullptr ||
        ctx->bufAcc == nullptr || ctx->work == nullptr) {
        LOGE("nativeNewSetup: aligned_malloc failed for N=%d", n);
        if (ctx->bufA) pffft_aligned_free(ctx->bufA);
        if (ctx->bufB) pffft_aligned_free(ctx->bufB);
        if (ctx->bufAcc) pffft_aligned_free(ctx->bufAcc);
        if (ctx->work) pffft_aligned_free(ctx->work);
        pffft_destroy_setup(setup);
        std::free(ctx);
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_lofipod_app_audio_PffftBridge_nativeDestroySetup(
    JNIEnv * /* env */, jobject /* this */, jlong handle
) {
    auto *ctx = as_ctx(handle);
    if (ctx == nullptr) return;
    pffft_aligned_free(ctx->bufA);
    pffft_aligned_free(ctx->bufB);
    pffft_aligned_free(ctx->bufAcc);
    pffft_aligned_free(ctx->work);
    pffft_destroy_setup(ctx->setup);
    std::free(ctx);
}

// In-place real-FFT forward or inverse. inOut is read and written. Scaling
// for the inverse is applied here (PFFFT does not auto-scale the inverse;
// we multiply by 1/N to match JTransforms' realInverse(scale=true)
// behavior). Forward direction: no scaling.
JNIEXPORT void JNICALL
Java_com_lofipod_app_audio_PffftBridge_nativeTransform(
    JNIEnv *env, jobject /* this */,
    jlong handle, jfloatArray inOut, jboolean forward
) {
    auto *ctx = as_ctx(handle);
    if (ctx == nullptr) return;
    jsize len = env->GetArrayLength(inOut);
    if (len != ctx->n) {
        LOGE("nativeTransform: array length %d != setup N %d", (int) len, ctx->n);
        return;
    }

    // Copy Kotlin float[] into aligned scratch, transform in-place, copy back.
    env->GetFloatArrayRegion(inOut, 0, ctx->n, ctx->bufA);

    pffft_transform(
        ctx->setup, ctx->bufA, ctx->bufA, ctx->work,
        forward ? PFFFT_FORWARD : PFFFT_BACKWARD
    );

    // Scale the inverse output to match the JTransforms realInverse(true)
    // convention used elsewhere in the codebase (1/N normalization).
    if (!forward) {
        const float invN = 1.0f / static_cast<float>(ctx->n);
        float *p = ctx->bufA;
        const int n = ctx->n;
        for (int i = 0; i < n; ++i) {
            p[i] *= invN;
        }
    }

    env->SetFloatArrayRegion(inOut, 0, ctx->n, ctx->bufA);
}

// dft_ab_acc += dft_a * dft_b * scaling (packed-complex multiply-accumulate).
// All three arrays are PFFFT-internal-layout spectra of size N (the result
// of pffft_transform). a and b are read; abAcc is read-modify-written.
JNIEXPORT void JNICALL
Java_com_lofipod_app_audio_PffftBridge_nativeZconvolveAccumulate(
    JNIEnv *env, jobject /* this */,
    jlong handle, jfloatArray a, jfloatArray b, jfloatArray abAcc, jfloat scaling
) {
    auto *ctx = as_ctx(handle);
    if (ctx == nullptr) return;
    const int n = ctx->n;
    if (env->GetArrayLength(a) != n ||
        env->GetArrayLength(b) != n ||
        env->GetArrayLength(abAcc) != n) {
        LOGE("nativeZconvolveAccumulate: array length mismatch");
        return;
    }

    env->GetFloatArrayRegion(a, 0, n, ctx->bufA);
    env->GetFloatArrayRegion(b, 0, n, ctx->bufB);
    env->GetFloatArrayRegion(abAcc, 0, n, ctx->bufAcc);

    pffft_zconvolve_accumulate(ctx->setup, ctx->bufA, ctx->bufB, ctx->bufAcc, scaling);

    env->SetFloatArrayRegion(abAcc, 0, n, ctx->bufAcc);
}

// Reorder a PFFFT internal-layout spectrum into the canonical packed-complex
// layout that callers expect for kernel-export inspection. Most of the time
// we don't need this — UPC's multiply-accumulate uses internal layout
// throughout — but it's available for diagnostics.
JNIEXPORT void JNICALL
Java_com_lofipod_app_audio_PffftBridge_nativeReorder(
    JNIEnv *env, jobject /* this */,
    jlong handle, jfloatArray spec, jboolean toCanonical
) {
    auto *ctx = as_ctx(handle);
    if (ctx == nullptr) return;
    const int n = ctx->n;
    if (env->GetArrayLength(spec) != n) return;

    env->GetFloatArrayRegion(spec, 0, n, ctx->bufA);
    pffft_zreorder(
        ctx->setup, ctx->bufA, ctx->bufB,
        toCanonical ? PFFFT_FORWARD : PFFFT_BACKWARD
    );
    env->SetFloatArrayRegion(spec, 0, n, ctx->bufB);
}

}  // extern "C"
