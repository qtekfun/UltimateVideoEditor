package com.qtekfun.ultimatevideoeditor.data.interchange

import com.qtekfun.ultimatevideoeditor.data.FontRegistry
import com.qtekfun.ultimatevideoeditor.data.LutStore
import com.qtekfun.ultimatevideoeditor.data.ProjectJson
import com.qtekfun.ultimatevideoeditor.data.ProjectRepository
import com.qtekfun.ultimatevideoeditor.data.ProjectTransferIO
import com.qtekfun.ultimatevideoeditor.data.fakeFont
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.EffectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.TitleDto
import com.qtekfun.ultimatevideoeditor.data.model.TitleLayerDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** LUTs and fonts travelling inside a project bundle: collecting, writing, reading, installing, re-keying. */
@OptIn(ExperimentalCoroutinesApi::class)
class BundleResourcesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val files = HashMap<String, ByteArray>()
    private var counter = 0

    private val io = object : ProjectTransferIO {
        override fun read(uri: String) = files[uri] ?: throw java.io.FileNotFoundException(uri)

        override fun write(uri: String, bytes: ByteArray) {
            files[uri] = bytes
        }
    }

    private fun cube(size: Int, tweak: Float = 0f) = buildString {
        append("LUT_3D_SIZE $size\n")
        val d = (size - 1).toFloat()
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) append("${r / d + tweak} ${g / d} ${b / d}\n")
    }

    /** One device: its own project folder and its own LUT and font libraries. */
    private inner class Device(name: String) {
        val luts = LutStore(File(tmp.root, "$name-luts"))
        val fonts = FontRegistry(File(tmp.root, "$name-fonts"))
        val library = StoreResourceLibrary(luts, fonts)
        val repo = ProjectRepository(
            rootDir = File(tmp.root, "$name-projects"),
            transferIO = io,
            ioDispatcher = UnconfinedTestDispatcher(),
            idGenerator = { "id-$name-${++counter}" },
            resourceLibrary = library,
        )
    }

    /** The sample project with a LUT effect on its first base clip and a title with a text layer in [fontId]. */
    private fun projectWith(lutKey: Int?, fontId: String?): ProjectDto {
        val base = sampleProject()
        val tracks = base.tracks.map { track ->
            when (track.id) {
                "v1" -> track.copy(
                    clips = track.clips.mapIndexed { i, c ->
                        if (i == 0 && lutKey != null) {
                            c.copy(effects = listOf(EffectDto("e-brightness", "brightness", listOf(0.1)), EffectDto("e-lut", "lut", listOf(lutKey.toDouble(), 0.8))))
                        } else {
                            c
                        }
                    },
                )
                "t1" -> track.copy(
                    clips = track.clips.map { c ->
                        if (fontId != null) c.copy(title = TitleDto(text = "Hi", layers = listOf(TitleLayerDto("text", text = "Hi", font = fontId), TitleLayerDto("shape")))) else c
                    },
                )
                else -> track
            }
        }
        return base.copy(tracks = tracks)
    }

    private fun lutKeysOf(project: ProjectDto): List<Int> =
        project.tracks.flatMap { it.clips }.flatMap { it.effects }.filter { it.type == "lut" }.map { it.values.first().toInt() }

    private fun fontsOf(project: ProjectDto): List<String> =
        project.tracks.flatMap { it.clips }.mapNotNull { it.title }.flatMap { it.layers }.mapNotNull { it.font }

    // region collecting references

    @Test
    fun `references are collected from effects and text layers and nothing else`() {
        val project = projectWith(lutKey = 4242, fontId = "00112233aabbccdd")
        val refs = ResourceRefs.collect(ProjectJson.parseObject(ProjectJson.encode(project)))
        assertEquals(setOf(4242), refs.lutKeys)
        assertEquals(setOf("00112233aabbccdd"), refs.fontIds)
        assertTrue(ResourceRefs.collect(ProjectJson.parseObject(ProjectJson.encode(sampleProject()))).isEmpty)
    }

    @Test
    fun `fields this build does not know cannot hide a reference and odd values are not references`() {
        val raw = ProjectJson.parseObject(
            """{"tracks":[{"id":"t","clips":[
                {"id":"c","effects":[
                    {"type":"lut","values":[17.0,1.0],"future":true},
                    {"type":"lut","values":[3.5,1.0]},
                    {"type":"lut","values":[0.0,1.0]},
                    {"type":"lut","values":[]},
                    {"type":"blur","values":[9.0]}],
                 "title":{"text":"x","layers":[{"type":"text","font":"abc"},{"type":"text","font":""},{"type":"shape","font":"ignored"},{"type":"text"}]}}]}]}""",
        )
        val refs = ResourceRefs.collect(raw)
        assertEquals(setOf(17), refs.lutKeys)
        assertEquals(setOf("abc"), refs.fontIds)
    }

    @Test
    fun `rewriting a LUT key touches only the first value of LUT effects`() {
        val raw = ProjectJson.parseObject(ProjectJson.encode(projectWith(lutKey = 500, fontId = null)))
        val rewritten = ResourceRefs.withLutKeys(raw, mapOf(500 to 501, 9 to 10))
        val project = ProjectJson.decode(rewritten.toString())
        val effects = project.tracks.flatMap { it.clips }.flatMap { it.effects }
        assertEquals(listOf(501), lutKeysOf(project))
        assertEquals(0.8, effects.first { it.type == "lut" }.values[1], 0.0)
        assertEquals(listOf(0.1), effects.first { it.type == "brightness" }.values)
        // An empty map is the same object, and a key that is not in the map is left alone.
        assertTrue(ResourceRefs.withLutKeys(raw, emptyMap()) === raw)
        assertEquals(listOf(500), lutKeysOf(ProjectJson.decode(ResourceRefs.withLutKeys(raw, mapOf(9 to 10)).toString())))
    }

    // endregion

    // region round trips

    @Test
    fun `a bundle carries the LUT and the font and installs them on another device`() = runBlocking {
        val source = Device("a")
        val lutText = cube(5)
        val lut = source.luts.import("Teal and orange.cube", lutText)
        val font = source.fonts.import(fakeFont("Lobster Test"))
        source.repo.save(projectWith(lut.key, font.id))

        val result = source.repo.exportBundle("p1", "doc://b", BundleChoice(includeMedia = false, includeLuts = true, includeFonts = true))
        assertEquals(2, result.resourcesIncluded)
        assertEquals(emptyList<String>(), result.resourcesSkipped)

        val target = Device("b")
        val report = target.repo.importWithReport("doc://b")
        val res = report.bundle!!.resources
        assertEquals(2, res.installed.size)
        assertEquals(emptyList<Any>(), res.failed)
        assertEquals(emptyList<String>(), res.missing)
        assertEquals(lutText, target.luts.readText(lut.key))
        assertEquals("Lobster Test", target.fonts.family(font.id))
        // Same keys, so the project needs no rewriting.
        val imported = target.repo.load(report.project.id)
        assertEquals(listOf(lut.key), lutKeysOf(imported))
        assertEquals(listOf(font.id), fontsOf(imported))
    }

    @Test
    fun `fonts stay out of the bundle unless chosen and the importer reports them as missing`() = runBlocking {
        val source = Device("a")
        val lut = source.luts.import("a.cube", cube(2))
        val font = source.fonts.import(fakeFont("Private Font"))
        source.repo.save(projectWith(lut.key, font.id))

        val result = source.repo.exportBundle("p1", "doc://b", BundleChoice(includeMedia = false))
        assertEquals(1, result.resourcesIncluded)
        assertFalse(BundleChoice().includeFonts)
        assertTrue(BundleChoice().includeLuts)

        // The font stays out but the manifest still names it, so the importer can say which one to get.
        val manifest = ProjectBundle.extract(files.getValue("doc://b").inputStream(), tmp.newFolder()).manifest
        val reference = manifest.resources.single { it.kind == "font" }
        assertEquals("Private Font", reference.name)
        assertNull(reference.entry)

        val target = Device("b")
        val res = target.repo.importWithReport("doc://b").bundle!!.resources
        assertEquals(1, res.installed.size)
        assertEquals(listOf("font Private Font"), res.missing)
        assertNull(target.fonts.file(font.id))
    }

    @Test
    fun `a resource already on the device is not copied again`() = runBlocking {
        val source = Device("a")
        val lut = source.luts.import("a.cube", cube(3))
        source.repo.save(projectWith(lut.key, null))
        source.repo.exportBundle("p1", "doc://b", BundleChoice())

        val target = Device("b")
        target.luts.import("same file under another name.cube", cube(3))
        val before = File(tmp.root, "b-luts").listFiles().orEmpty().map { it.name }
        val res = target.repo.importWithReport("doc://b").bundle!!.resources
        assertEquals(listOf("LUT a"), res.alreadyHere)
        assertEquals(emptyList<String>(), res.installed)
        assertEquals(before, File(tmp.root, "b-luts").listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `a different LUT on the same key gets the next key and the project is rewritten`() = runBlocking {
        val source = Device("a")
        val wanted = cube(4)
        val lut = source.luts.import("wanted.cube", wanted)
        source.repo.save(projectWith(lut.key, null))
        source.repo.exportBundle("p1", "doc://b", BundleChoice())

        val target = Device("b")
        // A different LUT already sits on the key the bundle's LUT uses (a hash clash).
        val squatter = target.luts.install("squatter", cube(2, tweak = 0.25f), lut.key)
        assertEquals(lut.key, squatter.info.key)

        val first = target.repo.importWithReport("doc://b")
        val res = first.bundle!!.resources
        assertEquals(listOf("LUT wanted"), res.rekeyed)
        val newKey = lut.key + 1
        assertEquals(listOf(newKey), lutKeysOf(target.repo.load(first.project.id)))
        assertEquals(wanted, target.luts.readText(newKey))
        assertEquals(cube(2, tweak = 0.25f), target.luts.readText(lut.key))

        // Importing again finds the LUT it placed before: no third copy, same rewritten key.
        val second = target.repo.importWithReport("doc://b")
        assertEquals(listOf("LUT wanted"), second.bundle!!.resources.alreadyHere)
        assertEquals(listOf(newKey), lutKeysOf(target.repo.load(second.project.id)))
        assertEquals(2, target.luts.list().size)
    }

    @Test
    fun `an unavailable resource is named instead of failing the export`() = runBlocking {
        val source = Device("a")
        source.repo.save(projectWith(lutKey = 777, fontId = "deadbeefdeadbeef"))
        val result = source.repo.exportBundle("p1", "doc://b", BundleChoice(includeLuts = true, includeFonts = true))
        assertEquals(0, result.resourcesIncluded)
        assertEquals(2, result.resourcesSkipped.size)
        val target = Device("b")
        val res = target.repo.importWithReport("doc://b").bundle!!.resources
        assertEquals(2, res.missing.size)
    }

    @Test
    fun `the same input gives the same bundle bytes`() = runBlocking {
        val source = Device("a")
        val lut = source.luts.import("a.cube", cube(3))
        val font = source.fonts.import(fakeFont("Det Font"))
        source.repo.save(projectWith(lut.key, font.id))
        val choice = BundleChoice(includeFonts = true)
        source.repo.exportBundle("p1", "doc://one", choice, thumbnails = mapOf("project.jpg" to byteArrayOf(1, 2, 3)))
        source.repo.exportBundle("p1", "doc://two", choice, thumbnails = mapOf("project.jpg" to byteArrayOf(1, 2, 3)))
        assertTrue(files.getValue("doc://one").contentEquals(files.getValue("doc://two")))
    }

    // endregion

    // region validating and bounding

    private fun manifestJson(resources: String) =
        """{"format":"uveditor-bundle","formatVersion":1,"projectName":"P","resources":$resources}"""

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
    }.toByteArray()

    private fun extract(bytes: ByteArray, limits: BundleLimits = BundleLimits()): ExtractedBundle =
        ProjectBundle.extract(bytes.inputStream(), tmp.newFolder(), limits)

    private val projectText get() = ProjectJson.encode(sampleProject())

    @Test
    fun `a damaged resource or one that is not a LUT or a font is reported and the rest is installed`() = runBlocking {
        val goodLut = cube(2).toByteArray()
        val goodFont = fakeFont("Good One")
        val goodId = FontRegistry.idOf(goodFont)
        val manifest = manifestJson(
            """[
              {"kind":"lut","key":"11","name":"Good.cube","sizeBytes":${goodLut.size},"sha256":"${Hashes.sha256Hex(goodLut)}","entry":"resources/lut-11-Good.cube"},
              {"kind":"lut","key":"12","name":"Tampered.cube","sizeBytes":5,"sha256":"${"0".repeat(64)}","entry":"resources/lut-12-Tampered.cube"},
              {"kind":"lut","key":"13","name":"Junk.cube","sizeBytes":4,"sha256":"${Hashes.sha256Hex("junk".toByteArray())}","entry":"resources/lut-13-Junk.cube"},
              {"kind":"font","key":"$goodId","name":"Good One.ttf","sha256":"${Hashes.sha256Hex(goodFont)}","entry":"resources/font-$goodId.ttf"},
              {"kind":"font","key":"ffffffffffffffff","name":"Wrong id.ttf","sha256":"${Hashes.sha256Hex(goodFont)}","entry":"resources/font-wrong.ttf"},
              {"kind":"font","key":"1111111111111111","name":"Not a font.ttf","sha256":"${Hashes.sha256Hex("nope".toByteArray())}","entry":"resources/font-nope.ttf"},
              {"kind":"hologram","key":"1","name":"Future.bin","entry":"resources/future.bin"}]""",
        )
        files["doc://x"] = zip(
            "bundle.json" to manifest.toByteArray(),
            "project.json" to projectText.toByteArray(),
            "resources/lut-11-Good.cube" to goodLut,
            "resources/lut-12-Tampered.cube" to "tampered".toByteArray(),
            "resources/lut-13-Junk.cube" to "junk".toByteArray(),
            "resources/font-$goodId.ttf" to goodFont,
            "resources/font-wrong.ttf" to goodFont,
            "resources/font-nope.ttf" to "nope".toByteArray(),
            "resources/future.bin" to byteArrayOf(1),
        )
        val target = Device("t")
        val res = target.repo.importWithReport("doc://x").bundle!!.resources
        assertEquals(listOf("LUT Good", "font Good One"), res.installed)
        val reasons = res.failed.associate { it.name to it.reason }
        assertEquals(5, reasons.size)
        assertTrue(reasons.getValue("LUT Tampered"), reasons.getValue("LUT Tampered").contains("checksum"))
        assertTrue(reasons.getValue("LUT Junk"), reasons.getValue("LUT Junk").contains("not a valid .cube"))
        assertTrue(reasons.getValue("font Wrong id"), reasons.getValue("font Wrong id").contains("does not match"))
        assertTrue(reasons.getValue("font Not a font").isNotEmpty())
        assertTrue(reasons.getValue("Future.bin"), reasons.getValue("Future.bin").contains("does not know"))
        assertEquals(1, target.luts.list().size)
        assertEquals(1, target.fonts.list().size)
    }

    @Test
    fun `a build without libraries reports resources it cannot install`() = runBlocking {
        val lutBytes = cube(2).toByteArray()
        val manifest = manifestJson("""[{"kind":"lut","key":"5","name":"A.cube","sha256":"${Hashes.sha256Hex(lutBytes)}","entry":"resources/lut-5-A.cube"}]""")
        files["doc://x"] = zip("bundle.json" to manifest.toByteArray(), "project.json" to projectText.toByteArray(), "resources/lut-5-A.cube" to lutBytes)
        val bare = ProjectRepository(File(tmp.root, "bare"), io, UnconfinedTestDispatcher(), idGenerator = { "bare-${++counter}" })
        val res = bare.importWithReport("doc://x").bundle!!.resources
        assertEquals(1, res.failed.size)
        assertTrue(res.failed.single().reason, res.failed.single().reason.contains("cannot install"))
    }

    @Test
    fun `resource sizes and counts are bounded and names cannot escape`() {
        val manifest = manifestJson("[]").toByteArray()
        val big = zip("bundle.json" to manifest, "project.json" to projectText.toByteArray(), "resources/lut-1-big.cube" to ByteArray(2048))
        assertThrows(BundleError.TooLarge::class.java) { extract(big, BundleLimits(maxResourceBytes = 1024)) }

        val many = zip(
            "bundle.json" to manifest,
            "project.json" to projectText.toByteArray(),
            "resources/a" to byteArrayOf(1),
            "resources/b" to byteArrayOf(1),
            "resources/c" to byteArrayOf(1),
        )
        assertThrows(BundleError.TooLarge::class.java) { extract(many, BundleLimits(maxResources = 2)) }
        assertThrows(BundleError.TooLarge::class.java) { extract(many, BundleLimits(maxResourceTotalBytes = 2)) }

        for (evil in listOf("resources/../escaped.cube", "resources/sub/dir.cube", "resources//x", "/resources/x")) {
            val bytes = zip("bundle.json" to manifest, "project.json" to projectText.toByteArray(), evil to byteArrayOf(1))
            assertThrows(evil, BundleError::class.java) { extract(bytes) }
        }
        assertFalse(File(tmp.root, "escaped.cube").exists())
    }

    @Test
    fun `a manifest entry that points outside resources is a damaged bundle`() {
        val manifest = manifestJson("""[{"kind":"lut","key":"1","name":"A","entry":"media/lut.cube"}]""")
        val bytes = zip("bundle.json" to manifest.toByteArray(), "project.json" to projectText.toByteArray())
        assertThrows(BundleError.Corrupt::class.java) { extract(bytes) }
    }

    // endregion

    // region compatibility

    @Test
    fun `a bundle from before resources existed imports as it did and reports a LUT it needs as missing`() = runBlocking {
        val project = projectWith(lutKey = 31337, fontId = null)
        val out = ByteArrayOutputStream()
        ProjectBundle.write(ProjectJson.encode(project), project, null, false, emptyMap(), out)
        files["doc://old"] = out.toByteArray()
        val extracted = extract(out.toByteArray())
        assertEquals(emptyList<BundleResource>(), extracted.manifest.resources)
        assertTrue(extracted.resourceFiles.isEmpty())

        val target = Device("t")
        val res = target.repo.importWithReport("doc://old").bundle!!.resources
        assertEquals(ResourceImportReport(missing = listOf("LUT #31337")), res)
    }

    @Test
    fun `entries this build does not know and manifest keys from the future are ignored`() {
        val manifest = """{"format":"uveditor-bundle","formatVersion":1,"resourcesVersion":2,"somethingNew":{"a":1},"resources":[]}"""
        val bytes = zip("bundle.json" to manifest.toByteArray(), "project.json" to projectText.toByteArray(), "future/extra.bin" to byteArrayOf(9))
        val extracted = extract(bytes)
        assertEquals(1, extracted.manifest.formatVersion)
        assertTrue(extracted.resourceFiles.isEmpty())
    }

    @Test
    fun `the format version stays 1 so older builds keep opening new bundles, and a newer one is refused`() {
        assertEquals(1, BundleManifest.FORMAT_VERSION)
        val manifest = """{"format":"uveditor-bundle","formatVersion":2,"resources":[]}"""
        val bytes = zip("bundle.json" to manifest.toByteArray(), "project.json" to projectText.toByteArray())
        assertThrows(BundleError.UnsupportedVersion::class.java) { extract(bytes) }
    }

    @Test
    fun `an older reader sees the same bundle without the resources key`() {
        // What an older build does: decode the manifest ignoring unknown keys, extract skips entries it does not know.
        val lut = ResourcePayload(ResourceKind.LUT, "7", ResourceBytes("A.cube", cube(2).toByteArray()))
        val out = ByteArrayOutputStream()
        ProjectBundle.write(projectText, sampleProject(), null, false, emptyMap(), out, ResourcePlan(listOf(lut), emptyList()))
        val legacy = ZipEntryReader.read(out.toByteArray(), "bundle.json")
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(legacy) as kotlinx.serialization.json.JsonObject
        assertEquals("uveditor-bundle", (parsed["format"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("1", (parsed["formatVersion"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue(parsed.containsKey("resources"))
        // The keys an older build reads are all still there.
        assertTrue(parsed.keys.containsAll(listOf("format", "formatVersion", "app", "projectName", "schemaVersion", "media", "thumbnails")))
    }

    // endregion

    // region the dialog's numbers

    @Test
    fun `the preview adds up what the choice puts in`() = runBlocking {
        val source = Device("a")
        val lutText = cube(5)
        val lut = source.luts.import("a.cube", lutText)
        val font = source.fonts.import(fakeFont("Preview Font"))
        source.repo.save(projectWith(lut.key, font.id))
        val preview = source.repo.bundlePreview("p1")
        assertEquals(1, preview.luts.size)
        assertEquals(1, preview.fonts.size)
        assertTrue(preview.luts.single().available && preview.fonts.single().available)
        assertEquals(lutText.toByteArray().size.toLong(), preview.luts.single().sizeBytes)
        assertEquals(lutText.toByteArray().size.toLong(), preview.estimatedBytes(BundleChoice()))
        assertEquals(0L, preview.estimatedBytes(BundleChoice(includeLuts = false)))
        assertEquals(
            lutText.toByteArray().size.toLong() + preview.fonts.single().sizeBytes,
            preview.estimatedBytes(BundleChoice(includeFonts = true)),
        )
        // No media access in this repository: every file of the library is unreadable and none is counted.
        assertEquals(0, preview.mediaCount)
        assertEquals(sampleProject().mediaLibrary.size, preview.mediaUnreadable.size)
    }

    @Test
    fun `a resource the device does not hold is shown but not counted`() = runBlocking {
        val source = Device("a")
        source.repo.save(projectWith(lutKey = 99, fontId = null))
        val preview = source.repo.bundlePreview("p1")
        val info = preview.luts.single()
        assertFalse(info.available)
        assertEquals("LUT #99", info.name)
        assertEquals(0L, preview.estimatedBytes(BundleChoice(includeMedia = true)))
        assertTrue(preview.hasResources)
        assertFalse(BundlePreview.EMPTY.hasResources)
    }

    // endregion

    // region the stores

    @Test
    fun `a LUT store places a clashing LUT on the next key and finds identical content again`() {
        val store = LutStore(File(tmp.root, "luts"))
        val a = store.install("a", cube(2), 100)
        assertEquals(LutStore.InstallStatus.ADDED, a.status)
        val b = store.install("b", cube(3), 100)
        assertEquals(LutStore.InstallStatus.REKEYED, b.status)
        assertEquals(101, b.info.key)
        val again = store.install("b again", cube(3), 100)
        assertEquals(LutStore.InstallStatus.ALREADY_PRESENT, again.status)
        assertEquals(101, again.info.key)
        assertEquals(2, store.list().size)
        assertNotNull(store.info(101))
        assertNull(store.readText(102))
    }

    @Test
    fun `a font install says whether it was new and refuses a clash`() {
        val registry = FontRegistry(File(tmp.root, "fonts"))
        val bytes = fakeFont("Stored Font")
        val first = registry.install(bytes)
        assertEquals(FontRegistry.InstallStatus.ADDED, first.status)
        assertEquals(FontRegistry.InstallStatus.ALREADY_PRESENT, registry.install(bytes).status)
        // Different bytes under the same id: only possible by tampering, and never overwritten.
        val id = first.entry.id
        first.entry.file.writeBytes(fakeFont("Another Font"))
        assertEquals(FontRegistry.InstallStatus.CONFLICT, registry.install(bytes).status)
        assertEquals("Another Font", registry.family(id))
    }

    // endregion
}

/** Reads one entry of a zip held in memory. */
private object ZipEntryReader {
    fun read(zip: ByteArray, name: String): String {
        java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == name) return z.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("no $name in the zip")
    }
}
