package com.qtekfun.ultimatevideoeditor.ui.editor.proxy

import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatevideoeditor.data.MissingMedia
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.mvi.MviViewModel
import com.qtekfun.ultimatevideoeditor.mvi.UiEffect
import com.qtekfun.ultimatevideoeditor.mvi.UiIntent
import com.qtekfun.ultimatevideoeditor.mvi.UiState
import com.qtekfun.ultimatevideoeditor.proxy.MediaPurpose
import com.qtekfun.ultimatevideoeditor.proxy.ProxyManager
import com.qtekfun.ultimatevideoeditor.proxy.ProxyPrefs
import com.qtekfun.ultimatevideoeditor.proxy.ProxyStatus
import com.qtekfun.ultimatevideoeditor.proxy.ProxySuggester
import com.qtekfun.ultimatevideoeditor.proxy.ProxySuggestion
import com.qtekfun.ultimatevideoeditor.proxy.ProxyTargets
import com.qtekfun.ultimatevideoeditor.proxy.ProxyUsage
import com.qtekfun.ultimatevideoeditor.proxy.ResolvedSource
import com.qtekfun.ultimatevideoeditor.proxy.canHaveProxy
import kotlinx.coroutines.launch

/** One video of the project in the proxy sheet. */
data class ProxyItem(val assetId: String, val name: String, val status: ProxyStatus)

data class ProxyUiState(
    /** The project's "Use proxies for editing" switch. */
    val enabled: Boolean = false,
    val items: List<ProxyItem> = emptyList(),
    val usage: ProxyUsage = ProxyUsage(0, ProxyPrefs.DEFAULT_BUDGET_BYTES),
    val targetShortSide: Int = ProxyTargets.SHORT_SIDE_720,
    val suggestion: ProxySuggestion? = null,
    val sheetOpen: Boolean = false,
    val confirmClear: Boolean = false,
    val message: String? = null,
    /** Changes whenever what the preview and thumbnails should open changes (switch, proxy ready, settings). */
    val resolveVersion: Int = 0,
) : UiState {
    /** Status by asset id, for the badges of the tray and the library. */
    val statuses: Map<String, ProxyStatus> get() = items.associate { it.assetId to it.status }
}

sealed interface ProxyIntent : UiIntent {
    data class SetAssets(val assets: List<MediaAssetDto>) : ProxyIntent
    data class SetEnabled(val enabled: Boolean) : ProxyIntent
    data object GenerateAll : ProxyIntent
    data class Generate(val assetId: String) : ProxyIntent
    data class Remove(val assetId: String) : ProxyIntent
    data class SetBudget(val bytes: Long) : ProxyIntent
    data class SetTarget(val shortSide: Int) : ProxyIntent
    data object RequestClear : ProxyIntent
    data object ConfirmClear : ProxyIntent
    data object CancelClear : ProxyIntent
    data object DismissSuggestion : ProxyIntent
    data object AcceptSuggestion : ProxyIntent
    data object OpenSheet : ProxyIntent
    data object CloseSheet : ProxyIntent

    /** The preview stalled while playing: after a few, proxies are suggested even for lighter media. */
    data object ReportStall : ProxyIntent

    /** An asset is decoded in software and is too heavy for real time: suggest proxies right away. */
    data object SoftwareDecodeHeavy : ProxyIntent

    /** The preview could not open the proxy of this asset and fell back to the original. */
    data class PreviewProxyFailed(val assetId: String) : ProxyIntent
    data object ClearMessage : ProxyIntent
}

sealed interface ProxyEffect : UiEffect

/**
 * The proxy side of one editing session: the switch, the queue as the user sees it, the suggestion and the
 * storage settings. It also answers the editor's question "which file do I open for this asset?" through
 * [resolve]; export never asks it.
 */
