# <img src="./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="48"> Untrusted Manager

A free dual pane, Material Design file manager for Android with focus on APKs and the goal to be an open source alternative to MT Manager

<p align="center">
  <img src="./images/Ss1.png" width="200" alt="Untrusted Manager screenshot"> <img src="./images/Ss2.png" width="200" alt="Untrusted Manager screenshot">
</p>

[![GitHub Release](https://img.shields.io/github/v/release/AbdurazaaqMohammed/MP-Manager?style=for-the-badge&logo=github&label=Download&color=purple)](https://github.com/UntrustedGuy/UM/releases)

[![Telegram Discussion](https://img.shields.io/badge/Telegram%20Discussion-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://github.com/UntrustedGuy/UM/discussions)
## Features

### File Manager

<details><summary>Dual pane navigation</summary>

Browse two folders side by side. This makes it easy to move or copy files from one pane to the other.

A separate home folder can be set for each pane.

There are back and forward buttons, button to sync both panes to the same folder, new file/folder button, parent folder button.

<!-- TODO: Add video
![Dual pane navigation](./images/navigation.mp4)
-->
</details>

<details><summary>Bookmarks and history</summary>

Add any folder to bookmarks and manage them from a bottom drawer. The drawer has tabs for bookmarks and navigation history and opens by swiping up on the bottom bar.

Bookmarks can be deleted by long pressing on one.

<p align="center">
  <img src="./images/bookmarks.png" width="200" alt="Bookmarks and history drawer">
  <br>
  <em>The bookmarks and history drawer</em>
</p>
</details>

<details><summary>Filtering, sorting and hidden files</summary>

Filter the current folder as you type. Sort by name, size, date or type, reverse order, and choose whether a sort applies only to the current folder or everywhere. You can hide files from the list, show or hide system hidden files such as dot folders, and edit the list of manually hidden files later.

<p align="center">
  <img src="./images/filter.png" width="200" alt="Filter the current folder"> <img src="./images/sort.png" width="200" alt="Sort dialog"> <img src="./images/hidefiles.png" width="200" alt="File hiding">
  <br>
  <em>Filter the current folder</em> &nbsp;·&nbsp; <em>Choose a sorting mode</em> &nbsp;·&nbsp; <em>Hide files from the list</em>
</p>
</details>

<details><summary>Advanced search</summary>

Search the current folder by file name and optionally recurse into subfolders. Advanced options include match case, regular expressions, searching for text inside file contents, and minimum or maximum file size. Recent searches are saved for quick reuse.

<p align="center">
  <img src="./images/search.png" width="200" alt="Search dialog">
  <br>
  <em>The advanced search dialog</em>
</p>
</details>

<details><summary>File operations</summary>

Create files and folders, rename, copy, move and delete with progress reporting.

Extract, add files in ZIP, APK, auto sign option in APK

Rename several files at once using templates with prefix, suffix, numbering and find/replace.

<!-- TODO: Add screenshots/videos
![Multi rename dialog](./images/multi-rename.jpg)
![Compress dialog](./images/compress.jpg)
-->
</details>

<details><summary>File properties and sharing</summary>

View type, size and last modified date, and copy any value to the clipboard with a long press. Share files or open them with another app.

<!-- TODO: Add screenshots/videos -->
</details>

### Media

<details><summary>Built-in audio and video player</summary>

Play audio and video files without leaving the app. A mini player dialog with artwork, seek bar and playback controls can play in the background or expand into a full player.

<!-- TODO: Add screenshots/videos
![Mini player](./images/mini-player.jpg)
![Full player](./images/full-player.mp4)
-->
</details>

<details><summary>Image viewer</summary>

Open images with swipe between pictures in directory. EXIF metadata is shown for supported files, images can be deleted or shared from the viewer.

<!-- TODO: Add screenshots/videos
![Image viewer](./images/image-viewer.jpg)
-->
</details>

### APK Tools

<details><summary>APK information and install</summary>

Tap an APK to see its icon, name, version code and name, package name, signature schemes used (V1, V2, V3, V4) and whether it is protected. You can view files inside the APK and more features outlined below.

Installing both regular and split APKS is supported.

<p align="center">
  <img src="./images/apkdialog.png" width="200" alt="APK info dialog">
  <br>
  <em>The APK info dialog</em>
</p>
</details>

<details><summary>Sign APK and split APKs</summary>

Sign APKs and split APKs with your own or default (Debug) key. Signing supports JKS and PKCS12 keystores as well as PK8/PEM keys, and new keys can be generated inside the app.

Automatic signing after modifying an APK can be toggled and configured.

Biometrics can be used as alternative to entering password every time.

<!-- TODO: Add screenshots/videos
![Sign settings](./images/sign-settings.jpg)
-->
</details>

<details><summary>Decompile, build and protect</summary>

All functions from [REAndroid APKEditor](https://github.com/REAndroid/APKEditor) are available: Decompile an APK, Build an APK from a decompiled folder, merge (AntiSplit), Refactor obfuscated resource names and Protect.

<p align="center">
  <img src="./images/decomp.png" width="200" alt="Decompiling">
  <br>
  <em>Decompiling</em>
</p>
</details>

<details><summary>Quick edit APK attributes</summary>

Change the launcher icon, app name, install location, version code and name, min SDK and target SDK quickly in a dialog. Every activity and property in the manifest can also be edited from a tree view, including disabling entries.

<p align="center">
  <img src="./images/quick-edit.png" width="200" alt="Quick edit attributes dialog">
  <br>
  <em>Fast edit attributes</em>
</p>
</details>

<details><summary>APK optimization and cloning</summary>

Optimize APKs by removing chosen files, with a default list of common tracker and metadata files that can be edited. You can [clone an APK](https://github.com/developer-krushna/ApkCloner) with a new package name.

<p align="center">
  <img src="./images/clone.png" width="200" alt="Clone APK dialog">
  <br>
  <em>The clone APK dialog</em>
</p>
</details>

<details><summary>Dex editing</summary>

Edit dex files with [DEX Editor Pro](https://github.com/developer-krushna/Dex-Editor-Android) by developer-krushna. When editing a dex file inside an APK you can choose which dex files to load. Saving asks whether to add the modified file back into the APK and sign it, and a .bak backup is created upon modifying an APK.

<p align="center">
  <img src="./images/multidex.png" width="200" alt="Dex selection"> <img src="./images/dexe.png" width="200" alt="Dex Editor">
  <br>
  <em>Choose which dex files to load</em> &nbsp;·&nbsp; <em>The integrated dex editor</em>
</p>
</details>

### Editing and Comparing

<details><summary>Text editor</summary>

A full text editor based on [Sora Editor](https://github.com/Rosemoe/sora-editor) with a customizable bottom bar, regex find and replace, and many editor features.

Binary Android XML (AXML) files can be decoded for editing and re-encoded on save automatically.

<p align="center">
  <img src="./images/axml.png" width="200" alt="AXML decoded in the editor">
  <br>
  <em>Editing a decoded AXML file</em>
</p>
</details>

<details><summary>Compare tools</summary>

Compare two text files, two ZIP/APK files, or two resources.arsc files. Select one item in each pane and the matching compare option appears in the file menu.

<p align="center">
  <img src="./images/compared.png" width="200" alt="Compare ARSC"> <img src="./images/diff.png" width="200" alt="Diff view">
  <br>
  <em>Comparing resources.arsc files</em> &nbsp;·&nbsp; <em>The diff view</em>
</p>
</details>

### APK Extractor

<details><summary>Extract and share APK parts</summary>

Extract APKs in batch and pull out specific parts: the app icon, resources.arsc, classes.dex, AndroidManifest.xml, base.apk, splits and native libs, as well as the launch activity. Split APKs can be merged into a single APK before extracting, and anything can be shared directly.

<!-- TODO: Add screenshots/videos -->
</details>

### Managed DLL / .NET Assembly Editor

Untrusted Manager includes a self-contained Android managed-assembly browser and decompiler. The implementation runs inside UM and does not require a separate desktop decompiler or companion application.

<details><summary>PE/CLR detection and safe opening</summary>

The editor probes PE/COFF and CLR metadata before attempting decompilation. It distinguishes invalid/non-PE input, native/unmanaged PE files with no CLR directory, PE files containing a CLR directory but unreadable or malformed metadata, and managed .NET/CLI assemblies with a readable metadata root.

When the normal CLR metadata RVA is stale or rewritten, UM searches for valid `BSJB` metadata roots and retries parsing. Parser failures include the PE/CLR diagnostic and the underlying metadata error.
</details>

<details><summary>Assembly tree, navigation and search</summary>

Open `.dll` or `.exe` files from the UM file picker. Managed assemblies are represented as namespaces/types/members with fields, properties, methods, interfaces and nested types. Generic type and method parameters are recognized, and common type kinds are rendered as classes, structs, interfaces, enums or delegates when metadata permits.

The tree is opened from the fixed top-left menu button, overlays the source area, and has its own close button. Closing the tree returns the full-width source editor; the source document remains the only document-scrolling surface.

The search field filters type, method, field and property names.
</details>

<details><summary>C# reconstruction</summary>

The managed reader parses PE/COFF headers, the CLR header, ECMA-335 metadata streams, metadata tables, signatures, generic parameters, interfaces, fields, properties, parameters and method bodies.

The renderer reconstructs common CIL operations including constants, strings, locals, arguments, arithmetic and bitwise expressions, field access, calls, constructors, arrays, conversions, comparisons, conditional branches, switches, returns and exceptions. Unsupported or ambiguous instructions remain visible as explicit IL comments/fallbacks instead of being silently converted into invented source semantics.

The source is displayed through UM's Sora-based professional editor with the C# TextMate grammar and the active UM editor theme. C# source is an actual editable document: users can modify the reconstructed source, save the edited `.cs` source under `0/Untrusted Manager/DLL/EditedSource/`, and use **Compile** to produce a new managed DLL.
</details>

<details><summary>CIL editing and real DLL write-back</summary>

The **IL** action opens the selected method's decoded CIL in the same Sora editor and enables editing. **Save** performs a real ECMA-335 method-body patch into a new DLL under `0/Untrusted Manager/DLL/Edited/`; the original input file is not overwritten. The writer preserves the existing PE/CLI metadata and method RVA and replaces only the selected method's code bytes.

The writer now supports **relocating a rewritten method body** when it no longer fits in the original method region. It builds a valid fat ECMA-335 method header, preserves the original local-signature token/init-locals state, appends a dedicated `.umcil` executable/readable PE section when necessary, updates the MethodDef.RVA metadata cell, increments the PE section count and updates `SizeOfImage`. This means a replacement CIL body can be larger than the original method without overwriting adjacent methods or requiring a fake in-place patch.

Short/long branches, `switch`, numeric constants, variable operands, metadata-token operands, string tokens, prefixes and the supported opcode set are encoded back to bytes. Saved assemblies are reparsed immediately before the success result is reported; a structurally invalid rewritten image therefore fails the save operation rather than being presented as successful.

Methods containing additional exception-handling/extra method sections are still deliberately rejected because their clause offsets must be rewritten against the new instruction layout. The implementation does **not** silently copy stale EH offsets. This is the remaining major writer limitation before arbitrary managed methods can be relocated safely.
</details>

<details><summary>CIL view and instruction decoding</summary>

Select a method and use **IL** to view decoded CIL with instruction offsets, branch targets and resolved metadata tokens where available. The decoder handles short/long branches, `switch`, token-bearing instructions, integer constants and floating-point constants, and uses the same professional editor with the IL syntax grammar.
</details>

<details><summary>Native PE and Hex inspection</summary>

Native/unmanaged PE files are not reported as a generic managed-DLL failure. UM produces a PE inspection document containing the detected architecture, PE format, section information and CLR-directory state. **Hex** remains available for raw PE/native binaries and for protected or malformed managed files that cannot be reconstructed.
</details>

<details><summary>Export and performance</summary>

**Export** writes reconstructed C# for the managed assembly to `0/Untrusted Manager/DLL/Decompiled/`. **Save** writes edited method CIL to a separate DLL under `0/Untrusted Manager/DLL/Edited/`. Large assemblies and write operations run on a dedicated background executor so metadata decoding and file patching do not block the Android UI. The executor is shut down with the activity to avoid retaining work after the screen closes.
</details>

> **Implementation boundary:** UM's managed-DLL backend is an independently written Java/Android implementation. It is not a copied ILSpy or dnSpy implementation and does not claim feature-for-feature parity with those desktop projects. ILSpy provides a mature C# decompiler engine and dnSpyEx combines an ILSpy-based decompiler with dnlib/Roslyn-oriented assembly editing and debugging; those projects are feature/architecture references, not bundled source.

#### Managed DLL hardening, IL writer and C# editing — 2026-09-30

The latest DLL Editor pass upgrades the CIL writer from an in-place patcher to a relocating PE/CLI method-body writer. The pass corrected TypeDef namespace/name assignment, prevented nested types from being duplicated in the assembly tree and whole-assembly export, added interface and generic-parameter metadata, improved class/struct/interface/enum/delegate rendering, improved visibility/modifier rendering, added floating-point and prefix-opcode decoding, strengthened PE optional-header and metadata-stream bounds checks, preserved string metadata tokens in the IL view for round-tripping, and added a real method-body patch/save path.

The writer intentionally refuses unsafe cases instead of silently producing broken DLLs: methods with extra exception-handling sections and replacement bodies larger than the existing method code region are rejected. Saved files are written as new copies under `Untrusted Manager/DLL/Edited/`; the source DLL is preserved. C# write-back is now implemented through the separate managed compiler backend described below; it intentionally produces a newly compiled assembly rather than mutating the original PE in place.

The parser/writer backend passes standalone Java compilation and opcode-assembly smoke tests. The C# editor now supports real text editing and source-file persistence without falsely treating source edits as DLL recompilation. Full C# → DLL recompilation is represented by the Compile action and managed compiler backend. The compiler bundle is provisioned into UM private storage either from packaged assets or, when the build does not redistribute it, by downloading the pinned ARM64 Mono bundle on first use. Full Android Gradle compilation still depends on the build environment having the required Gradle distribution and dependencies available; an APK build is not treated as feature-completion evidence.


### C# compiler backend integration — 2026-10-01

The DLL Editor now contains a real compiler-backend integration point rather than a fake C# write-back button. `ManagedCompilerBackend` executes a self-contained Android ARM64 Mono runtime from UM private storage and prefers Roslyn `csc.exe`, falling back to `mcs.exe` when Roslyn is unavailable. The **Compile** action writes the edited C# document to a private compiler workspace, invokes the embedded compiler, validates the resulting PE/CLR image with UM's managed-assembly probe/parser, and then opens the newly produced DLL. Compiler stdout/stderr and failures are surfaced instead of silently producing an invalid file.

The compiler runtime itself is not source code that can be replaced by a Java implementation: Mono supplies the CLR runtime and C# compiler. The repository includes `tools/build-managed-compiler-android.sh` for producing a fully bundled offline compiler. In addition, the app has a pinned first-run provisioning path for the published ARM64 Mono 6.12.0.90 archive; it downloads the archive over HTTPS, enforces the expected archive size, safely extracts it into UM private storage, validates `mono` plus `mcs`/Roslyn, and then compiles locally. No separate compiler application is installed.

The build script follows the documented Android/Termux Mono architecture and uses the host Mono stage to provide the managed compiler/class libraries for the Android runtime. It must be run on a build host with an Android NDK and the required native build tools when an entirely offline/self-contained APK is desired. The normal first-run provisioning path does not require Termux or a companion app.

The compiler path produces a newly compiled managed DLL rather than attempting to patch arbitrary C# text directly into the original PE. This is necessary because C# compilation changes metadata, method bodies, references, and assembly layout. The original input DLL remains untouched.


### Network Storage

### Credential protection
Network-storage passwords, S3 access/secret keys, session tokens, and other persisted credentials are encrypted with an Android Keystore AES-256-GCM key. Legacy plaintext values are migrated once on first open and are not written back in plaintext. Non-secret endpoint/profile metadata remains in normal preferences.

## Network Storage Hub profile isolation (2026-09-29)

Saved Network Storage Hub locations now have a stable per-location ID. Opening a saved SFTP, SMB, WebDAV, or S3 location passes that ID into the protocol activity, so connection metadata and encrypted credentials are kept in a profile-specific preference namespace instead of being shared between unrelated saved locations. Existing hub entries without an ID receive one during migration and are persisted on the next save. The hub also passes the saved endpoint into the protocol connection dialog (including SFTP host/port parsing), so selecting a saved location no longer discards the endpoint and falls back to the last global connection.

Deleting a saved location clears its profile-specific protocol preferences as well, preventing orphaned credentials from remaining after the location is removed.


## Framework resources

Untrusted Manager no longer references compile-time `R.raw.android_*` framework
binaries. Framework APKs are discovered from `0/Untrusted Manager/frameworks/`.
Existing user-supplied framework APKs are preserved. On devices that expose
`/system/framework/framework-res.apk`, the app seeds the current Android API
level as a fallback when the framework directory is empty. This keeps the
source distribution buildable without embedding proprietary/system framework
binaries and avoids creating invalid placeholder APK resources.
## Credits and open-source references

Untrusted Manager is an independent open-source project. The projects below are credited because UM uses their libraries in other parts of the application, or because their public feature sets and architecture were consulted while implementing compatible functionality.

### Managed DLL / .NET references

- **[ILSpy](https://github.com/icsharpcode/ILSpy)** — open-source, cross-platform .NET assembly browser and decompiler. ILSpy is MIT licensed. Its documented C# decompilation, assembly navigation, metadata inspection and whole-project workflows were used as feature references. UM does **not** bundle ILSpy's decompiler source in the Java managed-DLL backend.
- **[backtrip](https://github.com/yingkitw/backtrip)** — Apache-2.0 Rust .NET/JVM decompiler reference evaluated for a future native Android decompiler backend. Its zero-runtime native architecture is particularly relevant to on-device use; UM does not copy its source into the current Java backend.
- **[dnSpyEx](https://github.com/dnSpyEx/dnSpy)** — continuation of the dnSpy assembly editor/debugger. The documented assembly-tree, IL, metadata, editing and debugging feature surface was used as a compatibility/reference target. dnSpy is GPLv3; UM does **not** copy dnSpy's GPL implementation into the managed-DLL parser.
- **[Mono.Cecil](https://github.com/jbevain/cecil)** — open-source ECMA CIL/metadata library consulted as a metadata-format reference. Mono.Cecil is MIT/X11 licensed; its .NET implementation is not bundled into UM's Java parser.
- **[ECMA-335](https://www.ecma-international.org/publications-and-standards/standards/ecma-335/)** — CLI specification used as the authoritative reference for PE/CLI metadata, signatures, tables and CIL behavior.
- **[Microsoft System.Reflection.Metadata documentation](https://learn.microsoft.com/dotnet/api/system.reflection.metadata.ecma335.tableindex)** — metadata table-index reference used to verify ECMA-335 table numbering and structures.

### UM managed-DLL implementation credits

The following parts are **UM-original implementation work**:

- PE/COFF and CLR probing;
- CLR metadata-root recovery;
- ECMA-335 metadata-table reader;
- type/member/generic/interface model;
- CIL decoder and token resolution;
- conservative C# reconstruction layer;
- assembly tree and search UI;
- Sora editor integration;
- C#/IL TextMate grammars;
- background parsing and export path;
- native PE inspection fallback;
- conservative ECMA-335 CIL method-body writer and safe edited-DLL output path.

No ILSpy, dnSpy/dnSpyEx or Mono.Cecil source files were copied into these implementation files.

### Other open-source components used by UM

- **[Sora Editor](https://github.com/Rosemoe/sora-editor)** — Android professional text/code editor used for UM's source-editing surface.
- **[REAndroid APKEditor](https://github.com/REAndroid/APKEditor)** — APK decompilation/build functionality integrated elsewhere in UM.
- **[DEX Editor](https://github.com/developer-krushna/Dex-Editor-Android)** — DEX editing functionality integrated elsewhere in UM.- **Mono** — open-source ECMA CLI/.NET implementation used as the planned embedded Android ARM64 compiler/runtime backend. UM invokes its compiler through `ManagedCompilerBackend`; Mono is not copied into the Java source tree.

