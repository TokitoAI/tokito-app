cmake_minimum_required(VERSION 3.22.1)
project("ai-chat" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
else()
    message(FATAL_ERROR "Tokito dialogue runtime supports arm64-v8a only")
endif()

set(LLAMA_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
add_subdirectory("${LLAMA_SRC}" build-llama)

set(ANDROID_EXAMPLE_CPP "${LLAMA_SRC}/examples/llama.android/lib/src/main/cpp")
set(GENERATED_CPP "${CMAKE_CURRENT_BINARY_DIR}/tokito-llama-android")
file(MAKE_DIRECTORY "${GENERATED_CPP}")
configure_file("${ANDROID_EXAMPLE_CPP}/ai_chat.cpp" "${GENERATED_CPP}/ai_chat.cpp" COPYONLY)
file(READ "${ANDROID_EXAMPLE_CPP}/logging.h" LOGGING_SOURCE)
# Upstream's example uses an API-30-only log filtering helper. Tokito supports
# API 26 and keeps the same compile-time minimum-level semantics without calling
# that unavailable symbol.
string(REPLACE
    "return __android_log_is_loggable(prio, LOG_TAG, LOG_MIN_LEVEL);"
    "return prio >= LOG_MIN_LEVEL;"
    LOGGING_SOURCE
    "${LOGGING_SOURCE}"
)
file(WRITE "${GENERATED_CPP}/logging.h" "${LOGGING_SOURCE}")

add_library(ai-chat SHARED "${GENERATED_CPP}/ai_chat.cpp")
target_compile_definitions(ai-chat PRIVATE
    GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
    GGML_CPU_KLEIDIAI=1
    GGML_OPENMP=1
)
target_include_directories(ai-chat PRIVATE
    "${LLAMA_SRC}"
    "${LLAMA_SRC}/common"
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/ggml/include"
    "${LLAMA_SRC}/ggml/src"
    "${GENERATED_CPP}"
)
target_link_libraries(ai-chat llama llama-common android log)