class ProxyViewModel(
    private val manager: ProxyManager,
    private val projectId: String,
) : MviViewModel<ProxyUiState, ProxyIntent, ProxyEffect>(ProxyUiState(enabled = manager.isEnabled(projectId))) {

    private var assets: List<MediaAssetDto> = emptyList()
    private var stalls = 0

    init {
        viewModelScope.launch {
            manager.changes.collect { refresh(bumpResolve = true) }
        }
        // The percentage of the proxy being made moves the sheet and the badges, not the files the preview opens.
        viewModelScope.launch {
            manager.progress.collect { refresh() }
        }
    }

    /** The source to open for [asset]: the proxy while editing with proxies on and ready, else the original. */
    fun resolve(asset: MediaAssetDto, purpose: MediaPurpose): ResolvedSource = manager.resolve(asset, purpose, projectId)

    override fun onIntent(intent: ProxyIntent) {
        when (intent) {
            is ProxyIntent.SetAssets -> setAssets(intent.assets)
            is ProxyIntent.SetEnabled -> {
                manager.setEnabled(projectId, intent.enabled)
                if (intent.enabled) manager.generate(assets.filter { manager.statusOf(it) is ProxyStatus.None || manager.statusOf(it) is ProxyStatus.OutOfDate })
                refresh(bumpResolve = true)
            }
            ProxyIntent.GenerateAll -> {
                manager.generate(assets)
                refresh()
            }
            is ProxyIntent.Generate -> {
                assets.firstOrNull { it.id == intent.assetId }?.let { manager.generate(listOf(it)) }
                refresh()
            }
            is ProxyIntent.Remove -> {
                assets.firstOrNull { it.id == intent.assetId }?.let(manager::remove)
                refresh(bumpResolve = true)
            }
            is ProxyIntent.SetBudget -> {
                manager.setBudget(intent.bytes)
                refresh()
            }
            is ProxyIntent.SetTarget -> {
                manager.setTargetShortSide(intent.shortSide)
                manager.protect(assets)
                refresh(bumpResolve = true)
            }
            ProxyIntent.RequestClear -> reduce { copy(confirmClear = true) }
            ProxyIntent.CancelClear -> reduce { copy(confirmClear = false) }
            ProxyIntent.ConfirmClear -> {
                val freed = manager.clearCache()
                reduce { copy(confirmClear = false, message = "Freed ${megabytes(freed)}") }
                refresh(bumpResolve = true)
            }
            ProxyIntent.DismissSuggestion -> {
                manager.prefs.dismissSuggestion(projectId)
                refresh()
            }
            ProxyIntent.AcceptSuggestion -> {
                val ids = state.value.suggestion?.assetIds.orEmpty().toSet()
                manager.setEnabled(projectId, true)
                manager.generate(assets.filter { it.id in ids })
                refresh(bumpResolve = true)
            }
            ProxyIntent.OpenSheet -> reduce { copy(sheetOpen = true) }
            ProxyIntent.CloseSheet -> reduce { copy(sheetOpen = false) }
            ProxyIntent.ReportStall -> {
                stalls++
                refresh()
            }
            ProxyIntent.SoftwareDecodeHeavy -> {
                stalls = maxOf(stalls, ProxySuggester.STALL_THRESHOLD)
                refresh()
            }
            is ProxyIntent.PreviewProxyFailed -> {
                assets.firstOrNull { it.id == intent.assetId }?.let(manager::markUnusable)
                reduce { copy(message = "A proxy could not be opened, so the original is used") }
                refresh(bumpResolve = true)
            }
            ProxyIntent.ClearMessage -> reduce { copy(message = null) }
        }
    }

    private fun setAssets(newAssets: List<MediaAssetDto>) {
        assets = newAssets
        manager.protect(newAssets)
        refresh()
        viewModelScope.launch {
            manager.probe(newAssets)
            manager.validate(newAssets)
            refresh()
        }
    }

    private fun refresh(bumpResolve: Boolean = false) {
        val videos = assets.filter { it.canHaveProxy() }
        val items = videos.map { ProxyItem(it.id, MissingMedia.nameOf(it), manager.statusOf(it)) }
        val enabled = manager.isEnabled(projectId)
        val suggestion = ProxySuggester.evaluate(
            assets = assets,
            infoOf = manager::infoOf,
            hasProxy = { manager.statusOf(it) !is ProxyStatus.None && manager.statusOf(it) !is ProxyStatus.OutOfDate && manager.statusOf(it) !is ProxyStatus.Failed },
            stalls = stalls,
            enabled = enabled,
            dismissed = manager.prefs.suggestionDismissed(projectId),
        )
        reduce {
            copy(
                enabled = enabled,
                items = items,
                usage = manager.usage(),
                targetShortSide = manager.prefs.targetShortSide,
                suggestion = suggestion,
                resolveVersion = if (bumpResolve) resolveVersion + 1 else resolveVersion,
            )
        }
    }

    override fun onCleared() {
        manager.flush()
    }
}

internal fun megabytes(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0)) else "%d MB".format(bytes / (1024 * 1024))
