<p align="center">
  <img src="./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="112" height="112" alt="Untrusted Manager icon">
</p>

<h1 align="center">Untrusted Manager</h1>

<p align="center">
  <strong>An Android power-user toolkit built on top of MP Manager.</strong>
</p>

<p align="center">
  <a href="https://github.com/UntrustedGuy/UM/releases"><img src="https://img.shields.io/github/v/release/UntrustedGuy/UM?style=for-the-badge&label=Download" alt="Latest release"></a>
  <a href="https://github.com/UntrustedGuy/UM/discussions"><img src="https://img.shields.io/badge/Discussions-GitHub-blue?style=for-the-badge&logo=github" alt="GitHub Discussions"></a>
  <img src="https://img.shields.io/badge/Android-Java%2017-green?style=for-the-badge" alt="Android Java 17">
</p>

## About

**Untrusted Manager (UM)** is a fork of [MP Manager](https://github.com/AbdurazaaqMohammed/MP-Manager), the open-source Android file manager created by **Abdurazaaq Mohammed**.

MP Manager provides the foundation for the project. UM keeps that foundation while adding a much larger power-user layer aimed at Android developers, APK modders, reverse engineers, people working with remote files, and anyone who wants a toolbox that lives in one app.

The main idea behind UM is simple: the file manager should be the place where everything connects. A file can be sent to a network service, opened in an editor, passed into a game-analysis tool, used by a terminal command, processed through MCP, or handed to a third-party plugin without turning the workflow into a pile of separate apps.

> **Upstream credit:** MP Manager was created by **Abdurazaaq Mohammed**. This project builds on that work and keeps the upstream project and its contributors credited.

## What UM adds

The sections below highlight the extra capabilities that make up the UM power-user layer.

---

# Android Power-User & Modding Tools

## APK Patcher

UM includes an integrated patching workspace for APK-related patch formats instead of treating patching as an external helper.

It provides a common entry point for supported **APK Editor**, **Lucky Patcher**, and **LPZIP-style** patch workflows.

### Lucky Patcher-style patching

- Apply supported **CLASSES** byte-pattern patches.
- Apply supported **ODEX** byte-pattern patches.
- Apply supported **LIB** byte-pattern patches.
- Work with patch definitions directly from the UM workflow.
- Keep patch execution inside the application rather than requiring a separate patcher APK.

## Root and Shizuku integration

UM extends the file-operation side of the app with elevated execution paths when the device provides them.

- Root-backed file operations where root access is available.
- Shizuku-backed file operations where Shizuku is authorized.
- Non-root operation remains available on ordinary devices.
- Elevated command execution is exposed through the same Tools Kit and terminal workflows.
- Operations are checked before committing filesystem changes rather than assuming an elevated command succeeded.

## Game Analyzer

The Game Analyzer is built for inspecting Android game packages and identifying the files that matter for further analysis.

It can work with APK/XAPK/APKM/APKS/AAB/ZIP-style inputs and can locate or report things such as:

- Unity/IL2CPP native libraries.
- Global metadata files.
- Native libraries.
- APK package structures.
- Candidate game-analysis inputs.

Analysis reports are written to the UM analysis workspace instead of requiring another application.

## IL2CPP Dumper

UM includes an integrated IL2CPP dumping workflow.

The dumper can accept the inputs that are available to the user instead of forcing a single rigid workflow:

- APK — optional context/input.
- `libil2cpp.so` — optional direct input when supplied separately.
- `global-metadata.dat` — optional direct input when supplied separately.

Dump output is placed under:

```text
0/Untrusted Manager/Dump/
```

The workflow is designed around explicit input validation and reports failures instead of claiming that a dump succeeded when the required metadata pair could not be processed.

## Global Metadata inspector

UM has a dedicated tool for inspecting `global-metadata.dat` files.

It can validate supported metadata structures and produce readable analysis output for the game-analysis workspace.

## Game Encryption tools

The Game Encryption tool provides a place to inspect protected or transformed game data and identify what the current tooling can understand.

It deliberately reports unsupported schemes instead of pretending that every protected asset has been decrypted.

## Game Modding Toolkit

The Game Modding Toolkit brings the game-oriented pieces of UM into one place:

- APK patching.
- APK extraction/editing workflows.
- DEX tooling.
- Native-library inspection.
- IL2CPP tooling.
- IL2CPP editing.
- Frida runtime helpers.
- Managed DLL editing.

## IL2CPP Editor

The IL2CPP editor provides a working area for edited IL2CPP dumps and native files.

- Open analysis output directly from the UM workspace.
- Edit supported generated/native data.
- Keep edited copies separate from the original input.
- Return edited results to the normal file-manager workflow.

## Frida Runtime Kit

UM includes a Frida-oriented runtime tool surface for game-analysis work.

- Generate IL2CPP-oriented Frida scripts.
- Work with script files from the UM file manager.
- Launch supported runtime workflows through the integrated tool surface.
- Keep runtime scripts and output inside the UM workspace.

## Managed DLL / PE / CIL editor

UM contains a dedicated managed-assembly workflow that goes well beyond simply opening a DLL as a binary file.

### Managed assembly inspection

- PE/COFF probing.
- CLR metadata detection.
- Metadata table parsing.
- Type navigation.
- Method navigation.
- Field navigation.
- Property navigation.
- Interface navigation.
- Generic type/member inspection.
- Decompiled C# document navigation.

### C# edit and rebuild workflow

A managed assembly can be reconstructed into editable C# source, edited, compiled, and written back as a new assembly.

Edited source is kept under:

```text
0/Untrusted Manager/DLL/EditedSource/
```

Generated assemblies are kept under:

```text
0/Untrusted Manager/DLL/Edited/
```

The original DLL is left untouched.

The managed compiler backend prefers the provisioned **Roslyn `csc.exe`** backend and can fall back to **Mono `mcs.exe`** where available.

### CIL / IL editing

The editor can decode supported CIL instructions and write supported changes back into a new assembly.

The writer handles larger replacement method bodies by relocating them into a dedicated `.umcil` PE section when necessary and reparses the generated image before accepting it.

Unsupported extra method-section and exception-handling layouts are rejected rather than being written with stale offsets.

### Native PE and hex work

Native/unmanaged PE files receive a proper PE inspection path instead of being incorrectly treated as malformed managed assemblies.

Raw hex editing is also available for binary files, malformed files, and files that do not expose managed metadata.

---

# Remote Storage & Network Workspaces

## Network Storage Hub

UM adds a unified remote-storage area for protocols that are outside the normal local/FTP workflow.

Saved locations are kept as separate profiles with stable IDs so one connection's settings do not accidentally overwrite another connection.

Supported backends include:

- **SMB / SMB2 / SMB3**
- **SFTP / SSH**
- **WebDAV**
- **S3-compatible object storage**

## SMB storage

- Browse SMB shares from the UM workspace.
- Authenticate against remote shares.
- Transfer files without leaving the app.
- SMB2/SMB3 signing is enabled by default in the hardened path.
- Downloads use temporary files before the final destination is committed.
- Resulting file sizes are checked after transfers.

## SFTP storage

UM's SFTP workflow uses SSH/SFTP rather than requiring a separate network browser.

- Browse remote directories.
- Upload files.
- Download files.
- Create remote directories.
- Rename remote entries.
- Delete remote entries.
- Pin a host fingerprint for normal connections.
- Use an explicit trust-any mode when the user deliberately chooses that setup.
- Verify staged transfers before finalizing them.

## WebDAV storage

The WebDAV client supports the normal file-management operations needed for a remote workspace:

- `PROPFIND` directory listing.
- Upload.
- Download.
- `MKCOL` directory creation.
- Delete.
- Move/rename.

The client also applies traversal and XML parser protections to remote paths and responses.

## S3-compatible storage

UM provides an S3-style object-storage browser with:

- Object listing.
- Upload.
- Download.
- Delete.
- Directory-like prefixes.
- Rename/move-style object workflows where the backend supports them.
- AWS Signature Version 4 request signing.
- Canonical query handling.
- Streaming transfers.

## Network Transfers

Remote operations are coordinated through a shared transfer queue.

- Queue multiple transfers.
- Cancel active work.
- Keep transfer history.
- Perform expensive network work away from the main UI thread.
- Keep remote activity visible as a single workflow instead of one-off progress dialogs.

## HTTP Remote Management

UM includes a dedicated HTTP remote-management service and client workflow.

The service exposes a browser-friendly remote file manager with authenticated operations and server-side path confinement.

- Bearer authentication.
- Remote file listing.
- Read/write operations.
- Copy/move/rename operations.
- Atomic text writes.
- Collision-aware destination handling.
- Symlink-ancestor rejection.
- Source and destination confinement.

The remote service is designed so path restrictions are enforced on the server side rather than being treated as a UI-only setting.

## Streamable HTTP MCP service

UM exposes a **Model Context Protocol (MCP)** service over Streamable HTTP.

This is intended for AI clients and automation systems that need to operate on files or APK workspaces through a controlled interface.

### File tools

- List files/directories.
- Read files.
- Write files.
- Copy files.
- Move files.
- Delete files.
- Enforce path permissions on the server.

### Path permissions

Each configured path can be governed by a server-side policy:

- **Read-only**
- **Read-write**
- **Denied**

The longest matching configured path wins, with the service applying canonical-path checks and rejecting symlink-based escapes.

The Home path is the default writable area for the local workspace unless the administrator changes it.

### APK MCP workspace

APK operations use isolated workspaces so automation does not directly mutate the original APK.

The APK MCP surface can:

- Create/open an APK workspace.
- List workspaces.
- Inspect workspace metadata.
- Delete workspaces.
- List APK entries.
- Read APK entries.
- Write APK entries.
- Delete APK entries.
- Search DEX classes.
- Read and edit Smali content.
- Reassemble supported changed DEX content.
- Inspect package/manifest metadata.
- Rebuild an APK workspace.
- Sign rebuilt output through the supported signing path.

### MCP authentication

UM supports controlled local use as well as authenticated remote use.

- Authentication can be deliberately disabled for a trusted local setup.
- Bearer-token authentication can be enabled.
- Named tokens can be created.
- Individual tokens can be revoked.
- Authentication state is persisted with the remote-service configuration.

---

# HTML & Editor Enhancements

## HTML preview

UM adds a dedicated HTML preview workflow instead of treating an HTML document only as plain source text.

HTML files can be opened from the file manager and sent into a dedicated preview surface, with the editor and preview workflows sharing the same file context.

## Embedded web-language syntax

The editor understands common embedded content inside HTML and Markdown files, including:

- CSS inside HTML.
- JavaScript inside HTML.
- JSON contained in script blocks.
- Code fences inside Markdown.

This makes mixed-language web documents much easier to edit without manually changing the editor language every time the cursor moves into another section.

## Streaming HTTP headers

UM provides a persistent header-rule system for HTTP requests used by supported streaming/metadata workflows.

Rules can target:

- A specific URL.
- A URL prefix.
- Requests globally.

The same rules are applied to both metadata probing and supported media playback requests.

---

# Expanded Batch Workflows

UM extends batch work beyond simple rename templates.

Supported batch-oriented workflows include:

- Multi-file copy.
- Multi-file move.
- Multi-file delete.
- Multi-file compression.
- Checksum operations over selected files.
- Batch command-helper execution for selected files.
- Batch image operations.

Selections are validated again when the operation starts so stale UI selection state does not silently become the wrong filesystem input.

---

# Extra Everyday Tools

UM also contains several small tools that are intentionally unrelated to APK editing. They are there because a power-user app is useful when you can do the small job without opening another app.

## Additional device tools

- **Strobe Light** — fullscreen flashing tool.
- **Screen Tester** — fullscreen colour/dead-pixel testing surface.

## Additional time & productivity tools

- **World Clock** — multi-zone time display.
- **Checklist** — saved task lists.
- **Habit Tracker** — streak tracking.
- **Expense Tracker** — personal spending log.
- **Attendance Tracker** — attendance percentages and required attendance calculations.
- **Typing Test** — typing speed and accuracy measurement.

These are part of the integrated UM Tools Kit and do not require separate helper applications.

---

# Backup & Service Lifecycle

## Cloud backup

UM includes a SAF-based backup and restore workflow that can target Android document providers, including cloud-backed providers exposed through the system file picker.

The backup format is versioned and validated before being restored.

Sensitive internal keys are deliberately excluded from portable backup data.

## Remote-service auto-start

Users can opt into restoring supported remote-management services after device boot or application package replacement.

The implementation accounts for modern Android foreground-service restrictions and promotes the service before expensive server initialization when a service needs to be restored.

Supported long-running services include the remote-management stack used by HTTP/MCP and the FTP/FTPS service lifecycle.

---

# Plugin SDK

UM has a real plugin boundary so developers can add tools without rebuilding the main application.

Plugins are **separately installed Android APKs**. Once installed and enabled, their tool entries appear inside the UM Tools Kit alongside the built-in tools.

The host API is intentionally small and stable.

## SDK module

The SDK lives in:

```text
plugin-api/
```

Primary API classes:

```text
untrusted.manager.um.plugin.api.UmPluginContract
untrusted.manager.um.plugin.api.UmPluginActivity
```

Current host API version:

```text
1
```

## Plugin discovery

A plugin exposes an activity using the UM entry-point action:

```text
untrusted.manager.um.plugin.ACTION_ENTRY
```

UM discovers installed plugins automatically, validates the plugin metadata, checks API compatibility, remembers enabled/disabled state, and adds enabled entries to Tools Kit.

A plugin can be updated independently of the UM base APK.

## Creating a plugin

### 1. Create a normal Android application project

A plugin is its own Android application module. It does not need to copy UM's private implementation classes.

During development inside the UM source tree, the API module can be referenced directly:

```gradle
dependencies {
    implementation project(':plugin-api')
}
```

For a completely separate project, package the compatible `plugin-api` library as part of the plugin project dependency setup.

### 2. Implement `UmPluginActivity`

A minimal plugin can look like this:

```java
package com.example.umplugin;

import android.os.Bundle;
import android.widget.TextView;

import untrusted.manager.um.plugin.api.UmPluginActivity;

public final class ExamplePluginActivity extends UmPluginActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView view = new TextView(this);
        view.setText("Hello from an Untrusted Manager plugin");
        view.setPadding(32, 32, 32, 32);
        setContentView(view);
    }
}
```

The base activity exposes:

```java
pluginId()
inputPath()
inputUri()
inputMime()
```

The helpers tell the plugin which plugin entry launched it and, when file context was supplied, which file/URI/MIME type was associated with the launch.

### 3. Register the entry activity

Add an exported activity to the plugin manifest:

```xml
<activity
    android:name=".ExamplePluginActivity"
    android:exported="true"
    android:label="Example Plugin">

    <intent-filter>
        <action android:name="untrusted.manager.um.plugin.ACTION_ENTRY" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>

    <meta-data
        android:name="untrusted.manager.um.plugin.id"
        android:value="example.tool" />

    <meta-data
        android:name="untrusted.manager.um.plugin.name"
        android:value="Example Tool" />

    <meta-data
        android:name="untrusted.manager.um.plugin.description"
        android:value="A demonstration UM feature plugin" />

    <meta-data
        android:name="untrusted.manager.um.plugin.category"
        android:value="Plugins" />

    <meta-data
        android:name="untrusted.manager.um.plugin.api"
        android:value="1" />

    <meta-data
        android:name="untrusted.manager.um.plugin.min_api"
        android:value="1" />
</activity>
```

### Plugin metadata

| Metadata | Required | Purpose |
|---|:---:|---|
| `untrusted.manager.um.plugin.id` | Yes | Stable plugin ID |
| `untrusted.manager.um.plugin.name` | Yes | Name shown to the user |
| `untrusted.manager.um.plugin.api` | Yes | API version implemented |
| `untrusted.manager.um.plugin.min_api` | Yes | Minimum host API required |
| `untrusted.manager.um.plugin.description` | No | Description shown in Tools Kit |
| `untrusted.manager.um.plugin.category` | No | Tools Kit category; defaults to Plugins |
| `untrusted.manager.um.plugin.icon` | No | Optional plugin icon metadata |

Plugin IDs are intended to remain stable across plugin updates and should contain only letters, digits, `.`, `_`, and `-`.

### File context

When a file-aware launch is available, the host can provide:

```java
UmPluginContract.EXTRA_INPUT_URI
UmPluginContract.EXTRA_INPUT_PATH
UmPluginContract.EXTRA_INPUT_MIME
```

The plugin ID is available through:

```java
UmPluginContract.EXTRA_ID
```

Example:

```java
String path = inputPath();
String mime = inputMime();
android.net.Uri uri = inputUri();
```

Treat every incoming URI/path as untrusted input and validate it before performing filesystem work.

### Recommended plugin structure

```text
ExamplePlugin/
├── app/
│   ├── src/main/java/com/example/umplugin/
│   │   └── ExamplePluginActivity.java
│   └── src/main/AndroidManifest.xml
├── build.gradle
└── settings.gradle
```

The plugin can contain as many internal activities/classes/resources as it needs. Only the entry contract needs to be exposed to UM.

### Plugin development rules

- Keep the plugin ID stable.
- Depend on the public `plugin-api` contract rather than UM private implementation packages.
- Validate all incoming URIs and paths.
- Do not assume file context exists.
- Do not assume root or Shizuku is available.
- Handle unsupported host API versions gracefully.
- Keep the plugin independent so it can be installed and updated on its own.

---

# Project Layout

```text
UM/
├── app/                         # Untrusted Manager Android application
├── plugin-api/                  # Stable third-party plugin contract
├── images/                      # README screenshots
├── LICENSE                      # Project license
└── README.md
```

The main application namespace is:

```text
untrusted.manager.um
```

The plugin API namespace is:

```text
untrusted.manager.um.plugin.api
```

---

# Building

UM is a standard Android Gradle project.

The application currently targets Java 17 with:

```text
compileSdk 36
targetSdk 37
minSdk 19
```

From a normal Android development environment:

```bash
./gradlew assembleDebug
```

For plugin development, build the plugin project using its own Android Gradle setup and include the compatible UM `plugin-api` library.

---

# Workspace Conventions

UM keeps application-created analysis and diagnostic data in an ordinary user-visible workspace.

Important locations include:

```text
0/Untrusted Manager/
├── Dump/                 # IL2CPP/game-analysis dump output
├── DLL/
│   ├── Edited/           # Generated managed assemblies
│   └── EditedSource/     # Editable reconstructed C# sources
├── frameworks/           # Optional Android framework resources
└── logs/                 # Application diagnostic logs
```

These folders are intended to make generated work easy to find, back up, and inspect from the file manager.

---

# License

Untrusted Manager is distributed under the **GNU General Public License v3.0 or later**. See [`LICENSE`](./LICENSE).

UM also contains and links against many separately licensed open-source projects. Their individual license texts are included where required by the corresponding project and are shown from the application's About/Credits UI.

---

# Credits

Untrusted Manager is a fork of **[MP Manager](https://github.com/AbdurazaaqMohammed/MP-Manager)** by **[Abdurazaaq Mohammed](https://github.com/AbdurazaaqMohammed)**. MP Manager is the foundation of this project, and its original authors and contributors remain credited.

UM also brings together a number of open-source libraries, projects, and technical references. Some are incorporated directly into the application, while others are used as references or external runtime dependencies for specific tools. The distinction matters: a project listed as a reference is not being claimed as code that was copied into UM.

## Upstream

| Project | Author / Team | Link | Used for |
|---|---|---|---|
| **MP Manager** | Abdurazaaq Mohammed | https://github.com/AbdurazaaqMohammed/MP-Manager | Base application and the foundation UM is forked from |

## Android, APK and editor libraries

| Project | Author / Team | Link | Used for |
|---|---|---|---|
| DEX Editor | Krushna Chandra | https://github.com/developer-krushna/Dex-Editor-Android | DEX browsing and editing |
| APKEditor | REAndroid | https://github.com/REAndroid/APKEditor | APK/archive processing |
| ApkCloner | Krushna Chandra | https://github.com/developer-krushna/ApkCloner | APK-related workflows |
| JKS Signature Generator | Krushna Chandra | https://github.com/developer-krushna/JKS-SignKey-Generator | Signing support |
| sun.security for Android | Muntashir Al-Islam | https://github.com/MuntashirAkon/sun-security-android | Java security compatibility |
| apksig for Android | Muntashir Al-Islam | https://github.com/MuntashirAkon/apksig-android | APK signature handling |
| Sora Editor | Rosemoe | https://github.com/Rosemoe/sora-editor | Code/text editing and syntax support |
| zip4j | Srikanth Lingala | https://github.com/srikanth-lingala/zip4j | ZIP processing |
| aXML | APK Explorer & Editor | https://github.com/apk-editor/aXML | Android XML/APK XML processing |
| jadx | skylot | https://github.com/skylot/jadx | Java/Dex decompilation support |
| smali / baksmali | JesusFreke and the Android Open Source Project | https://github.com/JesusFreke/smali | Smali assembly/disassembly support |
| AndroidX | Google | https://github.com/androidx/androidx | Android application components |
| Material Components | Google | https://github.com/material-components/material-components-android | Material UI components |
| Material Design Icons | Google | https://github.com/google/material-design-icons | Application icons |

## Game-analysis and IL2CPP references

The IL2CPP tooling in UM is informed by several public projects and their documented approaches. These projects deserve explicit credit because their work helped establish the formats, metadata structures, and workflows that this tooling works with.

| Project | Author / Team | Link | Relation to UM |
|---|---|---|---|
| **Il2CppDumper** | Perfare | https://github.com/Perfare/Il2CppDumper | IL2CPP metadata/layout and dumping reference |
| **Il2CppDumper** | Jumboperson | https://github.com/Jumboperson/Il2CppDumper | Earlier IL2CPP dumping work referenced by the broader ecosystem |
| **il2cpp-Dumper** | MyDearMoon | https://github.com/MyDearMoon/il2cpp-Dumper | Archive/envelope and modern IL2CPP workflow reference |
| **Il2CppMetadataExtractor** | CameroonD | https://github.com/CameroonD/Il2CppMetadataExtractor | Runtime metadata-recovery reference |
| **frida-il2cpp-bridge** | vfsfitvnm | https://github.com/vfsfitvnm/frida-il2cpp-bridge | Runtime IL2CPP scripting/dump workflow used by the Frida tool integration |
| **Frida** | Frida Project | https://frida.re/ | External runtime required by the Frida workflow |

The UM Game Analyzer also records these IL2CPP references in its generated analysis report so that analysis output retains attribution to the projects that informed the workflow.

## Managed DLL / .NET tooling

UM's DLL editor is its own integrated implementation for inspecting and editing managed assemblies and PE files. It was developed around the same kinds of workflows users commonly use with established .NET reverse-engineering tools.

| Project / technology | Author / Team | Link | Relation to UM |
|---|---|---|---|
| **dnSpy** | dnSpy contributors | https://github.com/dnSpy/dnSpy | Workflow/reference for managed assembly browsing and C# inspection |
| **ILSpy** | ICSharpCode | https://github.com/icsharpcode/ILSpy | Workflow/reference for managed assembly browsing and decompilation |
| **Mono / MSBuild** | Mono Project | https://www.mono-project.com/ | Managed compilation/runtime support |
| **termux-mono** | IanusInferus | https://github.com/IanusInferus/termux-mono | Android/Termux Mono bundle reference used by the managed compiler backend |
| **Roslyn** | Microsoft | https://github.com/dotnet/roslyn | C# compiler backend when available through the managed compiler environment |

The DLL editor should not be read as claiming that dnSpy or ILSpy source code is embedded in UM; they are credited as tooling references/influences for the managed-assembly workflow.

## Archive, networking and utility libraries

| Project | Author / Team | Link | Used for |
|---|---|---|---|
| Commons Collections | Apache Software Foundation | https://github.com/apache/commons-collections | Collection utilities |
| Guava | Google | https://github.com/google/guava | Java utility support |
| Gson | Google | https://github.com/google/gson | JSON serialization |
| Commons IO | Apache Software Foundation | https://github.com/apache/commons-io | File and stream utilities |
| Apache Commons Compress | Apache Software Foundation | https://commons.apache.org/proper/commons-compress | TAR/7z/archive support |
| Apache Commons Net | Apache Software Foundation | https://commons.apache.org/proper/commons-net | Network protocol support |
| JunRAR | JunRAR | https://github.com/junrar/junrar | RAR archive support |
| XZ for Java | Tukaani | https://tukaani.org/xz/java.html | XZ compression |
| Apache MINA FTP Server | Apache Software Foundation | https://github.com/apache/mina-ftpserver | Embedded FTP server |
| Android-EZ-FTP | lilincpp | https://github.com/lilincpp/Android-EZ-FTP | FTP functionality |
| ftp4j | Carlo Pelliccia / Sauron Software | http://www.sauronsoftware.it/projects/ftp4j | FTP client support |
| Volley | Google | https://github.com/google/volley | HTTP networking |
| SSHJ | SSHJ contributors | https://github.com/hierynomus/sshj | SSH/SFTP support |
| Bouncy Castle | The Legion of the Bouncy Castle | https://github.com/bcgit/bc-java | Cryptographic support |
| SLF4J | QOS.ch | https://github.com/qos-ch/slf4j | Logging API |
| java-diff-utils | java-diff-utils | https://github.com/java-diff-utils/java-diff-utils | Text/file difference processing |
| ANTLR | The ANTLR Project | https://github.com/antlr/antlr4 | Parsing support |
| joni | JRuby | https://github.com/jruby/joni | Regular-expression engine support |
| Markwon | noties | https://github.com/noties/Markwon | Markdown rendering |
| ZXing | zxing | https://github.com/zxing/zxing | QR/barcode processing |
| zxing-android-embedded | Journey Mobile | https://github.com/journeyapps/zxing-android-embedded | Android QR scanning UI |
| JCommander | Cedric Beust | https://github.com/cbeust/jcommander | Command-line argument parsing |

## Other credited projects

| Project | Author / Team | Link | Used for |
|---|---|---|---|
| Shizuku | RikkaApps | https://github.com/RikkaApps/Shizuku | Privileged Android API bridge |
| Screen Color Picker | codehasan | https://github.com/codehasan/ScreenColorPicker | Color-picker functionality |
| Layout Inspector | Ratul Hasan | https://github.com/AbdurazaaqMohammed/Layout-Inspector | Layout inspection |
| Viz.js | Mike Daines | https://github.com/mdaines/viz.js | Graph rendering |
| svg-pan-zoom | Andrea Leofreddi | https://github.com/ariutta/svg-pan-zoom | SVG navigation and zooming |

## Licensing

The relevant license texts for redistributed open-source components are included in the application assets where required. UM's in-app **About / Credits** screen provides the project name, author, repository, and license information for the libraries it directly tracks there.

For the exact licensing terms of an individual dependency, use that project's repository and the license text shipped with UM rather than treating this README as a replacement for the original license.

<p align="center">
  <strong>Untrusted Manager</strong><br>
  <sub>Built on MP Manager. Expanded for Android power users.</sub>
</p>
