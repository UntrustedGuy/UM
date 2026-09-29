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

### Network Storage

### Credential protection
Network-storage passwords, S3 access/secret keys, session tokens, and other persisted credentials are encrypted with an Android Keystore AES-256-GCM key. Legacy plaintext values are migrated once on first open and are not written back in plaintext. Non-secret endpoint/profile metadata remains in normal preferences.

## Network Storage Hub profile isolation (2026-09-29)

Saved Network Storage Hub locations now have a stable per-location ID. Opening a saved SFTP, SMB, WebDAV, or S3 location passes that ID into the protocol activity, so connection metadata and encrypted credentials are kept in a profile-specific preference namespace instead of being shared between unrelated saved locations. Existing hub entries without an ID receive one during migration and are persisted on the next save. The hub also passes the saved endpoint into the protocol connection dialog (including SFTP host/port parsing), so selecting a saved location no longer discards the endpoint and falls back to the last global connection.

Deleting a saved location clears its profile-specific protocol preferences as well, preventing orphaned credentials from remaining after the location is removed.
