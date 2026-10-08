package com.qtekfun.ultimatevideoeditor.proxy

import android.content.Context

/** Proxy settings, kept on the device only: the global ones and the per-project switch and dismissals. */
interface ProxyPrefs {
    /** Disk the proxy cache may use before the least recently used are deleted. */
    var budgetBytes: Long

    /** Short side of new proxies: 720 or 1080. */
    var targetShortSide: Int

    fun isEnabled(projectId: String): Boolean

    fun setEnabled(projectId: String, enabled: Boolean)

    fun suggestionDismissed(projectId: String): Boolean

    fun dismissSuggestion(projectId: String)

    companion object {
        const val DEFAULT_BUDGET_BYTES = 4L shl 30
        val budgetChoices = listOf(1L shl 30, 2L shl 30, 4L shl 30, 8L shl 30, 16L shl 30)
    }
}

class InMemoryProxyPrefs : ProxyPrefs {
    private val enabled = HashSet<String>()
    private val dismissed = HashSet<String>()
    override var budgetBytes: Long = ProxyPrefs.DEFAULT_BUDGET_BYTES
    override var targetShortSide: Int = ProxyTargets.SHORT_SIDE_720

    override fun isEnabled(projectId: String) = projectId in enabled

    override fun setEnabled(projectId: String, enabled: Boolean) {
        if (enabled) this.enabled += projectId else this.enabled -= projectId
    }

    override fun suggestionDismissed(projectId: String) = projectId in dismissed

    override fun dismissSuggestion(projectId: String) {
        dismissed += projectId
    }
}

class SharedPreferencesProxyPrefs(context: Context) : ProxyPrefs {
    private val prefs = context.getSharedPreferences("proxy_media", Context.MODE_PRIVATE)

    override var budgetBytes: Long
        get() = prefs.getLong(KEY_BUDGET, ProxyPrefs.DEFAULT_BUDGET_BYTES).coerceAtLeast(MIN_BUDGET)
        set(value) {
            prefs.edit().putLong(KEY_BUDGET, value.coerceAtLeast(MIN_BUDGET)).apply()
        }

    override var targetShortSide: Int
        get() = prefs.getInt(KEY_TARGET, ProxyTargets.SHORT_SIDE_720).takeIf { it in ProxyTargets.choices } ?: ProxyTargets.SHORT_SIDE_720
        set(value) {
            prefs.edit().putInt(KEY_TARGET, value).apply()
        }

    override fun isEnabled(projectId: String) = prefs.getBoolean("$KEY_ENABLED$projectId", false)

    override fun setEnabled(projectId: String, enabled: Boolean) {
        prefs.edit().putBoolean("$KEY_ENABLED$projectId", enabled).apply()
    }

    override fun suggestionDismissed(projectId: String) = prefs.getBoolean("$KEY_DISMISSED$projectId", false)

    override fun dismissSuggestion(projectId: String) {
        prefs.edit().putBoolean("$KEY_DISMISSED$projectId", true).apply()
    }

    private companion object {
        const val KEY_BUDGET = "budget_bytes"
        const val KEY_TARGET = "target_short_side"
        const val KEY_ENABLED = "enabled_"
        const val KEY_DISMISSED = "dismissed_"
        const val MIN_BUDGET = 256L shl 20
    }
}
