#!/bin/bash
# 交叉编译 librime + 依赖，并链接出 libsplitkey_rime.so（JNI 桥）。
#
# 注意：本脚本按现有构建目录反推整理，开发机上已验证可用；
#       若在别的机器上失败，多数是某个依赖的 CMake 选项名不同，按报错调即可。
#
# 产出（build.sh 会用到）：
#   $WORK/out/libsplitkey_rime.so    → LIBSPLITKEY_RIME_SO
#   $WORK/librime/data/minimal       → RIME_DATA
#
# 首次跑要下载 NDK + 各依赖源码，编完 20~40 分钟。之后重编只要十几秒。
# 依赖：cmake ninja git curl unzip，以及主机侧 opencc（编简体字典用，见 build.sh）
set -e

WORK=${WORK:-$HOME/Project/splitkey-librime-build}
NDK_VER=${NDK_VER:-29.0.14206865}
ABI=${ABI:-arm64-v8a}
API=${API:-29}
BOOST_VER=${BOOST_VER:-1.86.0}

SRC=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$WORK"
cd "$WORK"

# ---------- 1. NDK ----------
if [ ! -d ndk ]; then
    echo "=== 下载 NDK r$NDK_VER ==="
    curl -L -o ndk.zip "https://dl.google.com/android/repository/android-ndk-r${NDK_VER}-linux.zip"
    unzip -q ndk.zip && mv "android-ndk-r${NDK_VER}" ndk && rm -f ndk.zip
fi

TOOLCHAIN=$WORK/ndk/build/cmake/android.toolchain.cmake
BIN=$WORK/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin
CXX=$BIN/aarch64-linux-android${API}-clang++

cm() {   # cm <srcdir> <builddir> [extra cmake args...]
    local src=$1 dir=$2; shift 2
    [ -d "$dir" ] && return 0
    cmake -S "$src" -B "$dir" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$WORK/sysroot" \
        -DBUILD_SHARED_LIBS=OFF "$@"
    cmake --build "$dir" && cmake --install "$dir"
}

# ---------- 2. librime 源码（含子模块 = 各依赖源码） ----------
if [ ! -d librime ]; then
    echo "=== 克隆 librime ==="
    git clone --depth 1 https://github.com/rime/librime.git librime
    ( cd librime && git submodule update --init --recursive )
fi
DEPS=$WORK/librime/deps

# ---------- 3. 依赖 -> sysroot ----------
# boost 是纯头文件：各库的 include/boost 要合并成一个 boost/ 目录
if [ ! -d "boost-$BOOST_VER" ]; then
    echo "=== 下载 boost $BOOST_VER ==="
    curl -L -o boost.tar.gz \
        "https://archives.boost.io/release/$BOOST_VER/source/boost_${BOOST_VER//./_}.tar.gz"
    tar xzf boost.tar.gz && rm -f boost.tar.gz
fi
if [ ! -d sysroot/include/boost ]; then
    echo "=== 合并 boost 头文件 ==="
    mkdir -p sysroot/include/boost
    for d in "boost-$BOOST_VER"/libs/*/include/boost; do
        [ -d "$d" ] && cp -r "$d"/* sysroot/include/boost/ 2>/dev/null || true
    done
fi

echo "=== 依赖 ==="
cm "$DEPS/marisa-trie" build/marisa
cm "$DEPS/leveldb"     build/leveldb  -DLEVELDB_BUILD_TESTS=OFF -DLEVELDB_BUILD_BENCHMARKS=OFF
cm "$DEPS/yaml-cpp"    build/yaml-cpp -DYAML_CPP_BUILD_TESTS=OFF \
                                       -DYAML_CPP_BUILD_TOOLS=OFF
# opencc 的脚本会自己编一份 marisa，用它的
cm "$DEPS/opencc"      build/opencc   -DBUILD_DOCUMENTATION=OFF -DENABLE_GTEST=OFF
# spdlog 由 librime 构建时带出来，不单独装

# ---------- 4. 编 librime ----------
if [ ! -f build-android/lib/librime.a ]; then
    echo "=== 编 librime ==="
    cmake -S librime -B build-android -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF -DSPDLOG_BUILD_SHARED=OFF \
        -DBoost_INCLUDE_DIR="$WORK/sysroot/include" \
        -DLevelDb_INCLUDE_PATH="$WORK/sysroot/include" \
        -DLevelDb_LIBRARY="$WORK/sysroot/lib/libleveldb.a" \
        -DMarisa_INCLUDE_PATH="$WORK/sysroot/include" \
        -DMarisa_LIBRARY="$WORK/sysroot/lib/libmarisa.a" \
        -DOpencc_INCLUDE_PATH="$WORK/sysroot/include" \
        -DOpencc_LIBRARY="$WORK/sysroot/lib/libopencc.a" \
        -DOpencc_DICT_DIR="$WORK/sysroot/share/opencc" \
        -DYamlCpp_INCLUDE_PATH="$WORK/sysroot/include" \
        -DYamlCpp_LIBRARY="$WORK/sysroot/lib/libyaml-cpp.a"
    cmake --build build-android
fi

# ---------- 5. 链接 JNI 桥 ----------
# 全静态依赖：APK 里放多个 .so 麻烦，一次链干净
echo "=== 链接 libsplitkey_rime.so ==="
mkdir -p out
"$CXX" -shared -fPIC -O2 -std=c++17 \
    -I"$WORK/librime/src" -I"$WORK/sysroot/include" \
    -o out/libsplitkey_rime.so "$SRC/jni/splitkey_rime.cpp" \
    -Wl,--start-group \
        build-android/lib/librime.a \
        build-android/deps/spdlog/libspdlog.a \
        sysroot/lib/libopencc.a sysroot/lib/libmarisa.a \
        sysroot/lib/libleveldb.a sysroot/lib/libyaml-cpp.a \
    -Wl,--end-group \
    -static-libstdc++ -llog -landroid
"$BIN/llvm-strip" --strip-unneeded out/libsplitkey_rime.so

echo
echo "=== 完成 ==="
ls -lh out/libsplitkey_rime.so
echo "词库：$WORK/librime/data/minimal"
echo
echo "接着编 APK："
echo "  LIBSPLITKEY_RIME_SO=$WORK/out/libsplitkey_rime.so \\"
echo "  RIME_DATA=$WORK/librime/data/minimal ./build.sh"
