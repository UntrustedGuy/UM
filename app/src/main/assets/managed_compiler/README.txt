Untrusted Manager managed C# compiler provisioning

UM supports a real C# -> managed DLL round trip through Mono on Android ARM64.

Preferred for offline/self-contained APK builds:
  tools/build-managed-compiler-android.sh

The build script produces the Android ARM64 Mono runtime plus its managed
class libraries/compiler and places them here. If those assets are present,
UM extracts them into private app storage on first Compile and never needs a
separate compiler application.

If the build does not redistribute the compiler binaries, UM has a pinned
first-run provisioning path for:
  Mono 6.12.0.90 / Android API 24 / ARM64
from the public IanusInferus/termux-mono release. The app downloads it over
HTTPS, checks the expected archive size, safely extracts it, validates the
runtime and compiler, and then uses it locally. Subsequent compilation is
offline unless the private compiler directory is deleted.

Mono is open-source. See the main README for the upstream project, release
reference, licensing notes, and the exact pinned download source.
