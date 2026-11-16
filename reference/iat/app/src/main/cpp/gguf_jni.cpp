#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <string>
#include <vector>
#include "llama.h"

#define LOG_TAG "IAT-GGUF"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * sampler = nullptr;
    const llama_vocab * vocab = nullptr;
};

static std::string jstring_to_string(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_agent_eae_android_core_inference_GgufNative_create(
        JNIEnv * env, jclass, jstring jpath, jint context_size, jint threads) {
    const std::string path = jstring_to_string(env, jpath);
    if (path.empty()) return 0;

    llama_backend_init();

    auto * engine = new Engine();
    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    engine->model = llama_model_load_from_file(path.c_str(), model_params);
    if (!engine->model) {
        LOGE("Unable to load GGUF: %s", path.c_str());
        delete engine;
        return 0;
    }

    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(std::max(512, static_cast<int>(context_size)));
    ctx_params.n_batch = std::min<uint32_t>(ctx_params.n_ctx, 512);
    ctx_params.n_ubatch = ctx_params.n_batch;
    ctx_params.n_threads = std::max(1, static_cast<int>(threads));
    ctx_params.n_threads_batch = ctx_params.n_threads;
    ctx_params.no_perf = false;

    engine->ctx = llama_init_from_model(engine->model, ctx_params);
    if (!engine->ctx) {
        LOGE("Unable to create llama context");
        llama_model_free(engine->model);
        delete engine;
        return 0;
    }

    engine->vocab = llama_model_get_vocab(engine->model);
    engine->sampler = llama_sampler_init_greedy();
    if (!engine->sampler) {
        llama_free(engine->ctx);
        llama_model_free(engine->model);
        delete engine;
        return 0;
    }

    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_agent_eae_android_core_inference_GgufNative_generate(
        JNIEnv * env, jclass, jlong handle, jstring jprompt, jint max_tokens) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (!engine || !engine->ctx || !engine->vocab) return nullptr;

    const std::string prompt = jstring_to_string(env, jprompt);
    if (prompt.empty()) return env->NewStringUTF("");

    llama_memory_clear(llama_get_memory(engine->ctx), true);
    llama_sampler_reset(engine->sampler);

    const int32_t probe = llama_tokenize(
            engine->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            nullptr, 0, true, false);
    if (probe >= 0) return nullptr;

    std::vector<llama_token> tokens(static_cast<size_t>(-probe));
    const int32_t n_tokens = llama_tokenize(
            engine->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            tokens.data(), static_cast<int32_t>(tokens.size()), true, false);
    if (n_tokens < 0) return nullptr;
    tokens.resize(static_cast<size_t>(n_tokens));

    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(engine->ctx));
    const int32_t reserve = std::max(32, static_cast<int>(max_tokens));
    if (static_cast<int32_t>(tokens.size()) + reserve >= n_ctx) {
        const size_t keep = static_cast<size_t>(std::max(1, n_ctx - reserve));
        if (tokens.size() > keep) {
            tokens.erase(tokens.begin(), tokens.end() - static_cast<long>(keep));
        }
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(engine->ctx, batch) != 0) {
        return nullptr;
    }

    std::string output;
    const int limit = std::max(1, static_cast<int>(max_tokens));
    for (int i = 0; i < limit; ++i) {
        const llama_token token = llama_sampler_sample(engine->sampler, engine->ctx, -1);
        llama_sampler_accept(engine->sampler, token);
        if (llama_vocab_is_eog(engine->vocab, token)) break;

        char piece[8192];
        const int32_t n = llama_token_to_piece(engine->vocab, token, piece, sizeof(piece), 0, true);
        if (n > 0) output.append(piece, static_cast<size_t>(n));

        llama_token next = token;
        llama_batch next_batch = llama_batch_get_one(&next, 1);
        if (llama_decode(engine->ctx, next_batch) != 0) break;
    }

    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_agent_eae_android_core_inference_GgufNative_close(
        JNIEnv *, jclass, jlong handle) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (!engine) return;
    if (engine->sampler) llama_sampler_free(engine->sampler);
    if (engine->ctx) llama_free(engine->ctx);
    if (engine->model) llama_model_free(engine->model);
    delete engine;
}
