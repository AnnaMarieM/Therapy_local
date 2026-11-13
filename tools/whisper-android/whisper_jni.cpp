// JNI wrapper around whisper.cpp for the app's :batch process (docs/AD/AD-22.md). CPU only, one context per loaded model, one call per turn.
// Built by tools/whisper-android/build.sh into app/src/main/jniLibs/arm64-v8a/libwhisper_jni.so. No text is ever logged from here.
#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

namespace {
struct Ctx {
    whisper_context * wctx = nullptr;
    float encode_ms = 0, decode_ms = 0, prompt_ms = 0, sample_ms = 0;
};
}

extern "C" {

// Returns a handle (a pointer) or 0 when the model cannot be loaded.
JNIEXPORT jlong JNICALL Java_com_therapylocal_app_engine_runner_WhisperLib_nativeInit(JNIEnv * env, jclass, jstring path) {
    const char * p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = false;
    whisper_context * w = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!w) return 0;
    auto * c = new Ctx();
    c->wctx = w;
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL Java_com_therapylocal_app_engine_runner_WhisperLib_nativeFree(JNIEnv *, jclass, jlong h) {
    auto * c = reinterpret_cast<Ctx *>(h);
    if (!c) return;
    whisper_free(c->wctx);
    delete c;
}

// language: "cs", "en" or "" for auto. translate: the Whisper translate task (any language to English text). beam <= 1 is greedy.
// Returns the text of every segment joined by a space, or null on failure.
JNIEXPORT jstring JNICALL Java_com_therapylocal_app_engine_runner_WhisperLib_nativeRun(
        JNIEnv * env, jclass, jlong h, jfloatArray samples, jstring language, jboolean translate, jint beam, jint threads, jstring prompt) {
    auto * c = reinterpret_cast<Ctx *>(h);
    if (!c) return nullptr;
    const jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());
    const char * lang = env->GetStringUTFChars(language, nullptr);
    std::string langs(lang);
    env->ReleaseStringUTFChars(language, lang);
    std::string prompts;
    if (prompt != nullptr) {
        const char * pr = env->GetStringUTFChars(prompt, nullptr);
        prompts = pr;
        env->ReleaseStringUTFChars(prompt, pr);
    }

    whisper_full_params fp = whisper_full_default_params(beam > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    fp.n_threads = threads;
    fp.translate = translate;
    fp.language = langs.empty() ? "auto" : langs.c_str();
    fp.detect_language = false;
    fp.print_progress = false;
    fp.print_realtime = false;
    fp.print_special = false;
    fp.print_timestamps = false;
    if (beam > 1) fp.beam_search.beam_size = beam;
    if (!prompts.empty()) fp.initial_prompt = prompts.c_str();

    whisper_reset_timings(c->wctx);
    if (whisper_full(c->wctx, fp, pcm.data(), n) != 0) return nullptr;
    const whisper_timings * t = whisper_get_timings(c->wctx);
    if (t) { c->encode_ms = t->encode_ms; c->decode_ms = t->decode_ms + t->batchd_ms; c->prompt_ms = t->prompt_ms; c->sample_ms = t->sample_ms; }

    std::string out;
    const int segs = whisper_full_n_segments(c->wctx);
    for (int i = 0; i < segs; i++) {
        const char * s = whisper_full_get_segment_text(c->wctx, i);
        if (!s) continue;
        if (!out.empty()) out += ' ';
        out += s;
    }
    return env->NewStringUTF(out.c_str());
}

// encode_ms, decode_ms (decode + batched decode), prompt_ms, sample_ms of the last run, as four floats.
JNIEXPORT jfloatArray JNICALL Java_com_therapylocal_app_engine_runner_WhisperLib_nativeTimings(JNIEnv * env, jclass, jlong h) {
    auto * c = reinterpret_cast<Ctx *>(h);
    jfloat v[4] = {0, 0, 0, 0};
    if (c) { v[0] = c->encode_ms; v[1] = c->decode_ms; v[2] = c->prompt_ms; v[3] = c->sample_ms; }
    jfloatArray a = env->NewFloatArray(4);
    env->SetFloatArrayRegion(a, 0, 4, v);
    return a;
}

JNIEXPORT jstring JNICALL Java_com_therapylocal_app_engine_runner_WhisperLib_nativeSystemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

}
