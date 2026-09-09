package dev.delpa.shimeji.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import org.json.JSONObject
import java.io.File

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
     * Import a character from a SAF directory URI. The directory must contain
     * a `poses.json` and at least one PNG frame. Returns the character name
     * (directory name) on success, null on failure.
     */
    fun importFromUri(name: String, uri: Uri): String? {
        return runCatching {
            val destDir = File(charsDir, name)
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()

            var bytesWritten = 0L
            var hasPosesJson = false

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow("_display_name")
                val mimeCol = cursor.getColumnIndexOrThrow("mime_type")
                val docIdCol = cursor.getColumnIndexOrThrow("document_id")

                while (cursor.moveToNext()) {
                    val fileName = cursor.getString(nameCol)
                    val mime = cursor.getString(mimeCol)
                    val docId = cursor.getString(docIdCol)

                    // Skip directories (they have vnd.android.document/directory MIME).
                    if (mime == "vnd.android.document/directory") continue

                    val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                    val destFile = File(destDir, fileName)

                    context.contentResolver.openInputStream(childUri)?.use { input ->
                        destFile.outputStream().use { output ->
                            bytesWritten += input.copyTo(output)
                        }
                    }

                    if (fileName == "poses.json") hasPosesJson = true
                }
            }

            if (!hasPosesJson) {
                destDir.deleteRecursively()
                Log.w(TAG, "import rejected: no poses.json in $name")
                return null
            }

            Log.i(TAG, "imported character '$name': ${bytesWritten / 1024} KB")
            name
        }.getOrElse { err ->
            Log.e(TAG, "import failed for $name", err)
            null
        }
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

    companion object {
        private const val TAG = "CharacterManager"
    }
}
