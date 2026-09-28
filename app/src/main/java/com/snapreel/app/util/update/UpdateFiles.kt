package com.snapreel.app.util.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A downloaded update that passed validation, recorded in the `pending.json` sidecar. */
data class PendingUpdate(
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val title: String,
    val notes: String,
    val fileName: String,
)

/** One file in the updates directory. */
data class UpdateDirEntry(val name: String, val sizeBytes: Long)

/** Pure rules for which update files are stale (design › In-app update, `UpdateFiles`). */
object UpdateFilesPolicy {
    const val SIDECAR = "pending.json"

    /** Pre-1.3 download names, left in `cacheDir` or `externalCacheDir`. */
    fun isLegacy(name: String): Boolean =
        name.startsWith("snapreel_update") && name.endsWith(".apk", ignoreCase = true)

    /**
     * Whether [pending] describes a complete update that is still worth installing: its APK is in
     * [entries] with the recorded size, and its versionCode is higher than [installedVc].
     */
    fun isPendingValid(pending: PendingUpdate?, entries: List<UpdateDirEntry>, installedVc: Long): Boolean {
        if (pending == null) return false
        val name = pending.fileName
        if (name.isEmpty() || '/' in name || name == SIDECAR || !name.endsWith(".apk") ) return false
        if (pending.versionCode <= installedVc) return false
        return entries.any { it.name == name && it.sizeBytes == pending.sizeBytes && it.sizeBytes > 0 }
    }

    /**
     * Whether [entry] in the updates directory is stale. Only a valid pending APK and its sidecar
     * are kept; partial downloads (`.part`), APKs without a valid sidecar, orphan or unreadable
     * sidecars, updates that aren't newer than [installedVc] and unknown files are all stale.
     * [pending] is the parsed sidecar, or null when it's missing or unreadable.
     */
    fun isStale(entry: UpdateDirEntry, entries: List<UpdateDirEntry>, pending: PendingUpdate?, installedVc: Long): Boolean {
        if (!isPendingValid(pending, entries, installedVc)) return true
        return entry.name != SIDECAR && entry.name != pending!!.fileName
    }

    fun parse(json: String): PendingUpdate? = try {
        val o = JSONObject(json)
        PendingUpdate(
            versionName = o.getString("versionName"),
            versionCode = o.getLong("versionCode"),
            sizeBytes = o.getLong("sizeBytes"),
            title = o.optString("title", ""),
            notes = o.optString("notes", ""),
            fileName = o.getString("fileName"),
        )
    } catch (_: JSONException) {
        null
    }

    fun serialize(p: PendingUpdate): String = JSONObject()
        .put("versionName", p.versionName)
        .put("versionCode", p.versionCode)
        .put("sizeBytes", p.sizeBytes)
        .put("title", p.title)
        .put("notes", p.notes)
        .put("fileName", p.fileName)
        .toString()
}

/**
 * Update downloads live in `cacheDir/updates/` (internal, never visible to the gallery).
 * A validated download gets a `pending.json` sidecar, so it survives a process restart (e.g. the
 * system reclaiming the app while the user grants "Install unknown apps").
 */
@Singleton
class UpdateFiles(
    val dir: File,
    private val legacyDirs: List<File>,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        File(context.cacheDir, "updates"),
        listOfNotNull(context.cacheDir, context.externalCacheDir),
    )

    private val sidecar: File get() = File(dir, UpdateFilesPolicy.SIDECAR)

    private fun safe(version: String) = version.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun apkFileFor(versionName: String) = File(dir, "snapreel-${safe(versionName)}.apk")

    fun partFileFor(versionName: String) = File(dir, "snapreel-${safe(versionName)}.apk.part")

    private fun entries(): List<UpdateDirEntry> =
        dir.listFiles()?.filter { it.isFile }?.map { UpdateDirEntry(it.name, it.length()) } ?: emptyList()

    fun readPending(): PendingUpdate? = try {
        if (sidecar.isFile) UpdateFilesPolicy.parse(sidecar.readText()) else null
    } catch (_: Exception) {
        null
    }

    /** Records a validated download (written to a temp file, then renamed, so it's never half-written). */
    fun writePending(pending: PendingUpdate) {
        dir.mkdirs()
        val tmp = File(dir, "${UpdateFilesPolicy.SIDECAR}.tmp")
        tmp.writeText(UpdateFilesPolicy.serialize(pending))
        if (!tmp.renameTo(sidecar)) {
            tmp.delete()
            throw java.io.IOException("Couldn't record the downloaded update")
        }
    }

    /** The validated pending update and its APK, if it is complete and newer than [installedVc]. */
    fun pendingVerified(installedVc: Long): Pair<PendingUpdate, File>? {
        val pending = readPending() ?: return null
        if (!UpdateFilesPolicy.isPendingValid(pending, entries(), installedVc)) return null
        return pending to File(dir, pending.fileName)
    }

    /** Removes the pending update (its APK and sidecar), e.g. after a validation or install failure. */
    fun clearPending() {
        readPending()?.let { File(dir, it.fileName).takeIf { f -> '/' !in it.fileName }?.delete() }
        sidecar.delete()
    }

    /** Deletes every stale update file (see [UpdateFilesPolicy.isStale]). Returns what was deleted. */
    fun deleteStale(installedVc: Long): List<File> {
        val deleted = mutableListOf<File>()
        for (legacyDir in legacyDirs) {
            legacyDir.listFiles()?.forEach { f ->
                if (f.isFile && UpdateFilesPolicy.isLegacy(f.name) && f.delete()) deleted += f
            }
        }
        val entries = entries()
        val pending = readPending()
        for (entry in entries) {
            if (UpdateFilesPolicy.isStale(entry, entries, pending, installedVc)) {
                val f = File(dir, entry.name)
                if (f.delete()) deleted += f
            }
        }
        return deleted
    }
}
