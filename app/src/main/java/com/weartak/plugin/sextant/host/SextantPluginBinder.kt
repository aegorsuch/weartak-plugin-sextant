package com.weartak.plugin.sextant.host

import android.os.Bundle
import android.util.Log
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.binder.PluginBinderErrorReporter
import com.weartak.plugin.sdk.binder.PluginBinderGuard
import com.weartak.plugin.sdk.binder.PluginHostNotifier
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleTransition
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.PluginErrorCodes
import com.weartak.plugin.sdk.model.PluginInfoParcel
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.model.PluginErrorParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.PluginMetadataValidator
import com.weartak.plugin.sdk.validation.SettingsApplyValidator
import com.weartak.plugin.sextant.BuildConfig

/**
 * Canonical plugin binder reference: contract-first, validation-aware, minimal behavior.
 * Host startup uses [initializePluginV2] only; [initializePlugin] is a legacy no-op stub.
 */
class SextantPluginBinder(
    private val lifecycle: PluginLifecycleMachine,
) : IWearTakPlugin.Stub() {

    private val pluginDisplayName = "Sextant"

    private var hostCallback: IWearTakHostCallback? = null
    private var control: IWearTakPluginControl? = null
    private var controlV2: IWearTakPluginHostControlV2? = null
    private val guard by lazy { PluginBinderGuard(TAG, lifecycle) }

    init {
        SextantPluginRuntime.configurePlugin(DECLARED_PLUGIN_ID, pluginDisplayName)
    }

    override fun getPluginInfo(): PluginInfoParcel {
        log("getPluginInfo")
        return SextantPluginRuntime.getPluginInfo(pluginDisplayName, BuildConfig.VERSION_NAME, BuildConfig.APPLICATION_ID)
    }

    override fun getCapabilities(): PluginCapabilitiesParcel {
        log("getCapabilities")
        // Sextant's real UI is SextantActivity; the hosted card schema has no controls.
        return SextantPluginRuntime.getCapabilities(hostedMainUi = false, externalMainActivity = true)
    }

    override fun getApiVersion(): Int {
        log("getApiVersion")
        return WearTakPluginContract.CONTRACT_API_VERSION
    }

    override fun getStatus(): PluginStatusParcel {
        log("getStatus")
        return guard.query(
            operation = "getStatus",
            fallback = PluginStatusParcel(lifecycle.snapshot(), message = "shutdown"),
        ) { SextantPluginRuntime.getStatus(lifecycle.snapshot()) }
    }

    override fun initializePlugin(init: PluginInitParcel?, pluginControl: IWearTakPluginControl?) {
        // Legacy compatibility only — host uses initializePluginV2; see IWearTakPlugin.aidl.
    }

    override fun initializePluginV2(
        init: PluginInitParcel?,
        pluginControl: IWearTakPluginControl?,
        pluginControlV2: IWearTakPluginHostControlV2?,
    ) {
        log("initializePluginV2 host=${init?.hostPackageName} uid=${init?.hostUid}")
        guard.command(
            operation = "initializePluginV2",
            onShutdown = { emitIllegalState("initializePluginV2") },
        ) {
            if (init != null) {
                when (
                    val v = PluginMetadataValidator.validateInitAgainstPlugin(
                        init,
                        declaredPluginId = DECLARED_PLUGIN_ID,
                    )
                ) {
                    is ContractValidationResult.Err -> Log.w(TAG, "init validation: ${v.issues}")
                    is ContractValidationResult.Ok -> Unit
                }
            }
            control = pluginControl
            controlV2 = pluginControlV2
            SextantPluginRuntime.attachHost(init, pluginControl, pluginControlV2, hostCallback)
            logTransition(lifecycle.dispatch(PluginLifecycleEvent.INITIALIZED))
            SextantPluginRuntime.updateLifecycle(lifecycle.snapshot())
        }
    }

    override fun startPlugin() {
        log("startPlugin")
        guard.command(
            operation = "startPlugin",
            onShutdown = { emitIllegalState("startPlugin") },
        ) {
            logTransition(lifecycle.dispatch(PluginLifecycleEvent.HOST_STARTED))
            SextantPluginRuntime.updateLifecycle(lifecycle.snapshot())
        }
    }

    override fun stopPlugin() {
        log("stopPlugin")
        guard.command(
            operation = "stopPlugin",
            onShutdown = { emitIllegalState("stopPlugin") },
        ) {
            logTransition(lifecycle.dispatch(PluginLifecycleEvent.HOST_STOPPED))
            SextantPluginRuntime.updateLifecycle(lifecycle.snapshot())
        }
    }

    override fun shutdownPlugin() {
        log("shutdownPlugin")
        guard.command(
            operation = "shutdownPlugin",
            onShutdown = { emitIllegalState("shutdownPlugin") },
        ) {
            logTransition(lifecycle.dispatch(PluginLifecycleEvent.HOST_SHUTDOWN))
            hostCallback = null
            control = null
            controlV2 = null
            SextantPluginRuntime.detachHost()
            SextantPluginRuntime.updateLifecycle(lifecycle.snapshot())
        }
    }

    override fun registerHostCallback(callback: IWearTakHostCallback?) {
        log("registerHostCallback")
        guard.command(
            operation = "registerHostCallback",
            onShutdown = { emitIllegalState("registerHostCallback") },
        ) {
            hostCallback = callback
            SextantPluginRuntime.setHostCallback(callback)
            logTransition(lifecycle.dispatch(PluginLifecycleEvent.HOST_CALLBACK_REGISTERED))
        }
    }

    override fun unregisterHostCallback(callback: IWearTakHostCallback?) {
        log("unregisterHostCallback")
        guard.command(
            operation = "unregisterHostCallback",
            onShutdown = { emitIllegalState("unregisterHostCallback") },
        ) {
            if (hostCallback === callback) {
                hostCallback = null
                SextantPluginRuntime.setHostCallback(null)
            }
        }
    }

    override fun getSettingsSchema(): SettingsSchemaParcel {
        log("getSettingsSchema")
        return SextantPluginRuntime.getSettingsSchema()
    }

    override fun getSettingsValues(): Bundle = SextantPluginRuntime.getSettingsValues()

    override fun onHostSettingsChanged(hostSettings: com.weartak.plugin.sdk.model.HostSettingsParcel?) {
        SextantPluginRuntime.onHostSettingsChanged(hostSettings)
    }

    override fun applySettingsWithResult(
        values: Bundle?,
        applyMode: Int,
    ): com.weartak.plugin.sdk.model.SettingsApplyResultParcel {
        val result = SextantPluginRuntime.applySettings(values, applyMode)
        if (result.success && result.restartRequired) {
            Log.i(TAG, "applySettingsWithResult: restart-required field changed; re-initializing lifecycle")
            performRestartLifecycleTransition()
            SextantPluginRuntime.updateLifecycle(lifecycle.snapshot())
        }
        return result
    }

    override fun applySettings(values: Bundle?) {
        log("applySettings keys=${values?.keySet()?.joinToString()}")
        guard.command(
            operation = "applySettings",
            onShutdown = { emitIllegalState("applySettings") },
        ) {
            val result = SextantPluginRuntime.applySettings(values, com.weartak.plugin.sdk.model.SettingsApplyMode.PARTIAL_PATCH)
            if (result.success && result.restartRequired) {
                Log.i(TAG, "applySettings: restart-required field changed; re-initializing lifecycle")
                performRestartLifecycleTransition()
                SextantPluginRuntime.updateLifecycle(lifecycle.snapshot())
            }
        }
    }

    override fun getMainUiSchema(): PluginMainUiSchemaParcel {
        log("getMainUiSchema")
        return SextantPluginRuntime.getMainUiSchema()
    }

    override fun getMainUiState(): Bundle = SextantPluginRuntime.getMainUiState()

    override fun invokeMainUiAction(actionId: String?, args: Bundle?) {
        log("invokeMainUiAction id=$actionId")
        guard.command(
            operation = "invokeMainUiAction",
            onShutdown = { emitIllegalState("invokeMainUiAction") },
        ) {
            val outcome = SextantPluginRuntime.invokeMainUiAction(actionId, args)
            Log.d(TAG, "invokeMainUiAction outcome=${outcome.actionId} success=${outcome.success} message=${outcome.message}")
        }
    }

    private fun performRestartLifecycleTransition() {
        val wasRunning = lifecycle.snapshot() == PluginLifecycleState.RUNNING
        if (wasRunning) {
            logTransition(lifecycle.dispatch(PluginLifecycleEvent.HOST_STOPPED))
        }
        logTransition(lifecycle.dispatch(PluginLifecycleEvent.INITIALIZED))
    }

    private fun logTransition(t: PluginLifecycleTransition) {
        when (t) {
            is PluginLifecycleTransition.Moved -> Log.d(TAG, "lifecycle ${t.previous} -> ${t.state}")
            is PluginLifecycleTransition.Rejected -> Log.w(TAG, "lifecycle rejected: ${t.reason}")
            is PluginLifecycleTransition.Unchanged -> Unit
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
    }

    private fun emitIllegalState(operation: String) {
        PluginBinderErrorReporter.report(
            tag = TAG,
            operation = operation,
            error = PluginErrorParcel(
                code = PluginErrorCodes.ILLEGAL_STATE,
                message = "$operation ignored after shutdown",
                fatal = false,
            ),
            callback = hostCallback,
        )
    }

    companion object {
        private const val TAG = "SextantPlugin"
        val DECLARED_PLUGIN_ID: String = BuildConfig.APPLICATION_ID
    }
}
