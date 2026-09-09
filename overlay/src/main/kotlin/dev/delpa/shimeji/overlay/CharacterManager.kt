package dev.delpa.shimeji.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Manages custom character sprite sets stored in internal storage.
 *
 * Characters live under `files/characters/<name>/` and must contain:
 *  - `poses.json` — animation→frame mapping (same format as bundled assets)
 *  - PNG frame files referenced by poses.json
 *
 * The bundled classic Shimeji is the implicit fallback when no custom
 * character is selected.
 */
class CharacterManager(private val context: Context) {

    private val charsDir: File
        get() = File(context.filesDir, "characters").also { it.mkdirs() }

    /** List all imported character names (sorted). */
    fun list(): List<String> =
        charsDir.listFiles()
            ?.filter { it.isDirectory && File(it, "poses.json").exists() }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    /** Get the directory for a named character. */
    fun dir(name: String): File = File(charsDir, name)

    /**
     * Import a character from a zip file URI. The zip must contain
     * a `poses.json` and at least one PNG frame. Returns the character name
     * on success, null on failure.
     *
     * Path handling: entries are written preserving their relative paths so
     * that `poses.json` `"file"` references survive. If every entry lives under
     * a single top-level directory (the common "character_name/..." layout), that
     * prefix is stripped so frames land directly in the character folder.
     */
    fun importFromZip(name: String, uri: Uri): String? {
        return runCatching {
            val destDir = File(charsDir, name)
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()

            var bytesWritten = 0L
            var hasPosesJson = false

            val staging = File(charsDir, ".staging_$name").apply { deleteRecursively(); mkdirs() }
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory &&
                                !entry.name.contains("__MACOSX") &&
                                !entry.name.startsWith(".")
                            ) {
                                // Preserve the full relative path so poses.json
                                // "file" references to subfolders keep working.
                                val destFile = File(staging, entry.name)
                                destFile.parentFile?.mkdirs()
                                destFile.outputStream().use { output ->
                                    bytesWritten += zip.copyTo(output)
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }

                // Locate poses.json anywhere in the staging tree.
                val posesFile = staging.walkTopDown()
                    .firstOrNull { it.isFile && it.name == "poses.json" }
                if (posesFile == null || bytesWritten == 0L) {
                    Log.w(TAG, "import rejected: no poses.json in zip for '$name'")
                    return null
                }
                hasPosesJson = true

                // Base directory = folder that contains poses.json. Move its
                // contents to the final character dir (handles the common
                // "character_name/poses.json + frames" layout).
                val base = posesFile.parentFile ?: staging
                base.listFiles()?.forEach { f ->
                    val target = File(destDir, f.name)
                    if (f.isDirectory) {
                        f.copyRecursively(target, overwrite = true)
                    } else {
                        f.copyTo(target, overwrite = true)
                    }
                }
            } finally {
                staging.deleteRecursively()
            }

            if (!hasPosesJson) return null

            Log.i(TAG, "imported character '$name' from zip: ${bytesWritten / 1024} KB")
            name
        }.getOrElse { err ->
            Log.e(TAG, "import failed for $name", err)
            File(charsDir, name).deleteRecursively()
            null
        }
    }

    /**
     * Derive a character name from a URI's display name (e.g. "my_char.zip" → "my_char").
     */
    fun deriveNameFromUri(uri: Uri): String {
        var name = "character_${System.currentTimeMillis() % 10000}"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameCol = cursor.getColumnIndex("_display_name")
                if (nameCol >= 0) {
                    val displayName = cursor.getString(nameCol) ?: ""
                    name = displayName
                        .removeSuffix(".zip")
                        .removeSuffix(".ZIP")
                        .replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                        .take(40)
                        .ifBlank { name }
                }
            }
        }
        return name
    }

    /** Delete an imported character. Cannot delete "classic" (bundled). */
    fun delete(name: String): Boolean {
        if (name == "classic") return false
        val dir = File(charsDir, name)
        return dir.exists() && dir.deleteRecursively()
    }

    /**
     * Load character bitmaps for use by [SpriteMascotRenderer].
     * Returns null if poses.json is missing or no frames loaded.
     */
    fun loadCharacter(name: String): Map<String, List<android.graphics.Bitmap>>? {
        return runCatching {
            val dir = dir(name)
            val posesFile = File(dir, "poses.json")
            if (!posesFile.exists()) return null

            val root = JSONObject(posesFile.readText())
            val animsJson = root.getJSONObject("animations")
            val anims = linkedMapOf<String, List<android.graphics.Bitmap>>()
            var total = 0

            for (animName in animsJson.keys()) {
                val list = animsJson.getJSONArray(animName)
                val frames = ArrayList<android.graphics.Bitmap>(list.length())
                for (i in 0 until list.length()) {
                    val file = list.getJSONObject(i).getString("file")
                    val frameFile = File(dir, file)
                    if (!frameFile.exists()) {
                        Log.w(TAG, "missing frame $file for animation $animName in character $name")
                        continue
                    }
                    val bmp = BitmapFactory.decodeFile(frameFile.absolutePath)
                    if (bmp != null) frames.add(bmp)
                }
                anims[animName] = frames
                total += frames.size
            }

            if (anims.isEmpty()) null else anims
        }.getOrElse { err ->
            Log.e(TAG, "failed to load character $name", err)
            null
        }
    }

    /**
     * Get a preview thumbnail bitmap for a character.
     * Returns the first frame of the idle animation, or the first available frame.
     */
    fun getPreviewBitmap(name: String, targetSize: Int = 64): Bitmap? {
        return runCatching {
            val posesRaw: String
            val readFrame: (String) -> Bitmap?
            if (name.isEmpty()) {
                // Bundled classic character — load from assets.
                posesRaw = context.assets.open("mascot/poses.json")
                    .bufferedReader().use { it.readText() }
                readFrame = { file ->
                    context.assets.open("mascot/$file").use {
                        BitmapFactory.decodeStream(it)
                    }
                }
            } else {
                val dir = dir(name)
                val posesFile = File(dir, "poses.json")
                if (!posesFile.exists()) return null
                posesRaw = posesFile.readText()
                readFrame = { file -> BitmapFactory.decodeFile(File(dir, file).absolutePath) }
            }

            val root = JSONObject(posesRaw)
            val animsJson = root.getJSONObject("animations")

            // Try idle first, then first available animation
            val animNames = listOf("idle", "sit", "walk").filter { animsJson.has(it) }
            val animName = animNames.firstOrNull() ?: animsJson.keys().asSequence().firstOrNull() ?: return null

            val list = animsJson.getJSONArray(animName)
            if (list.length() == 0) return null

            val file = list.getJSONObject(0).getString("file")
            val bmp = readFrame(file) ?: return null

            // Scale down to target size
            val scale = targetSize.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt(),
                    (bmp.height * scale).toInt(),
                    true
                )
                if (scaled != bmp) bmp.recycle()
                scaled
            } else bmp
        }.getOrNull()
    }

    companion object {
        private const val TAG = "CharacterManager"
    }
}
