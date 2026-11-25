# Native side of Compendium for Android: the Switch app's core (CPK builder, mod formats, library,
# GameBanana client) plus the engine and its JNI bridge, in one libcompendium.so.
#
# libarchive (zip/7z/rar) and liblzma (7z's compression) are not in the NDK: they are downloaded
# and built here the first time, for the ABI being built. This needs internet access once.
cmake_minimum_required(VERSION 3.22.1)
project(compendium LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# android/app/src/main/cpp -> the repository root
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)

# ------------------------------------------------------------------ third-party libraries
include(ExternalProject)
set(DEPS "${CMAKE_BINARY_DIR}/deps")
file(MAKE_DIRECTORY "${DEPS}/include")

# DOWNLOAD_EXTRACT_TIMESTAMP only exists from CMake 3.24; the SDK's CMake is 3.22.
if(CMAKE_VERSION VERSION_GREATER_EQUAL 3.24)
    set(EP_TIMESTAMP DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
else()
    set(EP_TIMESTAMP "")
endif()

set(DEP_ARGS
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM}
    -DANDROID_STL=c++_static
    -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_INSTALL_PREFIX=${DEPS}
    -DCMAKE_PREFIX_PATH=${DEPS}
    -DCMAKE_FIND_ROOT_PATH=${DEPS}
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DBUILD_SHARED_LIBS=OFF
    -DBUILD_TESTING=OFF)

# liblzma, from xz 5.4.7 (not 5.6.0/5.6.1, which shipped a backdoor).
ExternalProject_Add(xz_ep
    URL https://github.com/tukaani-project/xz/releases/download/v5.4.7/xz-5.4.7.tar.gz
        https://tukaani.org/xz/xz-5.4.7.tar.gz
    ${EP_TIMESTAMP}
    CMAKE_GENERATOR ${CMAKE_GENERATOR}
    CMAKE_ARGS ${DEP_ARGS}
        -DXZ_TOOL_XZ=OFF -DXZ_TOOL_XZDEC=OFF -DXZ_TOOL_LZMADEC=OFF -DXZ_TOOL_LZMAINFO=OFF
        -DXZ_TOOL_SCRIPTS=OFF -DXZ_TOOL_SYMLINKS=OFF -DXZ_NLS=OFF
    BUILD_BYPRODUCTS ${DEPS}/lib/liblzma.a)

# libarchive 3.7.7: only the readers we need (zip, 7z, rar, rar5), with zlib from the NDK and liblzma from above.
ExternalProject_Add(libarchive_ep
    DEPENDS xz_ep
    URL https://github.com/libarchive/libarchive/releases/download/v3.7.7/libarchive-3.7.7.tar.gz
    ${EP_TIMESTAMP}
    CMAKE_GENERATOR ${CMAKE_GENERATOR}
    CMAKE_ARGS ${DEP_ARGS}
        -DENABLE_LZMA=ON
        -DLIBLZMA_INCLUDE_DIR=${DEPS}/include
        -DLIBLZMA_LIBRARY=${DEPS}/lib/liblzma.a
        -DLIBLZMA_LIBRARY_RELEASE=${DEPS}/lib/liblzma.a
        -DENABLE_ZLIB=ON
        -DENABLE_BZip2=OFF -DENABLE_LZ4=OFF -DENABLE_LZO=OFF -DENABLE_ZSTD=OFF -DENABLE_LIBB2=OFF
        -DENABLE_OPENSSL=OFF -DENABLE_MBEDTLS=OFF -DENABLE_NETTLE=OFF -DENABLE_CNG=OFF
        -DENABLE_LIBXML2=OFF -DENABLE_EXPAT=OFF -DENABLE_PCREPOSIX=OFF -DENABLE_PCRE2POSIX=OFF
        -DENABLE_ICONV=OFF -DENABLE_ACL=OFF -DENABLE_XATTR=OFF
        -DENABLE_TAR=OFF -DENABLE_CPIO=OFF -DENABLE_CAT=OFF -DENABLE_UNZIP=OFF
        -DENABLE_TEST=OFF -DENABLE_COVERAGE=OFF -DENABLE_WERROR=OFF
    BUILD_BYPRODUCTS ${DEPS}/lib/libarchive.a)

add_library(lzma_static STATIC IMPORTED)
set_target_properties(lzma_static PROPERTIES IMPORTED_LOCATION ${DEPS}/lib/liblzma.a)
add_library(archive_static STATIC IMPORTED)
set_target_properties(archive_static PROPERTIES
    IMPORTED_LOCATION ${DEPS}/lib/libarchive.a
    INTERFACE_INCLUDE_DIRECTORIES ${DEPS}/include
    INTERFACE_LINK_LIBRARIES "lzma_static;z")
add_dependencies(archive_static libarchive_ep)

# ------------------------------------------------------------------ the Switch app's core
file(GLOB CORE_SOURCES "${REPO_ROOT}/source/core/*.cpp")
list(FILTER CORE_SOURCES EXCLUDE REGEX "http_curl\\.cpp$")  # replaced by http_android.cpp
add_library(mmcore STATIC ${CORE_SOURCES})
target_include_directories(mmcore PUBLIC "${REPO_ROOT}/source/core" "${REPO_ROOT}/source/third_party")
target_compile_definitions(mmcore PUBLIC MM_ANDROID=1)
target_compile_options(mmcore PRIVATE -Wall -Wextra)
add_dependencies(mmcore libarchive_ep xz_ep)  # archive.h must exist before archive.cpp compiles
target_link_libraries(mmcore PUBLIC archive_static)

# ------------------------------------------------------------------ the engine + JNI
add_library(compendium SHARED engine.cpp http_android.cpp jni_bridge.cpp)
target_include_directories(compendium PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(compendium PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(compendium PRIVATE mmcore log)
target_link_options(compendium PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
