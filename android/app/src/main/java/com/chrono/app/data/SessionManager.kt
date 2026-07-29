package com.chrono.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Folder-based logging in PUBLIC storage so the user can browse it with any
 * file manager:
 *
 *   Documents/ChronoData/
 *     2026-07-06/                <- PROJECT folder, one per day (renameable at
 *       Test1/                      creation via the new-day prompt)
 *         shot.json              <- TEST subfolder, named by the test label or
 *         setup_*.jpg, after_*      Test1/Test2/… auto-incrementing
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
    private var testCounter: Int = prefs.getInt("testCounter", 0)
    private var currentTestRel: String? = prefs.getString("currentTestRel", null)
    private var shotLogged: Boolean = prefs.getBoolean("shotLogged", false)

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Prompt for a project only on a genuinely new day (or first run). */
    fun needsProjectPrompt(): Boolean = projectName == null || projectDay != today()

    /** Default label for the next test: Test1, Test2, … within this project. */
    fun suggestedLabel(): String = "Test${testCounter + 1}"

    val pathLabel: String get() = "Documents/$rootDir/${projectName ?: ""}"

    fun startProject(name: String) {
        projectName = sanitize(name.ifBlank { today() })
        projectDay = today()
        testCounter = 0
        currentTestRel = null
        shotLogged = false
        prefs.edit().remove("used_$projectName").apply()
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
        testCounter += 1
        val base = sanitize(label).ifBlank { "Test$testCounter" }
        val name = uniqueName(base)
        addUsedName(name)
        currentTestRel = "$projectName/$name"
        shotLogged = false
        save()
    }

    /** Folder for the current test cycle; rolls a new one after a logged shot. */
    private fun currentTest(label: String): String {
        if (currentTestRel == null || shotLogged) rollTest(label)
        return currentTestRel!!
    }

    fun currentTestLogged(): Boolean = shotLogged

    /** Setup photos open/join the upcoming test; after photos stay with it. */
    fun newPhotoUri(kind: String, label: String): Uri? {
        val rel = writablePhotoRel(kind, label)
        return createUriAt(rel, "${kind}_${System.currentTimeMillis()}.jpg", "image/jpeg")
    }

    /** Create a photo directly inside a result's immutable owning folder. */
    fun newPhotoUriInFolder(rel: String, kind: String): Uri? =
        rel.takeIf { it.isNotBlank() }?.let {
            createUriAt(it, "${kind}_${System.currentTimeMillis()}.jpg", "image/jpeg")
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
        createUriAt(rel, "shot.json", "application/json")?.let { uri ->
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toString(2).toByteArray())
                }
            }
        }
        shotLogged = true
        save()
        return rel
    }

    /** Folder to attach photos to an existing record without touching counters. */
    fun folderForResult(existingRel: String?, uidHint: String): String {
        if (!existingRel.isNullOrBlank()) return existingRel
        ensureProject()
        return "$projectName/Extra_${uidHint.take(8)}"
    }

    fun importPhoto(rel: String, source: Uri): Boolean {
        val dest = createUriAt(rel, "attached_${System.currentTimeMillis()}.jpg", "image/jpeg")
            ?: return false
        return runCatching {
            context.contentResolver.openInputStream(source)!!.use { input ->
                context.contentResolver.openOutputStream(dest)!!.use { out -> input.copyTo(out) }
            }
        }.isSuccess
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
                        "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'image/%'",
                    arrayOf("Documents/$rootDir/$rel/"),
                    "${MediaStore.MediaColumns._ID} ASC",
                )?.use { c ->
                    val idc = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (c.moveToNext()) out.add(ContentUris.withAppendedId(coll, c.getLong(idc)))
                }
            }
            out
        } else {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "$rootDir/$rel")
            dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
                ?.sortedBy { it.name }
                ?.mapNotNull {
                    runCatching {
                        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it)
                    }.getOrNull()
                } ?: emptyList()
        }
    }

    // ------------------------------------------------------------------ helpers

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
        s.trim().replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_").take(40).trim()

    private fun usedNames(): MutableSet<String> =
        prefs.getStringSet("used_$projectName", emptySet())!!.toMutableSet()

    private fun addUsedName(n: String) {
        val s = usedNames(); s.add(n)
        prefs.edit().putStringSet("used_$projectName", s).apply()
    }

    private fun uniqueName(base: String): String {
        val used = usedNames()
        if (base !in used) return base
        var i = 2
        while ("${base}_$i" in used) i++
        return "${base}_$i"
    }

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
            .putInt("testCounter", testCounter)
            .putString("currentTestRel", currentTestRel)
            .putBoolean("shotLogged", shotLogged)
            .apply()
    }
}
