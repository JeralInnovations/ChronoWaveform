package com.chrono.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Folder-based logging in PUBLIC storage so the user can browse it with any
 * file manager:
 *
 *   Documents/ChronoData/
 *     2026-07-06/                <- PROJECT folder, one per day (renameable at
 *       Test1--<uuid>/              creation via the new-day prompt)
 *         shot.json              <- immutable TEST folder; editable label in JSON
 *         setup_*.jpg, after_*      display labels can repeat
 *       LongRangeGroupA/
 *
 * A new day (or first run) prompts for the project folder; within a day every
 * test drops into the same project. A test subfolder opens when the first
 * photo or the shot log for that test arrives, and rolls to a new one when the
 * next shot begins.
 *
 * Files are written through MediaStore (no storage permission for app-created
 * files) on Android 10+. On 9 and below the same tree lives under
 * Android/data/com.chrono.app/files/ChronoData.
 */
class SessionManager(private val context: Context, simulation: Boolean = false) {

    private val prefs = context.getSharedPreferences(
        if (simulation) "chrono_session_sim" else "chrono_session", Context.MODE_PRIVATE
    )
    private val useMediaStore = Build.VERSION.SDK_INT >= 29

    // Simulated sessions live in a clearly-labelled sibling folder so their logs
    // and photos never mix with real range data.
    private val rootDir = if (simulation) "ChronoData_SIMULATION" else "ChronoData"

    var projectName: String? = prefs.getString("projectName", null)
        private set
    private var projectDay: String? = prefs.getString("projectDay", null)
    private var currentTestRel: String? = prefs.getString("currentTestRel", null)
    private var currentTestLabel: String? = prefs.getString("currentTestLabel", null)
    private var shotLogged: Boolean = prefs.getBoolean("shotLogged", false)

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Prompt for a project only on a genuinely new day (or first run). */
    fun needsProjectPrompt(): Boolean = projectName == null || projectDay != today()

    /** The active test folder remains authoritative through its after photos. */
    fun suggestedLabel(): String =
        currentTestLabel ?: currentTestRel?.substringAfterLast('/') ?: nextGeneratedLabel()

    val pathLabel: String get() = if (useMediaStore) "Documents/$rootDir/${projectName ?: ""}"
        else "Android/data/${context.packageName}/files/$rootDir/${projectName ?: ""}"

    fun startProject(name: String) {
        projectName = sanitize(name).ifBlank { today() }
        projectDay = today()
        currentTestRel = null
        currentTestLabel = null
        shotLogged = false
        save()
    }

    /** Keep the previous project on a new day; just stop prompting for today. */
    fun continueProject() {
        if (projectName == null) startProject(today())
        else { projectDay = today(); save() }
    }

    // ------------------------------------------------------------- test folders

    private fun ensureProject() { if (projectName == null) startProject(today()) }

    private fun rollTest(label: String) {
        ensureProject()
        val base = sanitize(label).ifBlank { nextGeneratedLabel() }
        // A provider may hide older files after reinstall. Visibility is never identity.
        val name = "$base--${UUID.randomUUID()}"
        currentTestLabel = base
        currentTestRel = "$projectName/$name"
        reserve(currentTestRel!!)
        shotLogged = false
        save()
    }

    /** Apply a UI label edit to the active test folder. */
    fun commitCurrentTestLabel(label: String): String {
        val currentRel = currentTestRel
            ?: return sanitize(label).ifBlank { nextGeneratedLabel() }
        return renameTestFolder(currentRel, label).second
    }

    /**
     * End the active folder only for an explicit New Test or saved manual log.
     * The next photo or result creates the next numbered folder.
     */
    fun beginNewTest() {
        currentTestRel = null
        currentTestLabel = null
        shotLogged = false
        save()
    }

    /** Open the test if needed and return the exact folder/label name selected. */
    fun prepareTestLabel(label: String): String {
        currentTest(label)
        return suggestedLabel()
    }

    /** Edit the display label without moving files or changing photo ownership. */
    fun renameTestFolder(rel: String, label: String): Pair<String, String> {
        val effective = sanitize(label).ifBlank {
            if (rel == currentTestRel) suggestedLabel() else rel.substringAfterLast('/').substringBefore("--")
        }
        if (rel == currentTestRel) {
            currentTestLabel = effective
            save()
        }
        return rel to effective
    }

    /** Folder for the active test cycle. Only beginNewTest() advances it. */
    private fun currentTest(label: String): String {
        if (currentTestRel == null) {
            rollTest(label)
        } else {
            commitCurrentTestLabel(label)
        }
        return currentTestRel!!
    }

    fun currentTestLogged(): Boolean = shotLogged || currentTestRel?.let { rel ->
        if (useMediaStore) findUriAt(rel, "shot.json") != null
        else File(context.getExternalFilesDir(null) ?: context.filesDir, "$rootDir/$rel/shot.json").exists()
    } == true

    val activeFolder: String? get() = currentTestRel

    fun preparedFolder(): String = checkNotNull(currentTestRel)

    fun markCurrentTestLogged() { shotLogged = true; save() }

    /** Setup photos open/join the upcoming test; after photos stay with it. */
    fun newPhotoUri(kind: String, label: String): Uri? {
        val rel = writablePhotoRel(kind, label)
        return createUriAt(rel, "${kind}_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg", "image/jpeg")
    }

    /** Create a photo directly inside a result's immutable owning folder. */
    fun newPhotoUriInFolder(rel: String, kind: String): Uri? =
        rel.takeIf { it.isNotBlank() }?.let {
            createUriAt(it, "${kind}_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg", "image/jpeg")
        }

    fun listPromptPhotos(kind: String, label: String): List<Uri> =
        readablePhotoRel(kind, label)?.let { listPhotos(it) } ?: emptyList()

    fun importPromptPhoto(kind: String, label: String, source: Uri): Boolean =
        importPhoto(writablePhotoRel(kind, label), source)

    private fun writablePhotoRel(kind: String, label: String): String =
        if (kind == "after") (currentTestRel ?: currentTest(label)) else currentTest(label)

    private fun readablePhotoRel(kind: String, label: String): String? =
        if (kind == "after") currentTestRel ?: currentTest(label)
        else currentTestRel?.takeUnless { shotLogged }

    /** Writes the log into its test folder; returns the folder id for the record. */
    fun logShot(label: String, json: JSONObject): String {
        val rel = currentTest(label)
        json.put("label", suggestedLabel())
        json.put("shotFolder", rel)
        check(writeShotJson(rel, json)) { "Could not save the test file" }
        shotLogged = true
        save()
        return rel
    }

    /** Rewrites an existing test's canonical log after the user edits it. */
    fun updateShot(rel: String, json: JSONObject): Boolean {
        if (rel.isBlank()) return false
        return writeShotJson(rel, json)
    }

    /** Store the readable edge trace beside shot.json in the same test folder. */
    fun updateWaveform(rel: String, json: JSONObject): Boolean {
        if (rel.isBlank()) return false
        return writeJsonFile(rel, "waveform.json", json)
    }

    /** Replace the human-readable waveform snapshot beside the JSON files. */
    fun updateWaveformImage(rel: String, png: ByteArray): Boolean {
        if (rel.isBlank()) return false
        return writeBinaryFile(rel, "waveform.png", "image/png", png)
    }

    /**
     * Remove only a provisional reading. Setup photos and the active test
     * folder are deliberately retained so the operator can re-arm immediately.
     */
    fun discardShotArtifacts(rel: String): Boolean {
        if (rel.isBlank()) return false
        val artifactNames = setOf("shot.json", "waveform.json", "waveform.png")
        val artifacts = projectFiles(allProjects = true).filter {
            it.relativeFolder == rel && it.displayName in artifactNames
        }
        var success = true
        artifacts.forEach { stored ->
            val deleted = if (stored.uri != null) {
                runCatching { context.contentResolver.delete(stored.uri, null, null) > 0 }
                    .getOrDefault(false)
            } else {
                runCatching { stored.file?.delete() == true }.getOrDefault(false)
            }
            success = success && deleted
        }
        if (currentTestRel == rel) {
            shotLogged = false
            save()
        }
        return success
    }

    /** Folder to attach photos to an existing record without touching counters. */
    fun folderForResult(existingRel: String?, uidHint: String): String {
        if (!existingRel.isNullOrBlank()) return existingRel
        ensureProject()
        return "$projectName/Extra_$uidHint"
    }

    fun importPhoto(rel: String, source: Uri): Boolean {
        val dest = createUriAt(rel, "attached_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg", "image/jpeg")
            ?: return false
        val copied = runCatching {
            context.contentResolver.openInputStream(source)!!.use { input ->
                context.contentResolver.openOutputStream(dest)!!.use { out -> input.copyTo(out) }
            }
        }.isSuccess
        if (!copied) deletePhoto(dest)
        return copied
    }

