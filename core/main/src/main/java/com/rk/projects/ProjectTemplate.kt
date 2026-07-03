package com.rk.projects

import com.rk.resources.drawables

/**
 * Available project templates exposed by the "Create Project" flow.
 *
 * [recommendsSandbox] is true for templates whose toolchain (gradle, native npm modules, python
 * venvs, etc.) must run from an exec-capable Linux filesystem. Android shared storage
 * (Documents/Downloads) is mounted noexec and ignores Unix permission bits, so build tooling
 * cannot run there. For those templates we default the location to the terminal sandbox home.
 *
 * [iconRes] and [description] drive the icon-based template chooser in the Create Project sheet.
 */
enum class ProjectTemplate(
    val displayName: String,
    val recommendsSandbox: Boolean,
    val iconRes: Int,
    val description: String,
    val showsPackageName: Boolean = false,
) {
    NONE("None", recommendsSandbox = false, iconRes = drawables.folder, description = "Empty project — just a folder to start from."),
    PYTHON3(
        "Python 3",
        recommendsSandbox = false,
        iconRes = drawables.python,
        description = "A Python 3 script with a ready-to-run main file.",
    ),
    PYTHON(
        "Python",
        recommendsSandbox = false,
        iconRes = drawables.python,
        description = "A minimal Python project.",
    ),
    NODEJS(
        "Node.js",
        recommendsSandbox = true,
        iconRes = drawables.javascript,
        description = "A Node.js app with package.json and an entry script.",
    ),
    WEB(
        "Static Web (HTML/CSS/JS)",
        recommendsSandbox = false,
        iconRes = drawables.html,
        description = "A static website — HTML, CSS and JavaScript.",
    ),
    MINECRAFT_MOD(
        "Minecraft Java Mod",
        recommendsSandbox = true,
        iconRes = drawables.java,
        description = "A Fabric or Forge mod, ready for Gradle.",
        showsPackageName = true,
    ),
    ANDROID_COMPOSE(
        "Android (Jetpack Compose)",
        recommendsSandbox = true,
        iconRes = drawables.android,
        description = "An Android app built with Jetpack Compose.",
        showsPackageName = true,
    );

    val showsMinecraftOptions: Boolean
        get() = this == MINECRAFT_MOD
}

/** Mod loaders supported by the Minecraft Java Mod template. */
enum class ModLoader(val displayName: String) {
    FABRIC("Fabric"),
    FORGE("Forge"),
}

/**
 * A fully resolved request to create a project. The dialog produces one of these and hands it to
 * [ProjectScaffolder].
 *
 * @param name project (and root directory) name.
 * @param template the chosen template.
 * @param parentDir directory in which the project root folder will be created.
 * @param packageName java/kotlin package (Minecraft / Android).
 * @param author author name written into manifests.
 * @param modLoader Fabric or Forge (Minecraft only).
 * @param modId Minecraft mod id (lowercase, used as the resources/config id).
 * @param modDescription human readable mod description.
 * @param modVersion mod artifact version.
 * @param minecraftVersion target Minecraft version (e.g. "1.21.1").
 * @param jdkVersion Java language/toolchain version (e.g. "17", "21").
 * @param sdkVersion Android compile/target SDK API level (e.g. "34"); blank uses a sensible default.
 */
data class ProjectConfig(
    val name: String,
    val template: ProjectTemplate,
    val parentDir: java.io.File,
    val packageName: String = "",
    val author: String = "",
    val modLoader: ModLoader? = null,
    val modId: String = "",
    val modDescription: String = "",
    val modVersion: String = "1.0.0",
    val minecraftVersion: String = "",
    val jdkVersion: String = "21",
    val sdkVersion: String = "",
    val initGit: Boolean = false,
) {
    /** Sanitised mod id derived from [modId] (falls back to the project name). */
    fun resolvedModId(): String {
        val base = modId.ifBlank { name }
        return base.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_').ifBlank { "modid" }
    }

    /** Lowercase alphanumeric slug of the author, used to build a package name. */
    fun authorSlug(): String = author.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "example" }

    /** Lowercase alphanumeric slug of the project name. */
    fun nameSlug(): String = name.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "app" }

    /**
     * Intelligently resolved package / applicationId. Uses the user-provided [packageName] when set,
     * otherwise derives `com.<author>.<name>` from the project name and author.
     */
    fun resolvedPackageName(): String =
        packageName.trim().ifBlank { "com.${authorSlug()}.${nameSlug()}" }
            .lowercase()
            .split(".")
            .filter { it.isNotBlank() }
            .joinToString(".") { it.replace(Regex("[^a-z0-9_]"), "") }

    /** Package as a relative path (e.g. com.example -> com/example). */
    fun packagePath(): String = resolvedPackageName().replace('.', '/')

    /** Android compile/target SDK API level; defaults to 34 when unset or invalid. */
    fun resolvedCompileSdk(): Int = sdkVersion.trim().toIntOrNull()?.takeIf { it in 1..99 } ?: 34

    /** Android minSdk — 24 by default, but never above the chosen compile SDK. */
    fun resolvedMinSdk(): Int = minOf(24, resolvedCompileSdk())
}
