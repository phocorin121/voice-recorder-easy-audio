package com.phocorin.easyaudio

import android.media.*
import java.io.*
import java.nio.ByteBuffer

object AudioCodec {
    fun encode(wav: File, output: File, bitrate: Int) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Pcm.RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1; var started = false; var eos = false; var done = false; var total = 0L
            val info = MediaCodec.BufferInfo()
            FileInputStream(wav).use { input ->
                check(input.skip(44) == 44L)
                val bytes = ByteArray(8192)
                var lastProgress = android.os.SystemClock.elapsedRealtime()
                while (!done) {
                    var progressed = false
                    if (!eos) {
                        val index = codec.dequeueInputBuffer(0)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!; buffer.clear()
                            val n = input.read(bytes, 0, minOf(bytes.size, buffer.remaining()))
                            val time = total / 2 * 1000000 / Pcm.RATE
                            if (n < 0) { codec.queueInputBuffer(index, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                            else { buffer.put(bytes, 0, n); codec.queueInputBuffer(index, 0, n, time, 0); total += n }
                            progressed = true
                        }
                    }
                    // Drain every available frame before requesting more input. One PCM
                    // input buffer produces several AAC packets; blocking once per packet
                    // otherwise adds minutes of avoidable polling to a long recording.
                    while (!done) {
                        val index = codec.dequeueOutputBuffer(info, 0)
                        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        progressed = true
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                        } else if (index >= 0) {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(started)
                                val buffer = codec.getOutputBuffer(index)!!
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                    if (progressed) lastProgress = android.os.SystemClock.elapsedRealtime()
                    else {
                        check(android.os.SystemClock.elapsedRealtime() - lastProgress < 30000) { "AAC encoder stalled" }
                        Thread.sleep(1)
                    }
                }
            }
            muxer.stop()
        } finally { codec.release(); muxer?.release() }
        RandomAccessFile(output, "rw").use { it.fd.sync() }
    }
    fun decode(source: File, wav: File) {
        val extractor = MediaExtractor(); var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == Pcm.RATE && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0); codec.start()
            var eos = false; var done = false; val info = MediaCodec.BufferInfo()
            FileOutputStream(wav).use { out ->
                out.write(Pcm.header(0))
                while (!done) {
                    if (!eos) { val i = codec.dequeueInputBuffer(10000)
                        if (i >= 0) { val b = codec.getInputBuffer(i)!!; val n = extractor.readSampleData(b, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                            else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val i = codec.dequeueOutputBuffer(info, 10000)
                    if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        check(f.getInteger(MediaFormat.KEY_SAMPLE_RATE) == Pcm.RATE && f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
                        check(!f.containsKey(MediaFormat.KEY_PCM_ENCODING) || f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                    } else if (i >= 0) {
                        if (info.size > 0) { val b = codec.getOutputBuffer(i)!!; b.position(info.offset); b.limit(info.offset + info.size); val bytes = ByteArray(info.size); b.get(bytes); out.write(bytes) }
                        done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0; codec.releaseOutputBuffer(i, false)
                    }
                }
                out.fd.sync()
            }
            Pcm.repair(wav)
        } finally { codec?.release(); extractor.release() }
    }
    fun trim(r: Recording, start: Long, end: Long): Recording {
        val id = Repository.id(); val source = Repository.file(r)
        val decoded = File(Repository.root, "$id.decode.tmp")
        val cut = File(Repository.root, "$id.cut.tmp")
        val output = File(Repository.root, "$id.${source.extension}")
        try {
            val input = if (source.extension == "wav") source else decoded.also { decode(source, it) }
            Pcm.trim(input, cut, start, end.coerceAtMost(Pcm.duration(input)))
            val peaks = Pcm.peaks(cut)
            val duration = Pcm.duration(cut)
            if (source.extension == "wav") check(cut.renameTo(output)) else {
                val temporary = File(Repository.root, "$id.m4a.tmp")
                try { encode(cut, temporary, 192000); check(temporary.renameTo(output)) } finally { temporary.delete() }
            }
            val next = r.copy(id = id, name = r.name + " (" + Pcm.formatTime(start) + "–" + Pcm.formatTime(end) + ")", file = output.name,
                created = System.currentTimeMillis(), duration = duration,
                marks = r.marks.filter { it in start..end }.map { it - start }, peaks = peaks)
            Repository.add(next); return next
        } finally { decoded.delete(); cut.delete() }
    }
}



