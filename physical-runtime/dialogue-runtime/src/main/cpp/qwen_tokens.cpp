// C ABI: mlc-ai/tokenizers-cpp include/tokenizers_c.h, Apache-2.0.
// Uses the tokenizer already shipped by pinned GenieX; no second inference engine.
#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <memory>
#include <vector>

namespace {
struct EncodeResult { int* token_ids; size_t len; };
using Create = void* (*)(const char*, size_t);
using Encode = void (*)(void*, const char*, size_t, int, EncodeResult*);
using FreeResult = void (*)(EncodeResult*, size_t);
using Free = void (*)(void*);
struct Tokenizer {
    void* library = nullptr;
    void* handle = nullptr;
    Encode encode = nullptr;
    FreeResult free_result = nullptr;
    Free free_handle = nullptr;
    ~Tokenizer() {
        if (handle && free_handle) free_handle(handle);
        if (library) dlclose(library);
    }
};
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
bool bytes(JNIEnv* env, jbyteArray input, size_t limit, std::vector<char>& output) {
    if (!input) { fail(env, "Missing tokenizer input"); return false; }
    const auto length = env->GetArrayLength(input);
    if (length <= 0 || static_cast<size_t>(length) > limit) {
        fail(env, "Tokenizer input exceeds native bound"); return false;
    }
    output.resize(static_cast<size_t>(length));
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(output.data()));
    return !env->ExceptionCheck();
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_tokito_physical_dialogue_NativeQwenTokenizer_nativeOpen(JNIEnv* env, jobject, jbyteArray json) {
    try {
        std::vector<char> data;
        if (!bytes(env, json, 32 * 1024 * 1024, data)) return 0;
        auto tokenizer = std::make_unique<Tokenizer>();
        tokenizer->library = dlopen("libgeniex-proc.so", RTLD_NOW | RTLD_LOCAL);
        if (!tokenizer->library) { fail(env, "Pinned GenieX tokenizer library unavailable"); return 0; }
        const auto create = reinterpret_cast<Create>(dlsym(tokenizer->library, "tokenizers_new_from_str"));
        tokenizer->encode = reinterpret_cast<Encode>(dlsym(tokenizer->library, "tokenizers_encode"));
        tokenizer->free_result = reinterpret_cast<FreeResult>(dlsym(tokenizer->library, "tokenizers_free_encode_results"));
        tokenizer->free_handle = reinterpret_cast<Free>(dlsym(tokenizer->library, "tokenizers_free"));
        if (!create || !tokenizer->encode || !tokenizer->free_result || !tokenizer->free_handle) {
            fail(env, "Pinned tokenizer C ABI unavailable"); return 0;
        }
        tokenizer->handle = create(data.data(), data.size());
        if (!tokenizer->handle) { fail(env, "Tokenizer creation failed"); return 0; }
        return reinterpret_cast<jlong>(tokenizer.release());
    } catch (...) { fail(env, "Native tokenizer allocation failed"); return 0; }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_tokito_physical_dialogue_NativeQwenTokenizer_nativeEncode(JNIEnv* env, jobject, jlong handle, jbyteArray text) {
    try {
        auto* tokenizer = reinterpret_cast<Tokenizer*>(handle);
        if (!tokenizer || !tokenizer->handle) { fail(env, "Tokenizer is closed"); return nullptr; }
        std::vector<char> data;
        if (!bytes(env, text, 256 * 1024, data)) return nullptr;
        EncodeResult result{nullptr, 0};
        // Qualcomm geniex::Tokenizer::encode delegates Encode(text), whose
        // tokenizers-cpp implementation disables added special tokens. The
        // complete vendor chat template already contains those markers.
        tokenizer->encode(tokenizer->handle, data.data(), data.size(), 0, &result);
        struct ResultLease {
            Tokenizer* tokenizer; EncodeResult* result;
            ~ResultLease() { tokenizer->free_result(result, 1); }
        } lease{tokenizer, &result};
        if (!result.token_ids || result.len == 0 || result.len > 256 * 1024) {
            fail(env, "Invalid bounded tokenizer result"); return nullptr;
        }
        auto output = env->NewIntArray(static_cast<jsize>(result.len));
        if (output) env->SetIntArrayRegion(output, 0, static_cast<jsize>(result.len), result.token_ids);
        return output;
    } catch (...) { fail(env, "Native tokenization failed"); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_tokito_physical_dialogue_NativeQwenTokenizer_nativeClose(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Tokenizer*>(handle);
}
