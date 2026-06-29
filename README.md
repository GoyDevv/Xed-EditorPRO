## Xed-Editor

<img src="/fastlane/metadata/android/en-US/images/icon.png" alt="Xed-Editor Icon" width="90" height="90" align="left"/>

**Xed-Editor** is a versatile and extensible text editor for Android, featuring syntax highlighting,
LSP-powered code intelligence, a built-in terminal, extensions, and fast project-wide tools for
efficient editing.

![Android CI](https://github.com/GoyDevv/Xed-EditorPRO/actions/workflows/android.yml/badge.svg?event=push&style=for-the-badge)
![Download count](https://img.shields.io/github/downloads/GoyDevv/Xed-EditorPRO/total?label=Downloads)

---

## Documentation

To learn more about Xed-Editor PRO‘s features and usage, visit the official

[![Discord](https://img.shields.io/badge/Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/F2y8eva6e6)
---

## Download

- **Latest Alpha Build**: Download from [Actions](https://github.com/GoyDevv/Xed-EditorPRO/actions/)
- **Latest Stable Build**: Download
  from [Releases](https://github.com/GoyDevv/Xed-EditorPRO/releases)

[<img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" alt="Get it on GitHub" height="80">](https://github.com/GoyDevv/Xed-EditorPRO/releases/latest)

---


## Building the Project


1. Build the **Debug APK** (signed with the included testkey):
   ```bash
   ./gradlew assembleDebug
   ```
   The compiled APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.
---

### Option 2: Building with Docker

If you do not have the Android SDK or JDK 21 installed locally, you can compile the project in a container using Docker.
This command builds the APK and exports it directly to your host machine:
```bash
DOCKER_BUILDKIT=1 docker build --target export-stage --output ./out .
```              
The output debug APK will be generated at `out/debug/app-debug.apk`.

---

## Translations

Help translate Xed-Editor! Visit [Weblate](https://hosted.weblate.org/engage/xed-editor/) to get
started:

<a href="https://hosted.weblate.org/engage/xed-editor/">
    <img src="https://hosted.weblate.org/widgets/xed-editor/-/multi-auto.svg" alt="Translation Status">
</a>

---

## Copyright

This IDE was forked from XedEditor Originally As a text editor. We are completly separate projects, please do not create issue about our IDE to their Project. 