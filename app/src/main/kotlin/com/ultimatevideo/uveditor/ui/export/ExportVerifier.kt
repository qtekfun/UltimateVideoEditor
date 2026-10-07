package com.ultimatevideo.uveditor.ui.export

import android.os.ParcelFileDescriptor
import android.util.Log
import com.ultimatevideo.uveditor.engine.verify.ChannelByteSource
import com.ultimatevideo.uveditor.engine.verify.FrameSignature
import com.ultimatevideo.uveditor.engine.verify.MediaCodecFrameSource
import com.ultimatevideo.uveditor.engine.verify.VerificationOutcome
import com.ultimatevideo.uveditor.engine.verify.VerificationText
import com.ultimatevideo.uveditor.engine.verify.VerifyExpectation
import com.ultimatevideo.uveditor.engine.verify.VerifyRunner
import java.io.FileInputStream
import java.io.IOException

/** The finished file and what the exporter promised about it. */
class VerifyTarget(val uri: String, val expectation: VerifyExpectation, val signatures: List<FrameSignature>)

/**
 * Looks at the closed output file after an export (SPECS.md 5.10). Blocking: the executor calls it on its IO thread.
 * [verify] polls [cancel] and reports 0..1000 through [progress]; it returns [VerificationOutcome.Skipped] when cancelled.
 */
fun interface ExportVerifier {
    fun verify(target: VerifyTarget, cancel: () -> Boolean, progress: (Int) -> Unit): VerificationOutcome
}

/** The verifier of the app: reads the file back through [io] and decodes it with MediaCodec. */
class DeviceExportVerifier(private val io: ExportIO, private val runner: VerifyRunner = VerifyRunner()) : ExportVerifier {
    override fun verify(target: VerifyTarget, cancel: () -> Boolean, progress: (Int) -> Unit): VerificationOutcome {
        val reader = try {
            ParcelFileDescriptor.adoptFd(io.openForRead(target.uri))
        } catch (e: IOException) {
            return VerificationOutcome.CouldNotVerify("the saved file could not be opened (${e.message})")
        }
        reader.use {
            val decoderFd = try {
                ParcelFileDescriptor.adoptFd(io.openForRead(target.uri))
            } catch (e: IOException) {
                return VerificationOutcome.CouldNotVerify("the saved file could not be opened (${e.message})")
            }
            val frames = try {
                MediaCodecFrameSource.open(decoderFd.fileDescriptor, target.expectation, target.signatures.map { it.frame }.toSet(), decoderFd)
            } catch (e: IOException) {
                decoderFd.close()
                Log.w(TAG, "no decoder for the output: ${e.message}")
                null
            }
            try {
                // Not closed here: closing the stream would close the descriptor, which `reader` owns.
                val channel = FileInputStream(reader.fileDescriptor).channel
                val outcome = runner.run(ChannelByteSource(channel), frames, target.expectation, target.signatures, cancel, progress)
                Log.i(TAG, VerificationText.resultLine(outcome))
                return outcome
            } catch (e: IOException) {
                return VerificationOutcome.CouldNotVerify("the saved file could not be read back (${e.message})")
            } catch (e: RuntimeException) {
                // A bug in the verifier must not look like a pass nor take the app down: it is reported as "could not verify".
                Log.e(TAG, "verification failed unexpectedly", e)
                return VerificationOutcome.CouldNotVerify("the check failed inside the app (${e.javaClass.simpleName})")
            } finally {
                frames?.close()
            }
        }
    }

    private companion object {
        const val TAG = "UVVerify"
    }
}
