package com.qtekfun.ultimatevideoeditor

import com.qtekfun.ultimatevideoeditor.ui.editor.layout.PrefsLayoutStore
import android.os.Bundle
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.drawable.ColorDrawable
import com.qtekfun.ultimatevideoeditor.ui.theme.Palette
import com.qtekfun.ultimatevideoeditor.ui.theme.PreferencesAppearanceStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qtekfun.ultimatevideoeditor.crash.CrashReportStore
import com.qtekfun.ultimatevideoeditor.ui.about.AboutController
import com.qtekfun.ultimatevideoeditor.ui.about.AboutScreen
import com.qtekfun.ultimatevideoeditor.ui.about.AppVersion
import com.qtekfun.ultimatevideoeditor.ui.about.StorageMeter
import com.qtekfun.ultimatevideoeditor.ui.onboarding.OnboardingTips
import com.qtekfun.ultimatevideoeditor.ui.onboarding.PreferencesOnboardingStore
import com.qtekfun.ultimatevideoeditor.data.AndroidClipPeeker
import com.qtekfun.ultimatevideoeditor.data.AndroidMediaImporter
import com.qtekfun.ultimatevideoeditor.data.AndroidProjectThumbnails
import com.qtekfun.ultimatevideoeditor.data.PreferencesNewProjectDefaults
import com.qtekfun.ultimatevideoeditor.data.AndroidPersistedUris
import android.graphics.Bitmap
import com.qtekfun.ultimatevideoeditor.data.ContentResolverTransferIO
import com.qtekfun.ultimatevideoeditor.data.ProjectOverview
import com.qtekfun.ultimatevideoeditor.data.interchange.ContentResolverMediaAccess
import com.qtekfun.ultimatevideoeditor.data.interchange.StoreResourceLibrary
import com.qtekfun.ultimatevideoeditor.data.interchange.RepositoryInterchangeExporter
import java.io.ByteArrayOutputStream
import com.qtekfun.ultimatevideoeditor.data.PreferencesSessionStore
import com.qtekfun.ultimatevideoeditor.data.ProjectDirMediaCaches
import com.qtekfun.ultimatevideoeditor.data.FontRegistry
import com.qtekfun.ultimatevideoeditor.data.LutStore
import com.qtekfun.ultimatevideoeditor.data.ProjectRepository
import com.qtekfun.ultimatevideoeditor.data.trimPersistedUris
import com.qtekfun.ultimatevideoeditor.engine.NativeEngineClient
import com.qtekfun.ultimatevideoeditor.engine.timeline.WaveformBeatSource
import com.qtekfun.ultimatevideoeditor.engine.timeline.CachedEnvelopeSource
import com.qtekfun.ultimatevideoeditor.engine.timeline.WaveformCache
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorScreen
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorViewModel
import com.qtekfun.ultimatevideoeditor.ui.editor.loudnessCacheIn
import com.qtekfun.ultimatevideoeditor.ui.hub.HubIntent
import com.qtekfun.ultimatevideoeditor.ui.hub.HubScreen
import com.qtekfun.ultimatevideoeditor.ui.hub.HubViewModel
import com.qtekfun.ultimatevideoeditor.ui.templates.TemplateWizardViewModel
import com.qtekfun.ultimatevideoeditor.data.TemplateStore
import com.qtekfun.ultimatevideoeditor.ui.theme.UVEditorTheme
import com.qtekfun.ultimatevideoeditor.ui.export.ExportCenter
import com.qtekfun.ultimatevideoeditor.ui.export.ExportDestination
import com.qtekfun.ultimatevideoeditor.ui.export.ExportLaunch
import com.qtekfun.ultimatevideoeditor.ui.export.exportDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.qtekfun.ultimatevideoeditor.engine.stabilise.ContentResolverFdOpener
import com.qtekfun.ultimatevideoeditor.engine.sample.AndroidFrameSampler
import com.qtekfun.ultimatevideoeditor.engine.stabilise.FileStabiliser
import com.qtekfun.ultimatevideoeditor.engine.multicam.MulticamServices
import com.qtekfun.ultimatevideoeditor.engine.multicam.WaveformEnvelopeSource
import com.qtekfun.ultimatevideoeditor.engine.preview.DecoderLimits
import com.qtekfun.ultimatevideoeditor.engine.track.FileMotionTracker
import com.qtekfun.ultimatevideoeditor.proxy.ProxyManager
import com.qtekfun.ultimatevideoeditor.engine.track.MediaMetadataAspectProbe
import java.io.File

class MainActivity : ComponentActivity() {
    /** One tap on an export notification; a new instance each time so that a second tap on the same project is handled again. */
    private class ExportTap(val projectId: String?, val bundle: Boolean = false)

    private var exportTap by mutableStateOf<ExportTap?>(null)

