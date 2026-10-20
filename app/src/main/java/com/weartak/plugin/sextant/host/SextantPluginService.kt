package com.weartak.plugin.sextant.host

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.weartak.plugin.sdk.binder.PluginRulesAssetLoader
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleTransition

/**
 * Exported entry point for host binding. Action and permission: [com.weartak.plugin.sdk.WearTakPluginContract].
 */
class SextantPluginService : Service() {

    private val lifecycle = PluginLifecycleMachine()
    private val binder by lazy { SextantPluginBinder(lifecycle) }

    override fun onCreate() {
        super.onCreate()
        SextantPluginRuntime.setCepRulesJson(loadPluginRulesJson())
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind action=${intent?.action}")
        logTransition(lifecycle.dispatch(PluginLifecycleEvent.BIND_ATTACHED))
        return binder
    }

    override fun onDestroy() {
        SextantPluginRuntime.setCepRulesJson(null)
        logTransition(lifecycle.dispatch(PluginLifecycleEvent.RESET))
        super.onDestroy()
    }

    private fun loadPluginRulesJson(): String? = runCatching {
        PluginRulesAssetLoader.loadFromAssets(this, PLUGIN_RULES_ASSET)
    }.onFailure {
        Log.w(TAG, "failed to load $PLUGIN_RULES_ASSET", it)
    }.getOrNull()

    private fun logTransition(t: PluginLifecycleTransition) {
        when (t) {
            is PluginLifecycleTransition.Moved -> Log.d(TAG, "service lifecycle ${t.previous} -> ${t.state}")
            is PluginLifecycleTransition.Rejected -> Log.w(TAG, "service lifecycle rejected: ${t.reason}")
            is PluginLifecycleTransition.Unchanged -> Unit
        }
    }

    companion object {
        private const val TAG = "SextantSvc"
        private const val PLUGIN_RULES_ASSET = "plugin_rules.json"
    }
}
