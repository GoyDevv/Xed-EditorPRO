# XED-PRO

<img src="/fastlane/metadata/android/en-US/images/icon.png" alt="XED-PRO icon" width="90" height="90" align="left"/>

**XED-PRO** is a complete, self-contained mobile IDE for Android. It started as a fork of the
excellent [Xed-Editor](https://github.com/Xed-Editor/Xed-Editor) text editor, and grew into a full
development environment: create real projects, resolve toolchains, build and run them on-device
through a bundled Linux sandbox, and manage everything from a modern, redesigned UI.

![Android CI](https://github.com/GoyDevv/Xed-EditorPRO/actions/workflows/android.yml/badge.svg?event=push)
![Download count](https://img.shields.io/github/downloads/GoyDevv/Xed-EditorPRO/total?label=Downloads)

<br clear="left"/>

---

## Why XED-PRO?

Xed-Editor is a great editor. **XED-PRO turns it into an IDE.** Everything below is added on top of
the original editing experience:

| Capability | Xed-Editor | XED-PRO |
| --- | :---: | :---: |
| Syntax highlighting, LSP, multi-tab editing | ✅ | ✅ |
| Built-in terminal | ✅ | ✅ |
| **Create real projects from templates** | — | ✅ Android, Fabric/Forge mods, Python, Node.js, Web |
| **Build & Run on-device** (Gradle/npm/python) | — | ✅ one-tap, headless, in a Linux sandbox |
| **Dependency Manager** | — | ✅ install JDK/SDK/NDK/CMake/toolchains |
| **IDE Configuration** | — | ✅ switch JDK, pin NDK, per-project Gradle options |
| **Projects & Repositories browser** | — | ✅ |
| **Auto-resolved template versions** | — | ✅ Fabric/Forge/Android pinned to real, current versions |
| **Full Git workspace + GitHub integration** | basic | ✅ tabs, diffs, PRs, workflow runs |

### What makes it a real IDE

- **Project creation that actually builds.** Pick a template in a clean bottom sheet and get a
  complete, buildable project — not a stub. Android projects use the same proven toolchain the app
  itself ships with (AGP 9 / Kotlin 2.3 / Gradle 9.5) and let you choose the SDK (up to the latest
  API, labelled with its Android version). Fabric and Forge mods resolve real, published dependency
  versions for your chosen Minecraft version.
- **Build & Run in a bundled Linux sandbox.** A one-tap Run button detects the project type and runs
  it (Gradle, npm, Python, Go, Rust, static web) inside an exec-capable Ubuntu sandbox — headlessly,
  with a live output panel and a notification you can stop from anywhere. Android builds install the
  resulting APK for you.
- **Toolchain management, built in.** The Dependency Manager installs JDKs, the Android SDK/NDK,
  CMake, Node, Python and more, with a live install log. The IDE Configuration screen switches the
  active JDK, pins an NDK to a project, and exposes per-project Gradle options (build type, log
  level, and extra flags like `--stacktrace`).
- **Automatic setup.** Creating an Android project kicks off a Gradle sync that downloads the exact
  SDK platform your project targets before building, with an honest first-build heads-up.
- **Everything, organized.** Redesigned Dependency Manager, IDE Configuration and Create Project
  screens; a Projects & Repositories browser; import files into any folder from the file tree; and a
  terminal that opens in your selected project.

---

## Features

- **Editor** — syntax highlighting, LSP-powered code intelligence, multi-tab editing, project-wide
  search, fast file tree with import/copy/move.
- **Terminal** — a real Linux (Ubuntu) environment via proot; opens in your current project.
- **Build & Run** — project-aware Run/Sync with a live, stoppable output panel and notifications.
- **Project templates** — Android (Jetpack Compose), Minecraft mods (Fabric & Forge), Python,
  Node.js, and static web — all generated as complete, buildable projects.
- **Dependency Manager & IDE Configuration** — install and switch toolchains; per-project Gradle
  build type, log level and flags.
- **Git & GitHub** — Code / Diff / Actions tabs, commit with title + description, open pull
  requests, browse workflow runs, and manage the origin — all in-app.
- **Extensions & themes**, customizable app font, and more.

---

## Download

- **Latest build (XED-PRO):** from [Actions](https://github.com/GoyDevv/Xed-EditorPRO/actions/) →
  latest run → Artifacts → **XED-PRO**.
- **Releases:** from [Releases](https://github.com/GoyDevv/Xed-EditorPRO/releases).

[<img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" alt="Get it on GitHub" height="80">](https://github.com/GoyDevv/Xed-EditorPRO/releases/latest)

XED-PRO installs **alongside** the official Xed-Editor (distinct app id and name), so you can keep
both.

---

## Building the project

Build the **XED-PRO APK** (the standalone release build — always signed with the committed keystore
so every build installs as an update over the previous one; installs alongside other builds):

```bash
./gradlew assemblePro
```

The APK is written to `app/build/outputs/apk/pro/`.

You can also build a plain debug APK:

```bash
./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
```

### Build with Docker

If you don't have the Android SDK or JDK installed locally:

```bash
DOCKER_BUILDKIT=1 docker build --target export-stage --output ./out .
```

The output APK is generated under `out/`.

---

## Translations

Help translate the editor! Visit [Weblate](https://hosted.weblate.org/engage/xed-editor/) to get
started:

<a href="https://hosted.weblate.org/engage/xed-editor/">
    <img src="https://hosted.weblate.org/widgets/xed-editor/-/multi-auto.svg" alt="Translation Status">
</a>

---

## Community

[![Discord](https://img.shields.io/badge/Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/F2y8eva6e6)

---

## Credits & license

XED-PRO is a fork of [Xed-Editor](https://github.com/Xed-Editor/Xed-Editor) by the Xed-Editor
authors — huge thanks to them for the editor foundation. XED-PRO is a **separate project**: please
do not file XED-PRO issues on the upstream Xed-Editor repository.

Licensed under the terms in [LICENSE](LICENSE).
