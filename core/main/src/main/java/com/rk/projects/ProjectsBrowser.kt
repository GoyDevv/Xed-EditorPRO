package com.rk.projects

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rk.file.sandboxHomeDir
import com.rk.resources.drawables
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans the sandbox home — where the app creates projects and clones GitHub repositories — and
 * intelligently returns only the folders that are actually projects (a recognised project type, or a
 * git repository), each with the metadata shown in the Projects browser.
 */
object ProjectsBrowser {

    data class ProjectInfo(
        val dir: File,
        val name: String,
        val type: DetectedProjectType,
        val isGitRepo: Boolean,
        val createdAt: Long, // epoch millis; 0 when unknown
        val lastEdited: Long, // epoch millis
        val packageName: String?, // null when the project has none
        val sizeBytes: Long,
    )

    /** Directories that inflate size / mtime scans without saying anything about "last edited". */
    private val HEAVY = setOf(".git", "node_modules", "build", ".gradle", ".idea", "dist", "out", ".dart_tool")

    suspend fun scan(home: File = sandboxHomeDir()): List<ProjectInfo> =
        withContext(Dispatchers.IO) {
            val dirs = home.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
            dirs
                .mapNotNull { dir ->
                    val type = ProjectTypeDetector.detect(dir)
                    val isGit = File(dir, ".git").exists()
                    // Intelligent filter: only surface real projects, not arbitrary folders.
                    if (type == DetectedProjectType.UNKNOWN && !isGit) return@mapNotNull null
                    ProjectInfo(
                        dir = dir,
                        name = dir.name,
                        type = type,
                        isGitRepo = isGit,
                        createdAt = creationTime(dir),
                        lastEdited = lastEditedTime(dir),
                        packageName = readPackageName(dir),
                        sizeBytes = dirSize(dir),
                    )
                }
                .sortedByDescending { it.lastEdited }
        }

    private fun creationTime(dir: File): Long =
        runCatching {
                java.nio.file.Files
                    .readAttributes(dir.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
                    .creationTime()
                    .toMillis()
            }
            .getOrDefault(dir.lastModified())

    private fun lastEditedTime(dir: File): Long {
        var newest = dir.lastModified()
        dir.listFiles()?.forEach { child ->
            if (child.name in HEAVY) return@forEach
            newest = maxOf(newest, mostRecent(child, depth = 2))
        }
        return newest
    }

    private fun mostRecent(file: File, depth: Int): Long {
        var newest = file.lastModified()
        if (depth > 0 && file.isDirectory && file.name !in HEAVY) {
            file.listFiles()?.forEach { newest = maxOf(newest, mostRecent(it, depth - 1)) }
        }
        return newest
    }

    /** Best-effort package / applicationId (Android/Gradle → namespace/applicationId/manifest → package.json name). */
    private fun readPackageName(dir: File): String? {
        val gradleFiles =
            listOf(
                File(dir, "app/build.gradle.kts"),
                File(dir, "app/build.gradle"),
                File(dir, "build.gradle.kts"),
                File(dir, "build.gradle"),
            )
        val appId = Regex("""applicationId\s*=?\s*["']([\w.]+)["']""")
        val namespace = Regex("""namespace\s*=?\s*["']([\w.]+)["']""")
        for (f in gradleFiles) {
            if (!f.isFile) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            appId.find(text)?.groupValues?.getOrNull(1)?.let { return it }
            namespace.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        }
        val manifests =
            listOf(
                File(dir, "app/src/main/AndroidManifest.xml"),
                File(dir, "src/main/AndroidManifest.xml"),
                File(dir, "AndroidManifest.xml"),
            )
        val pkgAttr = Regex("""package\s*=\s*"([\w.]+)"""")
        for (m in manifests) {
            if (!m.isFile) continue
            val text = runCatching { m.readText() }.getOrNull() ?: continue
            pkgAttr.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        }
        File(dir, "package.json").takeIf { it.isFile }?.let { pj ->
            runCatching { pj.readText() }.getOrNull()?.let { text ->
                Regex(""""name"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.getOrNull(1)?.let { return it }
            }
        }
        return null
    }

    private fun dirSize(dir: File): Long =
        runCatching {
                var total = 0L
                dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
                total
            }
            .getOrDefault(0L)
}

private fun formatSize(bytes: Long): String {
    val mib = bytes / 1048576.0
    return if (mib >= 0.1 || bytes == 0L) String.format(Locale.US, "%.2f MiB", mib)
    else String.format(Locale.US, "%.0f KiB", bytes / 1024.0)
}

private fun formatDate(millis: Long): String =
    if (millis <= 0L) "—" else SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))

private fun formatRelative(millis: Long): String =
    if (millis <= 0L) "—"
    else DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/**
 * Bottom sheet listing every project/repository under the sandbox home, with type, dates, package
 * name and on-disk size. Tapping one opens it in the file tree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsBrowserSheet(onDismiss: () -> Unit, onOpenProject: (File) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var loading by remember { mutableStateOf(true) }
    val projects = remember { mutableStateListOf<ProjectsBrowser.ProjectInfo>() }

    LaunchedEffect(Unit) {
        loading = true
        val result = ProjectsBrowser.scan()
        projects.clear()
        projects.addAll(result)
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        painter = painterResource(drawables.folder_code),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Projects & Repositories",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text =
                            if (loading) "Scanning your workspace…"
                            else "${projects.size} project${if (projects.size == 1) "" else "s"} in your workspace",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            when {
                loading ->
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    }
                projects.isEmpty() ->
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = painterResource(drawables.folder),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(44.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "No projects yet — create one or clone a repository to get started.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                else ->
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        projects.forEach { project -> ProjectCard(project) { onOpenProject(project.dir) } }
                    }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: ProjectsBrowser.ProjectInfo, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        painter = painterResource(iconForProjectType(project.type)),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(9.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (project.type == DetectedProjectType.UNKNOWN) "Repository" else project.type.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (project.isGitRepo) {
                            Icon(
                                painter = painterResource(drawables.git),
                                contentDescription = "Git repository",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp).size(14.dp),
                            )
                        }
                    }
                }
                StatusPill(formatSize(project.sizeBytes), PillTone.NEUTRAL)
            }

            Spacer(Modifier.height(12.dp))
            project.packageName?.let { MetaLine(icon = drawables.folder_code, label = "Package", value = it) }
            MetaLine(icon = drawables.info, label = "Created", value = formatDate(project.createdAt))
            MetaLine(icon = drawables.edit, label = "Last edited", value = formatRelative(project.lastEdited))
        }
    }
}

@Composable
private fun MetaLine(icon: Int, label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
