// Narrow C ABI adapter, checked against Qualcomm's public QNN System headers
// at geniex-qairt-plugin e6d55652c174f5669608abaf04d529faa631f834.
// Opaque tensor/platform pointers are never dereferenced. No SDK implementation
// or licensed SDK headers are redistributed here.
#pragma once
#include <cstdint>
#include <cstddef>
namespace inventory {
struct Version { uint32_t major, minor, patch; };
struct Graph1 { const char* name; uint32_t inputs; void* input; uint32_t outputs; void* output; };
struct Graph2 { const char* name; uint32_t inputs; void* input; uint32_t outputs; void* output;
    uint32_t updates; void* update; };
struct Graph3 { const char* name; uint32_t inputs; void* input; uint32_t outputs; void* output;
    uint32_t updates; void* update; uint32_t blob_size; void* blob; uint32_t start, end; };
struct Graph { uint32_t version; union { Graph1 v1; Graph2 v2; Graph3 v3; }; };
struct Binary1 { uint32_t backend; const char* build; Version core, backend_api; const char* soc;
    Version hardware, context; uint32_t hw_size; void* hw; uint64_t context_size;
    uint32_t tensors; void* tensor; uint32_t count; Graph* graphs; };
struct Binary2 { uint32_t backend; const char* build; Version core, backend_api; const char* soc;
    Version hardware, context; uint32_t hw_size; void* hw; uint64_t context_size;
    uint32_t tensors; void* tensor; uint32_t count; Graph* graphs; void* platform; };
struct Binary3 { uint32_t backend; const char* build; Version core, backend_api; const char* soc;
    Version context; uint64_t context_size; uint32_t tensors; void* tensor;
    uint32_t count; Graph* graphs; void* platform; uint32_t metadata_size; void* metadata; uint32_t soc_model; };
struct Binary { uint32_t version; union { Binary1 v1; Binary2 v2; Binary3 v3; }; };
using Create = uint64_t (*)(void**);
using Info = uint64_t (*)(void*, void*, uint64_t, const Binary**, uint64_t*);
using Metadata = uint64_t (*)(void*, const void*, uint64_t, const Binary**);
using Free = uint64_t (*)(void*);
struct Functions { Create create; Info info; Metadata metadata; Free free; };
struct Provider { uint32_t backend; const char* name; Version version; Functions functions; };
using Providers = uint64_t (*)(const Provider***, uint32_t*);
static_assert(sizeof(void*) == 8, "Inventory adapter is arm64 only");
static_assert(sizeof(Graph) == 88 && offsetof(Graph, v3) == 8);
static_assert(offsetof(Provider, functions) == 32);
}

// Standalone qualification against the vendor SDK, never needed at app build.
#ifdef TOKITO_VERIFY_QNN_HEADERS
#include "System/QnnSystemInterface.h"
#define SAME_SIZE(ours, sdk) static_assert(sizeof(inventory::ours) == sizeof(sdk))
#define SAME_OFFSET(ours, member, sdk, field) static_assert(offsetof(inventory::ours, member) == offsetof(sdk, field))
SAME_SIZE(Graph, QnnSystemContext_GraphInfo_t);
SAME_SIZE(Binary, QnnSystemContext_BinaryInfo_t);
SAME_SIZE(Binary1, QnnSystemContext_BinaryInfoV1_t);
SAME_SIZE(Binary2, QnnSystemContext_BinaryInfoV2_t);
SAME_SIZE(Binary3, QnnSystemContext_BinaryInfoV3_t);
SAME_OFFSET(Binary1, count, QnnSystemContext_BinaryInfoV1_t, numGraphs);
SAME_OFFSET(Binary1, graphs, QnnSystemContext_BinaryInfoV1_t, graphs);
SAME_OFFSET(Binary2, count, QnnSystemContext_BinaryInfoV2_t, numGraphs);
SAME_OFFSET(Binary2, graphs, QnnSystemContext_BinaryInfoV2_t, graphs);
SAME_OFFSET(Binary3, count, QnnSystemContext_BinaryInfoV3_t, numGraphs);
SAME_OFFSET(Binary3, graphs, QnnSystemContext_BinaryInfoV3_t, graphs);
SAME_OFFSET(Provider, functions, QnnSystemInterface_t, QNN_SYSTEM_INTERFACE_VER_NAME);
SAME_OFFSET(Provider, version, QnnSystemInterface_t, systemApiVersion);
SAME_OFFSET(Functions, create, QNN_SYSTEM_INTERFACE_VER_TYPE, systemContextCreate);
SAME_OFFSET(Functions, info, QNN_SYSTEM_INTERFACE_VER_TYPE, systemContextGetBinaryInfo);
SAME_OFFSET(Functions, metadata, QNN_SYSTEM_INTERFACE_VER_TYPE, systemContextGetMetaData);
SAME_OFFSET(Functions, free, QNN_SYSTEM_INTERFACE_VER_TYPE, systemContextFree);
static_assert(sizeof(Qnn_ErrorHandle_t) == sizeof(uint64_t));
static_assert(sizeof(Qnn_ContextBinarySize_t) == sizeof(uint64_t));
#undef SAME_SIZE
#undef SAME_OFFSET
#endif
