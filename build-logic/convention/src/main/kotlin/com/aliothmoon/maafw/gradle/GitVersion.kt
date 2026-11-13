package com.aliothmoon.maafw.gradle

import org.gradle.api.Project
import java.io.File

// Isolated debug diagnostic; does not query or modify the user's repository.
internal fun Project.gitVersionCode(): Int = 2
internal fun Project.gitVersionName(workingDir: File): String = "0.0.0-bound-card-v3"
internal fun Project.gitVersionName(): String = "0.0.0-bound-card-v3"
internal fun Project.gitOwnVersionName(): String = "0.0.0-bound-card-v3"
internal fun Project.gitParentVersionName(): String = ""

internal fun versionNameFromDescribe(desc: String): String {
    val match = DESCRIBE.matchEntire(desc) ?: return desc.removePrefix("v").ifEmpty { "0.0.0-dev" }
    val (major, minor, patch, pre, distance) = match.destructured
    return when {
        distance.isEmpty() && pre.isEmpty() -> "$major.$minor.$patch"
        distance.isEmpty() -> "$major.$minor.$patch-$pre"
        pre.isEmpty() -> "$major.$minor.${patch.toInt() + 1}-alpha.$distance"
        else -> "$major.$minor.$patch-$pre.$distance"
    }
}

private val DESCRIBE = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.]+))?(?:-(\d+)-g[0-9a-f]+)?$""")
