package com.ultimatevideo.uveditor.data.interchange

import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.data.ProjectTransferIO
import java.io.IOException

/** Writes the files an export to another tool produces. The editor only knows this interface. */
interface InterchangeExporter {
    /** Writes the bundle of the saved project [projectId] to [uri]; the project must already be on disk. */
    @Throws(IOException::class)
    suspend fun exportBundle(projectId: String, uri: String, includeMedia: Boolean): BundleWriteResult

    /** Replaces the document at [uri] with [bytes] (an EDL, a zip of EDLs or an FCPXML file). */
    @Throws(IOException::class)
    suspend fun writeDocument(uri: String, bytes: ByteArray)

    /** For screens and tests that have no way to write: every call fails with a message. */
    object None : InterchangeExporter {
        override suspend fun exportBundle(projectId: String, uri: String, includeMedia: Boolean): BundleWriteResult =
            throw IOException("Exporting is not available here")

        override suspend fun writeDocument(uri: String, bytes: ByteArray) = throw IOException("Exporting is not available here")
    }
}

/** The app's exporter: bundles go through the project repository, plain documents through the same document IO as project files. */
class RepositoryInterchangeExporter(
    private val repository: ProjectRepository,
    private val io: ProjectTransferIO,
) : InterchangeExporter {
    override suspend fun exportBundle(projectId: String, uri: String, includeMedia: Boolean): BundleWriteResult =
        repository.exportBundle(projectId, uri, includeMedia)

    override suspend fun writeDocument(uri: String, bytes: ByteArray) = io.write(uri, bytes)
}
