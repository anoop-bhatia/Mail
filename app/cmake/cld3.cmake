# Shared by the Android library and host regression tests.
include_guard(GLOBAL)
get_filename_component(CLD3_JNI_DIR "${CMAKE_CURRENT_LIST_DIR}/../src/main/jni" ABSOLUTE)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# All sources are vendored; configuration never downloads dependencies or uses
# a host-installed protobuf/Abseil during Android cross-compilation.
set(ABSL_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ABSL_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(ABSL_PROPAGATE_CXX_STD ON CACHE BOOL "" FORCE)
add_subdirectory("${CLD3_JNI_DIR}/third_party/abseil" "${CMAKE_CURRENT_BINARY_DIR}/abseil" EXCLUDE_FROM_ALL)
set(protobuf_INSTALL OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_PROTOC_BINARIES OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_LIBPROTOBUF OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_LIBPROTOC OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_LIBUPB OFF CACHE BOOL "" FORCE)
set(protobuf_WITH_ZLIB OFF CACHE BOOL "" FORCE)
set(protobuf_LOCAL_DEPENDENCIES_ONLY ON CACHE BOOL "" FORCE)
add_subdirectory("${CLD3_JNI_DIR}/third_party/protobuf" "${CMAKE_CURRENT_BINARY_DIR}/protobuf" EXCLUDE_FROM_ALL)

# Registration-only translation units must not be discarded by static archive
# extraction: the original shared target included every CLD3 object directly.
add_library(cld3 OBJECT
        # Archived upstream CLD3 model and implementation (unchanged).
        ${CLD3_JNI_DIR}/cld_3/src/base.cc
        ${CLD3_JNI_DIR}/cld_3/src/embedding_feature_extractor.cc
        ${CLD3_JNI_DIR}/cld_3/src/embedding_network.cc
        ${CLD3_JNI_DIR}/cld_3/src/feature_extractor.cc
        ${CLD3_JNI_DIR}/cld_3/src/feature_types.cc
        ${CLD3_JNI_DIR}/cld_3/src/fml_parser.cc
        ${CLD3_JNI_DIR}/cld_3/src/language_identifier_features.cc
        ${CLD3_JNI_DIR}/cld_3/src/lang_id_nn_params.cc
        ${CLD3_JNI_DIR}/cld_3/src/nnet_language_identifier.cc
        ${CLD3_JNI_DIR}/cld_3/src/registry.cc
        ${CLD3_JNI_DIR}/cld_3/src/relevant_script_feature.cc
        ${CLD3_JNI_DIR}/cld_3/src/sentence_features.cc
        ${CLD3_JNI_DIR}/cld_3/src/script_span/fixunicodevalue.cc
        ${CLD3_JNI_DIR}/cld_3/src/script_span/generated_entities.cc
        ${CLD3_JNI_DIR}/cld_3/src/script_span/generated_ulscript.cc
        ${CLD3_JNI_DIR}/cld_3/src/script_span/getonescriptspan.cc
        ${CLD3_JNI_DIR}/cld_3/src/script_span/offsetmap.cc
        ${CLD3_JNI_DIR}/cld_3/src/script_span/text_processing.cc
        ${CLD3_JNI_DIR}/cld_3/src/script_span/utf8statetable.cc
        ${CLD3_JNI_DIR}/cld_3/src/task_context.cc
        ${CLD3_JNI_DIR}/cld_3/src/task_context_params.cc
        ${CLD3_JNI_DIR}/cld_3/src/unicodetext.cc
        ${CLD3_JNI_DIR}/cld_3/src/utils.cc
        ${CLD3_JNI_DIR}/cld_3/src/workspace.cc

        # protoc 36.2 / Protobuf C++ 7.36.2 (regenerated together).
        ${CLD3_JNI_DIR}/cld_3/protos/feature_extractor.pb.cc
        ${CLD3_JNI_DIR}/cld_3/protos/sentence.pb.cc
        ${CLD3_JNI_DIR}/cld_3/protos/task_spec.pb.cc

)
target_include_directories(cld3 PUBLIC "${CLD3_JNI_DIR}")
# Protobuf 36.2's public parse_context.h includes utf8_validity.h, but its
# upstream lite target links that dependency privately. Propagate it here.
target_link_libraries(cld3 PUBLIC protobuf::libprotobuf-lite utf8_validity)
target_compile_definitions(cld3 PRIVATE HAVE_PTHREAD)
