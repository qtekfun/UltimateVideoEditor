package com.ultimatevideo.uveditor

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
import com.ultimatevideo.uveditor.ui.theme.Palette
import com.ultimatevideo.uveditor.ui.theme.PreferencesAppearanceStore
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
import com.ultimatevideo.uveditor.crash.CrashReportStore
import com.ultimatevideo.uveditor.ui.about.AboutController
import com.ultimatevideo.uveditor.ui.about.AboutScreen
import com.ultimatevideo.uveditor.ui.about.AppVersion
import com.ultimatevideo.uveditor.ui.about.StorageMeter
import com.ultimatevideo.uveditor.ui.onboarding.OnboardingTips
import com.ultimatevideo.uveditor.ui.onboarding.PreferencesOnboardingStore
import com.ultimatevideo.uveditor.data.AndroidClipPeeker
import com.ultimatevideo.uveditor.data.AndroidMediaImporter
import com.ultimatevideo.uveditor.data.AndroidProjectThumbnails
import com.ultimatevideo.uveditor.data.PreferencesNewProjectDefaults
import com.ultimatevideo.uveditor.data.AndroidPersistedUris
import android.graphics.Bitmap
import com.ultimatevideo.uveditor.data.ContentResolverTransferIO
import com.ultimatevideo.uveditor.data.ProjectOverview
import com.ultimatevideo.uveditor.data.interchange.ContentResolverMediaAccess
import com.ultimatevideo.uveditor.data.interchange.StoreResourceLibrary
import com.ultimatevideo.uveditor.data.interchange.RepositoryInterchangeExporter
import java.io.ByteArrayOutputStream
import com.ultimatevideo.uveditor.data.PreferencesSessionStore
import com.ultimatevideo.uveditor.data.ProjectDirMediaCaches
import com.ultimatevideo.uveditor.data.FontRegistry
import com.ultimatevideo.uveditor.data.LutStore
import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.data.trimPersistedUris
import com.ultimatevideo.uveditor.engine.NativeEngineClient
import com.ultimatevideo.uveditor.engine.timeline.WaveformBeatSource
import com.ultimatevideo.uveditor.engine.timeline.CachedEnvelopeSource
import com.ultimatevideo.uveditor.engine.timeline.WaveformCache
import com.ultimatevideo.uveditor.ui.editor.EditorScreen
import com.ultimatevideo.uveditor.ui.editor.EditorViewModel
import com.ultimatevideo.uveditor.ui.editor.loudnessCacheIn
import com.ultimatevideo.uveditor.ui.hub.HubIntent
import com.ultimatevideo.uveditor.ui.hub.HubScreen
import com.ultimatevideo.uveditor.ui.hub.HubViewModel
import com.ultimatevideo.uveditor.ui.templates.TemplateWizardViewModel
import com.ultimatevideo.uveditor.data.TemplateStore
import com.ultimatevideo.uveditor.ui.theme.UVEditorTheme
import com.ultimatevideo.uveditor.ui.export.ExportCenter
import com.ultimatevideo.uveditor.ui.export.ExportDestination
import com.ultimatevideo.uveditor.ui.export.ExportLaunch
import com.ultimatevideo.uveditor.ui.export.exportDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ultimatevideo.uveditor.engine.stabilise.ContentResolverFdOpener
import com.ultimatevideo.uveditor.engine.sample.AndroidFrameSampler
import com.ultimatevideo.uveditor.engine.stabilise.FileStabiliser
import com.ultimatevideo.uveditor.engine.multicam.MulticamServices
import com.ultimatevideo.uveditor.engine.multicam.WaveformEnvelopeSource
import com.ultimatevideo.uveditor.engine.preview.DecoderLimits
import com.ultimatevideo.uveditor.engine.track.FileMotionTracker
import com.ultimatevideo.uveditor.proxy.ProxyManager
import com.ultimatevideo.uveditor.engine.track.MediaMetadataAspectProbe
import java.io.File

class MainActivity : ComponentActivity() {
    /** One tap on an export notification; a new instance each time so that a second tap on the same project is handled again. */
    private class ExportTap(val projectId: String?)

    private var exportTap by mutableStateOf<ExportTap?>(null)

    /** Reads the project id of an export notification's intent and clears it, so a rotation or recreation does not replay it. */
    private fun takeExportTap(intent: android.content.Intent?) {
        if (intent?.action != ExportLaunch.ACTION_SHOW) return
        exportTap = ExportTap(intent.getStringExtra(ExportLaunch.EXTRA_PROJECT_ID))
        intent.removeExtra(ExportLaunch.EXTRA_PROJECT_ID)
        intent.action = null
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeExportTap(intent)
    }

    /** The screen stays on while an export runs and this activity is visible; the flag is cleared as soon as it ends. */
    private fun keepScreenOnWhileExporting() {
        val executor = com.ultimatevideo.uveditor.ui.export.ExportCenter.executor(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                executor.state.map { it.isRunning }.distinctUntilChanged().collect { running ->
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
        val mediaFolderSettings = com.ultimatevideo.uveditor.data.PreferencesMediaFolderSettings(applicationContext)
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
                } catch (e: com.ultimatevideo.uveditor.data.MediaImportException) {
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
        val interchange = RepositoryInterchangeExporter(repository, transferIO)
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
