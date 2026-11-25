#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <string>
#include <vector>
#include "llama.h"

#define LOG_TAG "KarbiLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct KarbiLlamaHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int n_threads = 4;
};

static bool g_backend_initialized = false;

extern "C" JNIEXPORT jlong JNICALL
Java_com_talkaurelia_karbi_llm_LiteRtKarbiEngineManager_nativeLoadModel(
    JNIEnv * env,
    jobject /* thiz */,
    jstring model_path_jstr,
    jint n_ctx,
    jint n_threads
) {
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }

    const char * model_path = env->GetStringUTFChars(model_path_jstr, nullptr);
    if (!model_path) return 0;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    llama_model * model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(model_path_jstr, model_path);

    if (!model) {
        LOGE("Failed to load GGUF model");
        return 0;
    }

    const uint32_t target_ctx = n_ctx > 1024 ? static_cast<uint32_t>(n_ctx) : 2048;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = target_ctx;
    cparams.n_batch = target_ctx;
    cparams.n_ubatch = 512;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to initialize llama_context");
        llama_model_free(model);
        return 0;
    }

    auto * handle = new KarbiLlamaHandle();
    handle->model = model;
    handle->ctx = ctx;
    handle->n_threads = cparams.n_threads;
    LOGI("Loaded Qwen2.5 Karbi GGUF model (ctx=%u, threads=%d)", target_ctx, handle->n_threads);
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_talkaurelia_karbi_llm_LiteRtKarbiEngineManager_nativeGenerate(
    JNIEnv * env,
    jobject /* thiz */,
    jlong handle_ptr,
    jstring prompt_jstr,
    jint max_tokens
) {
    auto * handle = reinterpret_cast<KarbiLlamaHandle *>(handle_ptr);
    if (!handle || !handle->model || !handle->ctx) {
        return env->NewStringUTF("ERROR: GGUF engine not initialized");
    }

    const char * prompt_cstr = env->GetStringUTFChars(prompt_jstr, nullptr);
    if (!prompt_cstr) {
        return env->NewStringUTF("");
    }
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(prompt_jstr, prompt_cstr);

    llama_kv_cache_clear(handle->ctx);

    const llama_vocab * vocab = llama_model_get_vocab(handle->model);
    const int n_prompt_est = -llama_tokenize(
        vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        nullptr,
        0,
        true,
        true
    );

    if (n_prompt_est <= 0) {
        return env->NewStringUTF("ERROR: Failed to tokenize prompt");
    }

    std::vector<llama_token> tokens(n_prompt_est);
    if (llama_tokenize(
            vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,
            true) < 0) {
        return env->NewStringUTF("ERROR: Tokenization buffer mismatch");
    }

    const int ctx_limit = static_cast<int>(llama_n_ctx(handle->ctx));
    const int max_prompt_tokens = std::max(64, ctx_limit - 192);
    if (static_cast<int>(tokens.size()) > max_prompt_tokens) {
        tokens.resize(max_prompt_tokens);
    }

    // Decode prompt in safe 512-token chunks so n_ubatch is never exceeded
    const int chunk_size = 512;
    const int total_tokens = static_cast<int>(tokens.size());
    for (int offset = 0; offset < total_tokens; offset += chunk_size) {
        const int count = std::min(chunk_size, total_tokens - offset);
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, count);
        if (llama_decode(handle->ctx, batch) != 0) {
            return env->NewStringUTF("ERROR: Prompt decode failed");
        }
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string output;
    const int limit = max_tokens > 0 ? max_tokens : 160;

    for (int i = 0; i < limit; ++i) {
        llama_token new_token = llama_sampler_sample(smpl, handle->ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token)) {
            break;
        }

        char piece[256];
        int n_chars = llama_token_to_piece(vocab, new_token, piece, sizeof(piece), 0, false);
        if (n_chars > 0) {
            output.append(piece, n_chars);
        }

        if (output.find("<|im_end|>") != std::string::npos ||
            output.find("<|endoftext|>") != std::string::npos) {
            break;
        }

        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(handle->ctx, next_batch) != 0) {
            break;
        }
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_talkaurelia_karbi_llm_LiteRtKarbiEngineManager_nativeFree(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle_ptr
) {
    auto * handle = reinterpret_cast<KarbiLlamaHandle *>(handle_ptr);
    if (!handle) return;
    if (handle->ctx) {
        llama_free(handle->ctx);
        handle->ctx = nullptr;
    }
    if (handle->model) {
        llama_model_free(handle->model);
        handle->model = nullptr;
    }
    delete handle;
}
