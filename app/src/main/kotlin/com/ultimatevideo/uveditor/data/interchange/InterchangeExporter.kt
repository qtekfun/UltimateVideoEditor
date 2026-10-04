package com.ultimatevideo.uveditor.data.interchange

import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.data.ProjectTransferIO
import java.io.IOException

/** Writes the files an export to another tool produces. The editor only knows this interface. */
interface InterchangeExporter {
    /** Writes the bundle of the saved project [projectId] to [uri]; the project must already be on disk. */
    @Throws(IOException::class)
    suspend fun exportBundle(projectId: String, uri: String, includeMedia: Boolean): BundleWriteResult

    /**
     * Like the overload above, with the LUT and font choices of the export dialog. The default ignores them,
     * which is right for exporters that cannot carry resources.
     */
    @Throws(IOException::class)
    suspend fun exportBundle(projectId: String, uri: String, choice: BundleChoice): BundleWriteResult =
        exportBundle(projectId, uri, choice.includeMedia)

    /** What a bundle of the saved project [projectId] could contain, for the dialog that asks what to include. */
    @Throws(IOException::class)
    suspend fun bundlePreview(projectId: String): BundlePreview = BundlePreview.EMPTY

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

    override suspend fun exportBundle(projectId: String, uri: String, choice: BundleChoice): BundleWriteResult =
        repository.exportBundle(projectId, uri, choice)

    override suspend fun bundlePreview(projectId: String): BundlePreview = repository.bundlePreview(projectId)

    override suspend fun writeDocument(uri: String, bytes: ByteArray) = io.write(uri, bytes)
}
