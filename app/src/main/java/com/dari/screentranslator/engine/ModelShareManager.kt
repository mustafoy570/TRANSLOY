package com.dari.screentranslator.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ZAPYA OFFLINE SHARING (v1.5.0) — "share the app, share the models".
 *
 * In Afghanistan apps travel phone-to-phone through Zapya / SHAREit, not
 * through app stores. A friend who receives only the APK still needs
 * internet to download the ~30 MB language packs — often impossible.
 *
 * This manager therefore builds a SHARE PACK:
 *
 *   1. the app's own APK (copied out of /data/app, signed with the fixed
 *      key, so it installs on top of / alongside any older version), and
 *   2. a ZIP of every ML Kit translation model file found in the app's
 *      private storage, with the folder structure preserved.
 *
 * Both files are handed to Android's share sheet (Zapya is offered directly
 * when installed) via a FileProvider — no storage permission needed, the
 * files live in the app's own external share folder.
 *
 * On the receiving phone the user installs the APK, opens Settings →
 * "Import models", picks the received ZIP, and the models are copied back
 * into the same private paths — fully offline, zero internet, exactly the
 * same behaviour the sender had.
 *
 * PRIVACY: only model files are included (path/name filtered below); no
 * settings, no history, no user data ever leaves the phone.
 */
object ModelShareManager {

    /** What was built, consumed by the UI to fire the share intent. */
    data class SharePack(
        val apkFile: File?,
        val modelsZip: File?,
        val modelFileCount: Int,
        val modelBytes: Long
    ) {
        val hasModels: Boolean get() = modelsZip != null && modelFileCount > 0
    }

    /** Known Zapya package names (main app + the "self-share" variant). */
    private val ZAPYA_PACKAGES = listOf(
        "com.dewmobile.kuaizhan",
        "com.dewmobile.kuaizhan.trans"
    )

    /** Private-storage folders that are NOT model data. */
    private val EXCLUDED_DIRS = setOf(
        "cache", "code_cache", "datastore", "databases", "app_textures", "lib"
    )

    // ------------------------------------------------------------------ export

    /**
     * Builds (or rebuilds) the share pack in
     * `Android/data/com.dari.screentranslator/files/share/`.
     * Always safe to call again — the folder is wiped first.
     */
    suspend fun buildSharePack(context: Context): SharePack =
        withContext(Dispatchers.IO) {
            val shareDir = shareDir(context)
            cleanup(shareDir)

            // 1. Copy the installed APK (reading our own APK is allowed).
            val apkFile = runCatching {
                val source = File(context.applicationInfo.sourceDir)
                val target = File(shareDir, APK_FILE_NAME)
                source.copyTo(target, overwrite = true)
                target
            }.getOrNull()

            // 2. Zip every model file, remembering which private root each
            //    one came from so the import can put it back exactly there.
            val modelFiles = collectModelFiles(context)
            val modelsZip = if (modelFiles.isNotEmpty()) {
                runCatching {
                    val target = File(shareDir, MODELS_ZIP_NAME)
                    zipModels(modelFiles, target)
                    target
                }.getOrNull()
            } else {
                null
            }

            val bytes = modelsZip?.length() ?: 0L
            SharePack(apkFile, modelsZip, modelFiles.size, bytes)
        }

    /** Candidate private roots where ML Kit keeps translation models. */
    private fun modelRoots(context: Context): List<Pair<String, File>> {
        val roots = mutableListOf<Pair<String, File>>()
        context.filesDir.parentFile?.let { dataDir ->
            roots.add(ROOT_FILES to File(dataDir, "files"))
            roots.add(ROOT_NO_BACKUP to File(dataDir, "no_backup"))
        }
        // Defensive: also scan filesDir itself when parent is unavailable.
        roots.add(ROOT_FILES to context.filesDir)
        return roots.distinctBy { it.second.absolutePath }
    }