    /** Reads the project id of an export notification's intent and clears it, so a rotation or recreation does not replay it. */
    private fun takeExportTap(intent: android.content.Intent?) {
        if (intent?.action != ExportLaunch.ACTION_SHOW) return
        exportTap = ExportTap(intent.getStringExtra(ExportLaunch.EXTRA_PROJECT_ID), intent.getBooleanExtra(ExportLaunch.EXTRA_BUNDLE, false))
        intent.removeExtra(ExportLaunch.EXTRA_PROJECT_ID)
        intent.removeExtra(ExportLaunch.EXTRA_BUNDLE)
        intent.action = null
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeExportTap(intent)
    }

    /** The screen stays on while a movie export or a project backup runs and this activity is visible; the flag is cleared as soon as it ends. */
    private fun keepScreenOnWhileExporting() {
        val executor = com.qtekfun.ultimatevideoeditor.ui.export.ExportCenter.executor(this)
        val bundles = com.qtekfun.ultimatevideoeditor.ui.export.ExportCenter.bundles(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(executor.state, bundles.state) { movie, backup -> movie.isRunning || backup.isRunning }.distinctUntilChanged().collect { running ->
                    if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepScreenOnWhileExporting()
        if (savedInstanceState == null) takeExportTap(intent)
        // Dark only: light system-bar icons on a transparent bar whatever the system theme says.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val transferIO = ContentResolverTransferIO(applicationContext.contentResolver)
        val mediaFolderSettings = com.qtekfun.ultimatevideoeditor.data.PreferencesMediaFolderSettings(applicationContext)
        val projectThumbnails = AndroidProjectThumbnails(applicationContext)
        val repository = ProjectRepository(
            rootDir = File(filesDir, "projects"),
            transferIO = transferIO,
            mediaAccess = ContentResolverMediaAccess(applicationContext.contentResolver),
            // A bundle carries the imported LUTs and fonts the project uses, and installs the ones that come inside it.
            resourceLibrary = StoreResourceLibrary(LutStore(File(filesDir, "luts")), FontRegistry(File(filesDir, "fonts"))),
            // Footage of a LumaFusion package goes into the folder the user chose in About (read each time it is needed).
            mediaFolder = { mediaFolderSettings.folder() },
            log = { message, error -> Log.w("UVImport", message, error) },
            // Footage unpacked from a LumaFusion package is read once for its real length, frame rate and colour space.
            probeMedia = { uri ->
                try {
                    AndroidMediaImporter(applicationContext).probe(android.net.Uri.parse(uri))
                } catch (e: com.qtekfun.ultimatevideoeditor.data.MediaImportException) {
                    Log.w("LumaFusionImport", "Could not read $uri: ${e.message}")
                    null
                }
            },
            // The card picture goes into exported bundles; it is made on this device from the project's own first clip.
            cardThumbnail = { project ->
                projectThumbnails.load(project.id, ProjectOverview.thumbnailSource(project))?.let { bitmap ->
                    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                }
            },
        )
        val bundleJobs = ExportCenter.bundles(applicationContext)
        val interchange = RepositoryInterchangeExporter(repository, transferIO, bundleJobs)
        val mediaImporter = AndroidMediaImporter(applicationContext)
        val session = PreferencesSessionStore(applicationContext)
        val newProjectDefaults = PreferencesNewProjectDefaults(applicationContext)
        val clipPeeker = AndroidClipPeeker(applicationContext)
        val onboardingStore = PreferencesOnboardingStore(applicationContext)
        val appearance = PreferencesAppearanceStore(applicationContext)
        window.setBackgroundDrawable(ColorDrawable(Palette.of(appearance.amoled).background))
        val aboutController = AboutController(
            version = AppVersion.of(this),
            crashStore = CrashReportStore(File(filesDir, "crash")),
            storage = StorageMeter(filesDir, cacheDir),
            onboarding = onboardingStore,
        )
        // Android drops the oldest persisted file permissions past its limit, which would leave old projects
        // with missing media: give back the ones no project uses before that can happen.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                trimPersistedUris(AndroidPersistedUris(applicationContext), repository.referencedMediaUris() + listOfNotNull(mediaFolderSettings.treeUri())) { Log.i("MediaPermissions", it) }
            }
        }
        setContent {
            val amoled = appearance.amoled
            // The window behind Compose follows the choice, so no grey shows during transitions or on rotation.
            LaunchedEffect(amoled) { window.setBackgroundDrawable(ColorDrawable(Palette.of(amoled).background)) }
            UVEditorTheme(amoled = amoled) {
                val hubViewModel: HubViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            HubViewModel(
                                NativeEngineClient(),
                                repository,
                                session = session,
                                defaults = newProjectDefaults,
                                peeker = clipPeeker,
                                mediaFolders = mediaFolderSettings,
                                exportJobs = ExportCenter.executor(applicationContext),
                                bundleJobs = bundleJobs,
                            )
                        }
                    },
                )
                val templateWizard: TemplateWizardViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            TemplateWizardViewModel(
                                repository,
                                TemplateStore(File(filesDir, "templates")),
                                mediaImporter,
                                clipPeeker,
                                transferIO,
                            )
                        }
                    },
                )
                var openProjectId by rememberSaveable { mutableStateOf<String?>(null) }
                var showAbout by rememberSaveable { mutableStateOf(false) }
                var showExportDialog by rememberSaveable { mutableStateOf(false) }
                val tap = exportTap
                LaunchedEffect(tap) {
                    if (tap == null) return@LaunchedEffect
                    val id = tap.projectId
                    // A project that was deleted since the notification was posted must not be opened.
                    val exists = id != null && withContext(Dispatchers.IO) {
                        try {
                            repository.list().projects.any { it.id == id }
                        } catch (e: java.io.IOException) {
                            Log.w("UVExport", "Could not read the project list for a notification tap", e)
                            false
                        }
                    }
                    if (tap.bundle) {
                        // A backup is shown by the project list's bar and by its dialog, over whatever screen is open.
                        bundleJobs.showDetails()
                        exportTap = null
                        return@LaunchedEffect
                    }
                    when (val target = exportDestination(id, exists, ExportCenter.executor(applicationContext).state.value)) {
                        is ExportDestination.Editor -> {
                            session.markOpen(target.projectId)
                            showAbout = false
                            openProjectId = target.projectId
                            showExportDialog = true
                        }
                        ExportDestination.ProjectList -> {
                            if (id != null) {
                                showAbout = false
                                openProjectId = null
                            }
                        }
                    }
                    // Last, because changing the key restarts this effect: clearing it first would cancel the work above.
                    exportTap = null
                }
                val projectId = openProjectId
                if (projectId == null && showAbout) {
                    AboutScreen(aboutController, appearance, mediaFolderSettings, onBack = { showAbout = false })
                } else if (projectId == null) {
                    // Project timestamps and names may have changed while editing.
                    LaunchedEffect(Unit) { hubViewModel.onIntent(HubIntent.Refresh) }
                    HubScreen(
                        hubViewModel,
                        onOpenProject = {
                            session.markOpen(it)
                            openProjectId = it
                        },
                        thumbnails = projectThumbnails,
                        templates = templateWizard,
                        onOpenAbout = { showAbout = true },
                        onOpenExport = {
                            session.markOpen(it)
                            showExportDialog = true
                            openProjectId = it
                        },
                    )
                    // First launch (or after About -> Show tips again): three dismissible tips over the hub.
                    var tipsOpen by remember { mutableStateOf(!onboardingStore.seen()) }
                    if (tipsOpen) OnboardingTips(onFinished = { onboardingStore.markSeen(); tipsOpen = false })
                } else {
                    val owner = rememberScopedViewModelOwner(projectId)
                    val editorViewModel: EditorViewModel = viewModel(
                        viewModelStoreOwner = owner,
                        factory = viewModelFactory {
                            initializer {
                                EditorViewModel(
                                    projectId,
                                    repository,
                                    mediaImporter,
                                    mediaCaches = ProjectDirMediaCaches(File(filesDir, "projects/$projectId")),
                                    beatSource = WaveformBeatSource(WaveformCache(File(filesDir, "projects/$projectId"))),
                                    stabiliser = FileStabiliser(File(filesDir, "projects/$projectId/stab"), ContentResolverFdOpener(contentResolver)),
                                    frameSampler = AndroidFrameSampler(applicationContext),
                                    interchange = interchange,
                                    envelopeSource = CachedEnvelopeSource(WaveformCache(File(filesDir, "projects/$projectId"))),
                                    multicamServices = MulticamServices(
                                        envelopes = WaveformEnvelopeSource(WaveformCache(File(filesDir, "projects/$projectId"))),
                                        hasProxy = ProxyManager.of(applicationContext)::hasUsableProxy,
                                        maxDecoders = DecoderLimits.maxPreviewDecoders(),
                                    ),
                                    loudnessCache = loudnessCacheIn(filesDir),
                                    videoAudioPlacement = PrefsLayoutStore(getSharedPreferences(PrefsLayoutStore.FILE, MODE_PRIVATE)),
                                    motionTracker = FileMotionTracker(
                                        File(filesDir, "projects/$projectId/track"),
                                        ContentResolverFdOpener(contentResolver),
                                        MediaMetadataAspectProbe(applicationContext),
                                    ),
                                )
                            }
                        },
                    )
                    EditorScreen(
                        editorViewModel,
                        projectId,
                        showExport = showExportDialog,
                        onShowExportHandled = { showExportDialog = false },
                        onClose = {
                            // Leaving on purpose: the next start has nothing to offer to reopen.
                            session.markClosed()
                            openProjectId = null
                        },
                    )
                }
                // Over every screen: a project backup's progress and result, whichever screen started it.
                com.qtekfun.ultimatevideoeditor.ui.library.BundleJobDialog(bundleJobs)
            }
        }
    }
}

/**
 * A ViewModel owner that lives only while the editor for [key] is on screen, so reopening a
 * project always starts a fresh session from disk instead of reusing a stale one.
 */
@Composable
private fun rememberScopedViewModelOwner(key: String): ViewModelStoreOwner {
    val owner = remember(key) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}
