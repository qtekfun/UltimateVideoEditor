package com.qtekfun.ultimatevideoeditor.data.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "UVVoice"
private const val SAMPLE_RATE = 48_000
private const val CHANNELS = 1

/**
 * Records the microphone to a mono 48 kHz 16-bit WAV in [directory], on its own thread. The sound stays on the phone: it is written to
 * the project's own folder and nothing is sent anywhere. The microphone permission is the caller's business ([start] reports
 * [VoiceProblem.NO_PERMISSION] if it is missing). A WAV needs no encoder, so a take is sample-exact and any later step can read it.
 */
class AndroidVoiceRecorder(
    private val directory: () -> File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VoiceRecorder {
    private var record: AudioRecord? = null
    private var file: File? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile
    private var writer: TakeWriter? = null

    @Volatile
    private var failure: VoiceProblem? = null

    override val level: Float get() = writer?.peak ?: 0f

    override val elapsedMs: Long get() = writer?.elapsedMs ?: 0

    // While paused the microphone keeps running and its sound is dropped (see TakeWriter): resuming is instant and loses nothing.
    override fun pause() {
        writer?.paused = true
    }

    override fun resume() {
        writer?.paused = false
    }

    @SuppressLint("MissingPermission") // The permission is checked by the caller; a SecurityException is reported as NO_PERMISSION.
    override suspend fun start() = withContext(ioDispatcher) {
        if (running.get()) return@withContext
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw VoiceException(VoiceProblem.FAILED)
        val target = try {
            val dir = directory().also { it.mkdirs() }
            File(dir, "voice-${System.currentTimeMillis()}.wav")
        } catch (e: SecurityException) {
            throw VoiceException(VoiceProblem.FAILED, e)
        }
        val audio = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufferBytes(minBuffer),
            )
        } catch (e: SecurityException) {
            throw VoiceException(VoiceProblem.NO_PERMISSION, e)
        } catch (e: IllegalArgumentException) {
            throw VoiceException(VoiceProblem.FAILED, e)
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            throw VoiceException(VoiceProblem.MIC_BUSY)
        }
        val out = try {
            RandomAccessFile(target, "rw").also { it.write(WavHeader.build(SAMPLE_RATE, CHANNELS, 0)) }
        } catch (e: IOException) {
            audio.release()
            throw VoiceException(VoiceProblem.DISK, e)
        }
        val first = CompletableDeferred<VoiceProblem?>()
        record = audio
        file = target
        failure = null
        val take = TakeWriter(SAMPLE_RATE, CHANNELS) { data, length -> out.write(data, 0, length) }
        writer = take
        running.set(true)
        try {
            audio.startRecording()
        } catch (e: IllegalStateException) {
            cleanup(delete = true, out)
            throw VoiceException(VoiceProblem.MIC_BUSY, e)
        }
        thread = Thread({
            // The preview decodes video and the mixer plays while this runs: at normal priority the thread can be starved long enough for
            // the microphone's buffer to overrun, which leaves a gap in the take.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            pump(audio, out, take, minBuffer, first)
        }, "uv-voice").also { it.start() }
        // Back only once the first sound arrived: from here the take is really running.
        val problem = first.await()
        Log.i(TAG, "Recording from input type ${audio.routedDevice?.type} at ${audio.sampleRate} Hz, buffer ${recordBufferBytes(minBuffer)} bytes")
        if (problem != null) {
            cleanup(delete = true, out)
            throw VoiceException(problem)
        }
    }

    private fun pump(audio: AudioRecord, out: RandomAccessFile, take: TakeWriter, minBuffer: Int, first: CompletableDeferred<VoiceProblem?>) {
        val samples = ShortArray(minBuffer / 2)
        var announced = false
        try {
            while (running.get()) {
                val read = audio.read(samples, 0, samples.size)
                if (read < 0) {
                    failure = VoiceProblem.MIC_BUSY
                    if (!announced) first.complete(VoiceProblem.MIC_BUSY)
                    break
                }
                if (read == 0) continue
                try {
                    take.write(samples, read)
                } catch (e: IOException) {
                    Log.w(TAG, "Cannot write the take: ${e.message}")
                    failure = VoiceProblem.DISK
                    if (!announced) first.complete(VoiceProblem.DISK)
                    break
                }
                if (!announced) {
                    announced = true
                    first.complete(null)
                }
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Microphone stopped: ${e.message}")
            failure = VoiceProblem.MIC_BUSY
        } finally {
            if (!announced && !first.isCompleted) first.complete(failure ?: VoiceProblem.FAILED)
            take.silence()
            // The take is complete on disk when the loop ends; stop() reopens it to say how long it is.
            runCatching { out.close() }
        }
    }

    override suspend fun stop(): VoiceRecording? = withContext(ioDispatcher) {
        val target = file ?: return@withContext null
        val problem = failure
        running.set(false)
        thread?.join(JOIN_MS)
        val size = writer?.bytes ?: 0
        writer = null
        val audio = record
        record = null
        thread = null
        try {
            audio?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Stop: ${e.message}")
        }
        audio?.release()
        file = null
        if (size <= 0) {
            target.delete()
            if (problem != null) throw VoiceException(problem)
            return@withContext null
        }
        try {
            // The header was written for an empty take; now it can say how much there is.
            RandomAccessFile(target, "rw").use { it.seek(0); it.write(WavHeader.build(SAMPLE_RATE, CHANNELS, size)) }
        } catch (e: IOException) {
            target.delete()
            throw VoiceException(VoiceProblem.DISK, e)
        }
        if (problem != null) Log.w(TAG, "The take was cut short by $problem; what was recorded is kept")
        VoiceRecording(android.net.Uri.fromFile(target).toString(), WavHeader.durationMs(size, SAMPLE_RATE, CHANNELS))
    }

    override fun cancel() {
        running.set(false)
        thread?.join(JOIN_MS)
        cleanup(delete = true, null)
    }

    private fun cleanup(delete: Boolean, out: RandomAccessFile?) {
        running.set(false)
        runCatching { out?.close() }
        try {
            record?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Stop: ${e.message}")
        }
        record?.release()
        record = null
        thread = null
        if (delete) file?.delete()
        file = null
        writer = null
    }

    private companion object {
        const val JOIN_MS = 1500L

        /** At least one second of sound, so a short stall of the reading thread (or a slow external input) does not lose any of it. */
        fun recordBufferBytes(minBuffer: Int): Int = maxOf(minBuffer * 4, SAMPLE_RATE * 2 * CHANNELS)
    }
}
