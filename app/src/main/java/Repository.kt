package com.phocorin.easyaudio

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.*
import java.io.File
import java.util.UUID

enum class Quality(val extension: String, val bitRate: Int) { STANDARD("m4a", 96000), HIGH("m4a", 192000), LOSSLESS("wav", 0) }
data class Recording(val id: String, val name: String, val file: String, val created: Long, val duration: Long,
    val favorite: Boolean = false, val folder: String = "", val note: String = "", val marks: List<Long> = emptyList(), val peaks: List<Float> = emptyList())
data class Library(val recordings: List<Recording> = emptyList(), val folders: List<String> = emptyList())
class EasyAudioApp : Application() {
    override fun onCreate() { super.onCreate(); Repository.init(this) }
}
object Repository {
    lateinit var root: File
    private lateinit var index: AtomicFile
    val library = MutableStateFlow(Library())
    val error = MutableStateFlow<Int?>(null)
    val ready = MutableStateFlow(false)
    val editing = MutableStateFlow(false)
    val editCompleted = MutableStateFlow<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Synchronized fun init(context: Context) {
        if (::root.isInitialized) return
        root = File(context.filesDir, "recordings")
        index = AtomicFile(File(context.filesDir, "library-v1.json"))
        scope.launch {
            try {
                check(root.exists() || root.mkdirs())
                if (index.baseFile.exists() || File(index.baseFile.path + ".bak").exists()) {
                    val json = JSONObject(index.openRead().bufferedReader().use { it.readText() })
                    check(json.optInt("version", 1) == 1)
                    library.value = Library(json.getJSONArray("recordings").objects().map { r ->
                        Recording(r.getString("id"), r.getString("name"), r.getString("file"), r.getLong("created"), r.getLong("duration"), r.optBoolean("favorite"), r.optString("folder"), r.optString("note"), r.optJSONArray("marks").longs(), r.optJSONArray("peaks").floats())
                    }, json.optJSONArray("folders").strings())
                }
                recover(context)
                ready.value = true
            } catch (_: Exception) { error.value = R.string.library_error }
        }
    }
    @Synchronized fun update(transform: (Library) -> Library) {
        check(ready.value)
        val next = transform(library.value)
        persist(next); library.value = next
    }
    private fun persist(data: Library) {
        val json = JSONObject().put("version", 1).put("folders", JSONArray(data.folders)).put("recordings", JSONArray(data.recordings.map { r ->
            JSONObject().put("id", r.id).put("name", r.name).put("file", r.file).put("created", r.created).put("duration", r.duration).put("favorite", r.favorite).put("folder", r.folder).put("note", r.note).put("marks", JSONArray(r.marks)).put("peaks", JSONArray(r.peaks))
        }))
        val out = index.startWrite()
        try { out.write(json.toString().toByteArray()); index.finishWrite(out) } catch (e: Exception) { index.failWrite(out); throw e }
    }
    fun file(r: Recording): File { require(File(r.file).name == r.file); return File(root, r.file) }
    fun add(r: Recording) = update { it.copy(recordings = it.recordings.filterNot { x -> x.id == r.id } + r) }
    fun change(r: Recording) = update { it.copy(recordings = it.recordings.map { x -> if (x.id == r.id) r else x }) }
    fun delete(ids: Set<String>) {
        // Keep metadata for any file that could not be deleted; report a failure.
        var failed = false
        val removed = library.value.recordings.filter { it.id in ids }.filter { r ->
            val f = file(r); val ok = !f.exists() || f.delete(); if (!ok) failed = true; ok
        }.map { it.id }.toSet()
        update { it.copy(recordings = it.recordings.filterNot { r -> r.id in removed }) }
        if (failed) throw java.io.IOException("delete")
    }
    private fun recover(context: Context) {
        var data = library.value
        root.listFiles()?.filter { it.name.endsWith(".wav.part") }?.forEach { part ->
            if (part.length() <= 44) { part.delete(); return@forEach }
            Pcm.repair(part)
            val id = part.name.removeSuffix(".wav.part")
            val target = File(root, "$id.wav")
            check(part.renameTo(target))
        }
        root.listFiles()?.filter { it.extension in listOf("wav", "m4a") && data.recordings.none { r -> r.file == it.name || r.id == it.nameWithoutExtension } }?.forEach { f ->
            if (f.extension == "wav" && f.length() > 44) {
                data = data.copy(recordings = data.recordings + Recording(f.nameWithoutExtension, context.getString(R.string.recovered_recording, f.nameWithoutExtension.take(8)), f.name, f.lastModified(), Pcm.duration(f), peaks = Pcm.peaks(f)))
            } else if (f.extension == "m4a") {
                val meta = android.media.MediaMetadataRetriever()
                try { meta.setDataSource(f.path); val duration = meta.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
                    if (duration > 0) data = data.copy(recordings = data.recordings + Recording(f.nameWithoutExtension, context.getString(R.string.recovered_recording, f.nameWithoutExtension.take(8)), f.name, f.lastModified(), duration))
                } finally { meta.release() }
            }
        }
        persist(data); library.value = data
    }
    fun trim(r: Recording, startMs: Long, endMs: Long, removeOriginal: Boolean) {
        if (!editing.compareAndSet(false, true)) return
        editCompleted.value = null
        scope.launch {
            try {
                AudioCodec.trim(r, startMs, endMs)
                if (removeOriginal) delete(setOf(r.id))
                editCompleted.value = r.id
            } catch (_: Exception) { error.value = R.string.trim_error }
            finally { editing.value = false }
        }
    }
    fun id(): String = UUID.randomUUID().toString()
}
private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
private fun JSONArray?.longs() = if (this == null) emptyList() else (0 until length()).map { getLong(it) }
private fun JSONArray?.floats() = if (this == null) emptyList() else (0 until length()).map { getDouble(it).toFloat() }
private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }




