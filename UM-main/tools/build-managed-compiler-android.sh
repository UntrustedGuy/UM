#!/usr/bin/env bash
set -euo pipefail

# Builds the self-contained Android ARM64 Mono compiler bundle used by UM's
# ManagedCompilerBackend. Run from a Linux build host with Android NDK r22+
# (or a compatible NDK), make, cmake, ninja, perl, python3, wget and tar.
# The resulting tree is copied to app/src/main/assets/managed_compiler.
#
# This follows the community-documented Termux/Android Mono build approach:
# https://github.com/IanusInferus/termux-mono

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/managed_compiler"
WORK="${WORK:-$ROOT/.managed-compiler-build}"
MONO_VERSION="${MONO_VERSION:-6.12.0.90}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
API="${ANDROID_API:-24}"
ARCH="aarch64"
TARGET="${ARCH}-linux-android"

if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "ANDROID_NDK_HOME/ANDROID_NDK_ROOT must point to an Android NDK." >&2
  exit 2
fi
command -v clang >/dev/null || { echo "clang is required" >&2; exit 2; }
command -v make >/dev/null || { echo "make is required" >&2; exit 2; }
command -v wget >/dev/null || { echo "wget is required" >&2; exit 2; }

mkdir -p "$WORK"
cd "$WORK"
TARBALL="mono-${MONO_VERSION}.tar.xz"
URL="https://download.mono-project.com/sources/mono/${TARBALL}"
[[ -f "$TARBALL" ]] || wget -O "$TARBALL" "$URL"
[[ -d "mono-${MONO_VERSION}" ]] || tar xf "$TARBALL"

SRC="$WORK/mono-${MONO_VERSION}"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"

# Build host Mono first. Mono's Android build disables mcs in the target stage,
# so the host stage supplies the managed class libraries and compiler artifacts.
HOST="$WORK/mono-${MONO_VERSION}-host"
ANDROID="$WORK/mono-${MONO_VERSION}-android"
HOST_INSTALL="$WORK/mono-${MONO_VERSION}-host-install"
ANDROID_INSTALL="$OUT"

rm -rf "$HOST" "$ANDROID" "$HOST_INSTALL" "$ANDROID_INSTALL"
mkdir -p "$HOST" "$ANDROID"
tar xf "$TARBALL" -C "$HOST" --strip-components=1
tar xf "$TARBALL" -C "$ANDROID" --strip-components=1

cd "$HOST"
./configure --disable-system-aot --prefix=/usr/local
make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)"
make install DESTDIR="$HOST_INSTALL"

# Build the Android ARM64 runtime.
cd "$ANDROID"
export CC="$CLANG --target=${TARGET}${API} --sysroot=$SYSROOT"
export CXX="$CLANG++ --target=${TARGET}${API} --sysroot=$SYSROOT"
export LDFLAGS="-lz -Wl,-rpath=\\$ORIGIN/../lib -Wl,--enable-new-dtags"
./configure --host="$TARGET" --prefix=/usr/local --disable-mcs-build \
  --with-btls-android-ndk="$NDK" --with-btls-android-api="$API" \
  --with-btls-android-cmake-toolchain="$NDK/build/cmake/android.toolchain.cmake"
make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)"
make install DESTDIR="$ANDROID_INSTALL"

# Copy the managed class libraries and compiler from the host stage into the
# Android bundle. Mono itself is native ARM64; these compiler components are
# portable managed assemblies executed by that runtime.
HOST_USR="$HOST_INSTALL/usr/local"
ANDROID_USR="$ANDROID_INSTALL/usr/local"
mkdir -p "$ANDROID_USR"
cp -a "$HOST_USR/lib" "$ANDROID_USR/"

# Keep the runtime's executable/library layout stable for UM.
if [[ -d "$ANDROID_USR/bin" ]]; then
  mkdir -p "$ANDROID_INSTALL/bin"
  cp -a "$ANDROID_USR/bin/." "$ANDROID_INSTALL/bin/"
fi
if [[ -d "$ANDROID_USR/etc" ]]; then
  mkdir -p "$ANDROID_INSTALL/etc"
  cp -a "$ANDROID_USR/etc/." "$ANDROID_INSTALL/etc/"
fi
if [[ -d "$ANDROID_USR/share" ]]; then
  mkdir -p "$ANDROID_INSTALL/share"
  cp -a "$ANDROID_USR/share/." "$ANDROID_INSTALL/share/"
fi

# Some Android builds put mono in libexec rather than bin.
if [[ ! -f "$ANDROID_INSTALL/bin/mono" ]]; then
  CANDIDATE="$(find "$ANDROID_INSTALL" -type f -name mono -perm -111 | head -1 || true)"
  if [[ -n "$CANDIDATE" ]]; then
    mkdir -p "$ANDROID_INSTALL/bin"
    cp "$CANDIDATE" "$ANDROID_INSTALL/bin/mono"
  fi
fi

if [[ ! -f "$ANDROID_INSTALL/bin/mono" ]]; then
  echo "Mono runtime was not produced at $ANDROID_INSTALL/bin/mono" >&2
  exit 3
fi
if [[ ! -f "$ANDROID_INSTALL/lib/mono/msbuild/Current/bin/Roslyn/csc.exe" && ! -f "$ANDROID_INSTALL/lib/mono/4.5/mcs.exe" ]]; then
  echo "No C# compiler found in host Mono class libraries." >&2
  exit 4
fi

chmod 0755 "$ANDROID_INSTALL/bin/mono"
# Remove build-time files that are not needed at runtime.
find "$ANDROID_INSTALL" -type f \
  \( -name '*.a' -o -name '*.la' -o -name '*.pdb' -o -name '*.mdb' \) -delete || true

echo "Managed compiler bundle ready: $ANDROID_INSTALL"
