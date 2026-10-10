#include "qnn_inventory_abi.h"
#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstring>
#include <stdexcept>
#include <vector>

namespace {
struct Library { void* p = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_LOCAL); ~Library() { if (p) dlclose(p); } };
struct File { int fd; explicit File(const char* path) : fd(open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW)) {}
    ~File() { if (fd >= 0) close(fd); } };
struct Mapping { void* p; size_t size; Mapping(int fd, size_t n) : p(mmap(nullptr, n, PROT_READ, MAP_PRIVATE, fd, 0)), size(n) {}
    ~Mapping() { if (p != MAP_FAILED) munmap(p, size); } };
struct Context { void* p = nullptr; inventory::Free free; ~Context() { if (p) free(p); } };
void require(bool yes, const char* message) { if (!yes) throw std::runtime_error(message); }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_tokito_physical_dialogue_NativeQnnInventory_nativeGraphNames(JNIEnv* env, jobject, jbyteArray path) {
    try {
        require(path != nullptr, "Missing QNN binary path");
        const auto length = env->GetArrayLength(path);
        require(length > 0 && length <= 4096, "Invalid QNN binary path length");
        std::vector<char> utf8(static_cast<size_t>(length) + 1, 0);
        env->GetByteArrayRegion(path, 0, length, reinterpret_cast<jbyte*>(utf8.data()));
        if (env->ExceptionCheck()) return nullptr;
        require(std::memchr(utf8.data(), 0, length) == nullptr, "NUL in QNN path");
        File file(utf8.data());
        struct stat stat{};
        require(file.fd >= 0 && fstat(file.fd, &stat) == 0 && S_ISREG(stat.st_mode) && stat.st_size > 0 &&
            stat.st_size <= 3LL * 1024 * 1024 * 1024, "QNN binary file exceeds inventory bound");
        Mapping mapping(file.fd, static_cast<size_t>(stat.st_size));
        require(mapping.p != MAP_FAILED, "QNN binary mapping failed");
        Library library;
        require(library.p != nullptr, "Pinned QNN System library unavailable");
        const auto providers = reinterpret_cast<inventory::Providers>(dlsym(library.p, "QnnSystemInterface_getProviders"));
        require(providers != nullptr, "QNN System inventory ABI missing");
        const inventory::Provider** list = nullptr;
        uint32_t count = 0;
        require(providers(&list, &count) == 0 && list && count > 0 && count <= 32, "Invalid QNN System providers");
        const inventory::Functions* api = nullptr;
        for (uint32_t i = 0; i < count; ++i) {
            if (list[i] && list[i]->version.major == 1 && list[i]->version.minor >= 4) {
                api = &list[i]->functions; break;
            }
        }
        require(api && api->create && api->info && api->free, "Unsupported QNN System API version");
        Context context{nullptr, api->free};
        require(api->create(&context.p) == 0 && context.p, "QNN System context creation failed");
        const inventory::Binary* info = nullptr;
        uint64_t info_size = 0;
        require(api->info(context.p, mapping.p, mapping.size, &info, &info_size) == 0 && info,
            "QNN System rejected signed context binary");
        const inventory::Graph* graphs = nullptr;
        uint32_t graph_count = 0;
        switch (info->version) {
            case 1: graph_count = info->v1.count; graphs = info->v1.graphs; break;
            case 2: graph_count = info->v2.count; graphs = info->v2.graphs; break;
            case 3: graph_count = info->v3.count; graphs = info->v3.graphs; break;
            default: throw std::runtime_error("Unsupported QNN binary metadata version");
        }
        require(graphs && graph_count > 0 && graph_count <= 1024, "QNN graph inventory exceeds bound");
        auto output = env->NewObjectArray(static_cast<jsize>(graph_count), env->FindClass("java/lang/String"), nullptr);
        if (!output) return nullptr;
        for (uint32_t i = 0; i < graph_count; ++i) {
            require(graphs[i].version >= 1 && graphs[i].version <= 3, "Unsupported QNN graph metadata version");
            const char* name = graphs[i].v1.name;
            require(name && strnlen(name, 257) <= 256 && *name, "Invalid bounded QNN graph name");
            for (const char* p = name; *p; ++p) require(static_cast<unsigned char>(*p) < 128, "Non-ASCII QNN graph name");
            auto text = env->NewStringUTF(name);
            if (!text) return nullptr;
            env->SetObjectArrayElement(output, static_cast<jsize>(i), text);
            env->DeleteLocalRef(text);
            if (env->ExceptionCheck()) return nullptr;
        }
        return output;
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what()); return nullptr;
    } catch (...) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Native QNN inventory failed"); return nullptr;
    }
}