    /**
     * Finds ML Kit translation model files. ML Kit does not document its
     * storage layout, so this matches what it actually writes on device:
     * anything under a "translate"-named folder, anything under an
     * "mlkit"-named path, and model-like binaries (.tflite/.model/.bin),
     * while skipping caches and the DataStore.
     */
    private fun collectModelFiles(context: Context): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()
        for ((rootTag, rootDir) in modelRoots(context)) {
            if (!rootDir.exists()) continue
            rootDir.walkTopDown().forEach { file ->
                if (!file.isFile) return@forEach
                val relative = file.relativeTo(rootDir).path
                if (relative.isBlank()) return@forEach
                val segments = relative.split(File.separatorChar)
                if (segments.any { it.lowercase() in EXCLUDED_DIRS }) return@forEach
                val lower = relative.lowercase()
                val isModel = lower.contains("translate") ||
                    lower.contains("mlkit") ||
                    lower.endsWith(".tflite") ||
                    lower.endsWith(".model") ||
                    lower.endsWith(".lite")
                if (isModel && file.length() > 0) {
                    result.add("$rootTag/$relative" to file)
                }
            }
        }
        return result
    }

    private fun zipModels(entries: List<Pair<String, File>>, target: File) {
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            for ((entryName, file) in entries) {
                zip.putNextEntry(ZipEntry(entryName.replace(File.separatorChar, '/')))
                FileInputStream(file).use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }
        }
    }

    private fun cleanup(dir: File) {
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.deleteRecursively() }
        } else {
            dir.mkdirs()
        }
    }

    // ------------------------------------------------------------------ import

    /**
     * Restores a received models ZIP ([uri], picked with the system file
     * picker) into this phone's private storage. Returns the number of files
     * restored, or -1 when the ZIP was unreadable.
     */
    suspend fun importModelsZip(context: Context, uri: Uri): Int =
        withContext(Dispatchers.IO) {
            val dataDir = context.filesDir.parentFile
            var restored = 0
            try {
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(BufferedInputStream(raw)).use { zip ->
                        var entry: ZipEntry? = zip.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            // Path traversal guard: only clean relative paths.
                            if (!name.contains("..") && !name.startsWith("/")) {
                                val target = resolveImportTarget(dataDir, name)
                                if (target != null) {
                                    target.parentFile?.mkdirs()
                                    runCatching {
                                        FileOutputStream(target).use { output ->
                                            zip.copyTo(output)
                                        }
                                        restored++
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
            } catch (t: Throwable) {
                return@withContext if (restored > 0) restored else -1
            }
            restored
        }

    /** "files/…" → dataDir/files/… ; "no_backup/…" → dataDir/no_backup/… */
    private fun resolveImportTarget(dataDir: File?, entryName: String): File? {
        val dataDirectory = dataDir ?: return null
        return when {
            entryName.startsWith("$ROOT_NO_BACKUP/") ->
                File(dataDirectory, entryName.removePrefix("$ROOT_NO_BACKUP/"))
            // Default root (and legacy exports without a prefix): files/.
            else -> File(
                File(dataDirectory, "files"),
                entryName.removePrefix("$ROOT_FILES/")
            )
        }
    }

    // ------------------------------------------------------------------- share

    /**
     * The share intent for a [pack]: tries Zapya directly when installed,
     * otherwise falls back to the standard share sheet (Zapya, SHAREit,
     * Bluetooth, Files… all appear). Granting read permission lets the
     * receiver open the files without any storage permission.
     */
    fun shareIntent(context: Context, pack: SharePack): Intent {
        val uris = ArrayList<Uri>()
        pack.apkFile?.let { uris.add(uriFor(context, it)) }
        pack.modelsZip?.let { uris.add(uriFor(context, it)) }

        val send = if (uris.size > 1) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.firstOrNull())
            }
        }.apply {
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_TITLE, "Dari Screen Translator — app + offline models")
            putExtra(Intent.EXTRA_SUBJECT, "Dari Screen Translator — app + offline models")
        }

        val zapya = ZAPYA_PACKAGES.firstOrNull { pkg ->
            context.packageManager.getLaunchIntentForPackage(pkg) != null
        }
        return if (zapya != null) {
            send.setPackage(zapya)
        } else {
            Intent.createChooser(send, "Dari Screen Translator")
        }
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

    private fun shareDir(context: Context): File {
        val external = context.getExternalFilesDir(null) ?: context.filesDir
        return File(external, "share")
    }

    private companion object {
        const val APK_FILE_NAME = "Dari-Screen-Translator-v1.5.0.apk"
        const val MODELS_ZIP_NAME = "Dari-Translator-offline-models.zip"
        const val ROOT_FILES = "files"
        const val ROOT_NO_BACKUP = "no_backup"
    }
}