    fun deletePhoto(uri: Uri): Boolean =
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    /** Image files already saved in a shot's folder, for thumbnails. */
    fun listPhotos(rel: String): List<Uri> {
        if (rel.isBlank()) return emptyList()
        return if (useMediaStore) {
            val coll = MediaStore.Files.getContentUri("external")
            val out = mutableListOf<Uri>()
            runCatching {
                context.contentResolver.query(
                    coll,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                        "(${MediaStore.MediaColumns.MIME_TYPE} LIKE 'image/%' OR " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.jpg' OR " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.jpeg' OR " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.png' OR " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.webp' OR " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.heic') AND " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME}!=?",
                    arrayOf("Documents/$rootDir/$rel/", "waveform.png"),
                    "${MediaStore.MediaColumns._ID} ASC",
                )?.use { c ->
                    val idc = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (c.moveToNext()) out.add(ContentUris.withAppendedId(coll, c.getLong(idc)))
                }
            }
            out
        } else {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "$rootDir/$rel")
            dir.listFiles { f ->
                f.name != "waveform.png" && f.extension.lowercase() in setOf("jpg", "jpeg", "png")
            }
                ?.sortedBy { it.name }
                ?.mapNotNull {
                    runCatching {
                        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it)
                    }.getOrNull()
                } ?: emptyList()
        }
    }

    /** Discover readable public copies. The scan does not change session ownership. */
    fun loadProjectResults(): List<TestResult> {
        // The app log is a history, not just the currently active project.
        // Scan every public project so reinstalling the app or starting a new
        // project never makes earlier recorded tests disappear.
        val allFiles = projectFiles(allProjects = true)
        val jsonFiles = allFiles
            .filter { it.displayName.equals("shot.json", ignoreCase = true) }
            .groupBy { it.relativeFolder.lowercase(Locale.US) }
            .values
            .mapNotNull { files -> files.maxByOrNull { it.modifiedAt } }

        val parsed = jsonFiles.mapNotNull { stored ->
            runCatching {
                val json = JSONObject(readStoredText(stored))
                testResultFromJson(
                    o = json,
                    folder = stored.relativeFolder,
                    folderLabel = json.optString("label").ifBlank { stored.folderName.substringBefore("--") },
                ) to stored.modifiedAt
            }.getOrNull()
        }
        return parsed.sortedWith(
            compareByDescending<Pair<TestResult, Long>> {
                it.first.epochMillis ?: it.second * 1000L
            }.thenByDescending { testNumber(it.first.label) ?: 0 }
        ).map { it.first }
    }

    /** Delete the public folder represented by a result removed in the app. */
    fun deleteTestFolder(rel: String): Boolean {
        if (rel.isBlank()) return false
        val matching = projectFiles(allProjects = true).filter { it.relativeFolder == rel }
        var success = true
        for (stored in matching) {
            val deleted = if (stored.uri != null) {
                runCatching { context.contentResolver.delete(stored.uri, null, null) > 0 }
                    .getOrDefault(false)
            } else {
                runCatching { stored.file?.delete() == true }.getOrDefault(false)
            }
            success = success && deleted
        }
        if (!useMediaStore) {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            runCatching { File(root, "$rootDir/$rel").delete() }
        }
        if (currentTestRel == rel) {
            currentTestRel = null
            shotLogged = false
            save()
        }
        return matching.isNotEmpty() && success
    }

    private data class StoredProjectFile(
        val folderName: String,
        val relativeFolder: String,
        val displayName: String,
        val modifiedAt: Long,
        val uri: Uri? = null,
        val file: File? = null,
    )

    private fun projectFiles(allProjects: Boolean = false): List<StoredProjectFile> {
        val project = projectName
        if (!allProjects && project == null) return emptyList()
        if (!useMediaStore) {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val dataDir = File(root, rootDir)
            val projectDirs = if (allProjects) {
                dataDir.listFiles { file -> file.isDirectory }?.toList().orEmpty()
            } else {
                listOf(File(dataDir, project!!))
            }
            return projectDirs.flatMap { projectDir ->
                projectDir.listFiles { file -> file.isDirectory }
                    ?.flatMap { folder ->
                        folder.listFiles()?.filter { it.isFile }?.map { file ->
                        StoredProjectFile(
                            folderName = folder.name,
                            relativeFolder = "${projectDir.name}/${folder.name}",
                            displayName = file.name,
                            modifiedAt = file.lastModified() / 1000L,
                            file = file,
                        )
                        } ?: emptyList()
                    } ?: emptyList()
            }
        }

        val collection = MediaStore.Files.getContentUri("external")
        val rootPrefix = "Documents/$rootDir/"
        val queryPrefix = if (allProjects) rootPrefix else "$rootPrefix$project/"
        val out = mutableListOf<StoredProjectFile>()
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$queryPrefix%"),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val pathColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val nameColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val modifiedColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathColumn) ?: continue
                    if (!path.startsWith(rootPrefix)) continue
                    val parts = path.removePrefix(rootPrefix).trim('/').split('/')
                    if (parts.size < 2) continue
                    val storedProject = parts[0].takeIf { it.isNotBlank() } ?: continue
                    if (!allProjects && storedProject != project) continue
                    val folderName = parts[1].takeIf { it.isNotBlank() } ?: continue
                    val id = cursor.getLong(idColumn)
                    out.add(
                        StoredProjectFile(
                            folderName = folderName,
                            relativeFolder = "$storedProject/$folderName",
                            displayName = cursor.getString(nameColumn).orEmpty(),
                            modifiedAt = cursor.getLong(modifiedColumn),
                            uri = ContentUris.withAppendedId(collection, id),
                        )
                    )
                }
            }
        }
        return out
    }

    private fun readStoredText(stored: StoredProjectFile): String =
        if (stored.uri != null) {
            context.contentResolver.openInputStream(stored.uri)!!.bufferedReader().use { it.readText() }
        } else {
            stored.file!!.readText()
        }

    private fun reserve(rel: String) {
        val names = prefs.getStringSet("reservedFolders", emptySet()).orEmpty().toMutableSet()
        names.add(rel)
        prefs.edit().putStringSet("reservedFolders", names).apply()
    }

    private fun existingTestNames(): Set<String> =
        projectFiles().mapTo(linkedSetOf()) { it.folderName } +
            prefs.getStringSet("reservedFolders", emptySet()).orEmpty()
                .filter { it.substringBeforeLast('/') == projectName }
                .map { it.substringAfterLast('/') }

    private fun nextGeneratedLabel(): String {
        val names = existingTestNames()
        val highestNumber = names.mapNotNull(::testNumber).maxOrNull() ?: 0
        var next = maxOf(names.size + 1, highestNumber + 1)
        while (names.any { it.equals("Test$next", ignoreCase = true) }) next++
        return "Test$next"
    }

    private fun testNumber(label: String): Int? =
        Regex("^Test([0-9]+)$", RegexOption.IGNORE_CASE)
            .matchEntire(label.substringBefore("--"))?.groupValues?.get(1)?.toIntOrNull()

    // ------------------------------------------------------------------ helpers

    private fun writeShotJson(rel: String, json: JSONObject): Boolean {
        val previous = runCatching {
            if (useMediaStore) {
                findUriAt(rel, "shot.json")?.let { uri ->
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Cannot read existing recording")
                }
            } else {
                File(context.getExternalFilesDir(null) ?: context.filesDir, "$rootDir/$rel/shot.json")
                    .takeIf { it.exists() }?.readText()
            }
        }.getOrElse { return false }
        if (previous != null) {
            val prior = runCatching { JSONObject(previous) }.getOrNull() ?: return false
            if (testResultFromJson(prior, folder = rel).uid != json.optString("uid")) return false
        }
        return writeJsonFile(rel, "shot.json", json)
    }

    private fun writeJsonFile(rel: String, name: String, json: JSONObject): Boolean {
        return writeBinaryFile(rel, name, "application/json", json.toString(2).toByteArray())
    }

    private fun writeBinaryFile(
        rel: String,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): Boolean {
        if (!useMediaStore) {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "$rootDir/$rel")
            return runCatching {
                dir.mkdirs()
                val atomic = AtomicFile(File(dir, name))
                val out = atomic.startWrite()
                try {
                    out.write(bytes)
                    out.fd.sync()
                    atomic.finishWrite(out)
                } catch (error: Exception) {
                    atomic.failWrite(out)
                    throw error
                }
            }.isSuccess
        }

        val uri = findUriAt(rel, name)
            ?: createUriAt(rel, name, mime)
            ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        }.isSuccess
    }

    private fun findUriAt(rel: String, displayName: String): Uri? {
        val coll = MediaStore.Files.getContentUri("external")
        return runCatching {
            context.contentResolver.query(
                coll,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("Documents/$rootDir/$rel/", displayName),
                "${MediaStore.MediaColumns._ID} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else ContentUris.withAppendedId(
                    coll,
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
                )
            }
        }.getOrNull()
    }

    private fun createUriAt(rel: String, displayName: String, mime: String): Uri? =
        if (useMediaStore) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/$rootDir/$rel")
            }
            runCatching {
                context.contentResolver.insert(MediaStore.Files.getContentUri("external"), cv)
            }.getOrNull()
        } else {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "$rootDir/$rel")
            dir.mkdirs()
            runCatching {
                FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", File(dir, displayName)
                )
            }.getOrNull()
        }

    private fun sanitize(s: String): String =
        s.trim().replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_").take(40).trim().trim('.')

    /** Best-effort: open the data folder in the system Files app. */
    fun openFolder(context: Context) {
        val sub = projectName?.let { "/$it" } ?: ""
        val docId = if (useMediaStore) "primary:Documents/$rootDir$sub"
        else "primary:Android/data/${context.packageName}/files/$rootDir$sub"
        val uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", docId
        )
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        for (intent in attempts) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    private fun save() {
        prefs.edit()
            .putString("projectName", projectName)
            .putString("projectDay", projectDay)
            .putString("currentTestRel", currentTestRel)
            .putString("currentTestLabel", currentTestLabel)
            .putBoolean("shotLogged", shotLogged)
            .apply()
    }
}
