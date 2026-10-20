package com.weartak.plugin.sextant.host

import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakMapLayerControl
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.binder.PluginBinderErrorReporter
import com.weartak.plugin.sdk.binder.PluginHostNotifier
import com.weartak.plugin.sdk.binder.PluginToast
import com.weartak.plugin.sdk.model.AlertRequestParcel
import com.weartak.plugin.sdk.model.CepRuleEventParcel
import com.weartak.plugin.sdk.model.CepRuleOperation
import com.weartak.plugin.sdk.model.CotReadyEventParcel
import com.weartak.plugin.sdk.model.CotRequestParcel
import com.weartak.plugin.sdk.DeviceStatusContract
import com.weartak.plugin.sdk.model.HostSettingsParcel
import com.weartak.plugin.sdk.model.MainUiCardParcel
import com.weartak.plugin.sdk.model.MainUiCardTypes
import com.weartak.plugin.sdk.model.MapLayerCommandParcel
import com.weartak.plugin.sdk.MarkerContract
import com.weartak.plugin.sdk.model.MarkerParcel
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.PluginErrorCodes
import com.weartak.plugin.sdk.model.PluginErrorParcel
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SettingsApplyMode
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsFieldParcel
import com.weartak.plugin.sdk.model.SettingsFieldTypes
import com.weartak.plugin.sdk.model.SettingsOptionParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.model.SensorEventParcel
import com.weartak.plugin.sdk.model.ToastRequestParcel
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v2.model.CepRuleStreamEventParcel
import com.weartak.plugin.sdk.v2.model.V2HostResultParcel
import com.weartak.plugin.sdk.validation.CepRulesValidator
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.ControlPlanePayloadValidator
import com.weartak.plugin.sdk.validation.MainUiSchemaContractValidator
import com.weartak.plugin.sdk.validation.SettingsApplyValidator
import com.weartak.plugin.sdk.validation.SettingsSchemaContractValidator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

data class SextantPluginSnapshot(
    val lifecycleState: PluginLifecycleState = PluginLifecycleState.BOUND,
    val attached: Boolean = false,
    val hostPackageName: String? = null,
    val hostUid: Int? = null,
    val lastActionId: String = "idle",
    val lastActionMessage: String = "waiting for host",
    val lastError: String? = null,
    val cepRuns: Int = 0,
    val settingsRuns: Int = 0,
    val mapRuns: Int = 0,
    val alertRuns: Int = 0,
    val helloRuns: Int = 0,
    val settingsEnabled: Boolean = true,
    val reportMode: String = "summary",
)

data class SextantActionOutcome(
    val actionId: String,
    val success: Boolean,
    val message: String,
    val attached: Boolean,
)

fun interface SextantPluginSnapshotObserver {
    fun onSnapshotChanged(snapshot: SextantPluginSnapshot)
}

object SextantPluginRuntime {
    private const val TAG = "WearTakTemplateRuntime"

    private const val ARG_OPERATION = "operation"
    private const val OPERATION_ADD = "add"
    private const val OPERATION_REMOVE = "remove"

    const val ACTION_CEP_DEMO = "cep.run"
    const val ACTION_SETTINGS_DEMO = "settings.run"
    const val ACTION_MAP_DEMO = "map.run"
    const val ACTION_ALERT_DEMO = "alert.run"
    const val ACTION_HELLO_WORLD = "hello.world"
    const val ACTION_COT_DEMO = "cot.dispatch"
    const val ACTION_MARKER_PUBLISH = "marker.publish"
    const val ACTION_MARKER_UPDATE = "marker.update"
    const val ACTION_MARKER_CLEAR = "marker.clear"
    const val ACTION_DEVICE_STATUS = "device.status"
    const val ACTION_SENSOR_EVENT = "sensor.event"
    const val ACTION_SENSOR_BATCH = "sensor.batch"
    const val ACTION_COT_READY = "cot.ready"

    private const val DEMO_MARKER_ID = "weartak.template.demo.marker"

    private val lock = Any()
    private val observers = CopyOnWriteArrayList<SextantPluginSnapshotObserver>()

    @Volatile
    private var declaredPluginId: String = ""

    @Volatile
    private var displayName: String = "WearTAK Plugin"

    @Volatile
    private var hostCallback: IWearTakHostCallback? = null

    @Volatile
    private var pluginControl: IWearTakPluginControl? = null

    @Volatile
    private var pluginControlV2: IWearTakPluginHostControlV2? = null

    @Volatile
    private var hostPackageName: String? = null

    @Volatile
    private var hostUid: Int? = null

    @Volatile
    private var lifecycleState: PluginLifecycleState = PluginLifecycleState.BOUND

    private var hostSettings: HostSettingsParcel? = null
    @Volatile
    private var cepRulesJson: String? = null
    private var lastActionId: String = "idle"
    private var lastActionMessage: String = "waiting for host"
    private var lastError: String? = null
    private var cepRuns: Int = 0
    private var settingsRuns: Int = 0
    private var mapRuns: Int = 0
    private var alertRuns: Int = 0
    private var helloRuns: Int = 0
    private var cepSequence: Long = 0L
    private var sensorSequence: Long = 0L

    private val rawSettingsSchema = SettingsSchemaParcel(
        title = "WearTAK Settings Plugin",
        fields = listOf(
            SettingsFieldParcel(
                fieldId = "enable_stream",
                label = "Enable settings sync",
                fieldType = SettingsFieldTypes.TOGGLE,
                required = true,
                defaultValueLiteral = "true",
                requiresRestart = true,
            ),
            SettingsFieldParcel(
                fieldId = "report_mode",
                label = "Report mode",
                fieldType = SettingsFieldTypes.SELECT,
                options = listOf(
                    SettingsOptionParcel("summary", "Summary"),
                    SettingsOptionParcel("verbose", "Verbose"),
                    SettingsOptionParcel("adaptive", "Adaptive"),
                ),
                required = true,
                defaultValueLiteral = "summary",
                visibleWhenKey = "enable_stream",
                visibleWhenLiteral = "true",
            ),
            SettingsFieldParcel(
                fieldId = "operator_label",
                label = "Operator label",
                fieldType = SettingsFieldTypes.STRING,
                required = true,
                defaultValueLiteral = "Template Operator",
                visibleWhenKey = "enable_stream",
                visibleWhenLiteral = "true",
            ),
            SettingsFieldParcel(
                fieldId = "stream_interval_ms",
                label = "Stream interval (ms)",
                fieldType = SettingsFieldTypes.NUMBER,
                required = true,
                defaultValueLiteral = "5000",
                requiresRestart = true,
            ),
            SettingsFieldParcel(
                fieldId = "burst_window_secs",
                label = "Burst window (secs)",
                fieldType = SettingsFieldTypes.NUMBER,
                defaultValueLiteral = "15",
                visibleWhenKey = "enable_stream",
                visibleWhenLiteral = "true",
            ),
            SettingsFieldParcel(
                fieldId = "fallback_mode",
                label = "Fallback mode",
                fieldType = SettingsFieldTypes.TOGGLE,
                defaultValueLiteral = "false",
                visibleWhenHostKey = com.weartak.plugin.sdk.HostSettingsContract.KEY_COT_CONNECTED,
                visibleWhenHostLiteral = "false",
            ),
            SettingsFieldParcel(
                fieldId = "offline_note",
                label = "Offline note",
                fieldType = SettingsFieldTypes.STRING,
                defaultValueLiteral = "Waiting for host connectivity",
                visibleWhenHostKey = com.weartak.plugin.sdk.HostSettingsContract.KEY_COT_CONNECTED,
                visibleWhenHostLiteral = "false",
            ),
            SettingsFieldParcel(
                fieldId = "host_callsign",
                label = "Host callsign",
                fieldType = SettingsFieldTypes.STRING,
                hostSettingKey = com.weartak.plugin.sdk.HostSettingsContract.KEY_CALLSIGN,
                readOnly = true,
            ),
            SettingsFieldParcel(
                fieldId = "host_team",
                label = "Host team",
                fieldType = SettingsFieldTypes.STRING,
                hostSettingKey = com.weartak.plugin.sdk.HostSettingsContract.KEY_TEAM,
                readOnly = true,
            ),
            SettingsFieldParcel(
                fieldId = "host_role",
                label = "Host role",
                fieldType = SettingsFieldTypes.STRING,
                hostSettingKey = com.weartak.plugin.sdk.HostSettingsContract.KEY_ROLE,
                readOnly = true,
            ),
            SettingsFieldParcel(
                fieldId = "tak_connected",
                label = "TAK connected",
                fieldType = SettingsFieldTypes.TOGGLE,
                hostSettingKey = com.weartak.plugin.sdk.HostSettingsContract.KEY_TAK_CONNECTED,
                readOnly = true,
            ),
            SettingsFieldParcel(
                fieldId = "sitx_connected",
                label = "SITX connected",
                fieldType = SettingsFieldTypes.TOGGLE,
                hostSettingKey = com.weartak.plugin.sdk.HostSettingsContract.KEY_SITX_CONNECTED,
                readOnly = true,
            ),
            SettingsFieldParcel(
                fieldId = "cot_connected",
                label = "CoT connected",
                fieldType = SettingsFieldTypes.TOGGLE,
                hostSettingKey = com.weartak.plugin.sdk.HostSettingsContract.KEY_COT_CONNECTED,
                readOnly = true,
            ),
        ),
    )

    private val settingsSchema: SettingsSchemaParcel =
        when (val v = SettingsSchemaContractValidator.validate(rawSettingsSchema)) {
            is ContractValidationResult.Ok -> v.value
            is ContractValidationResult.Err -> SettingsSchemaContractValidator.sanitize(rawSettingsSchema).also {
                Log.w(TAG, "Settings schema validation: ${v.issues}")
            }
        }

    private val rawMainUi = PluginMainUiSchemaParcel(
        cards = listOf(
            MainUiCardParcel(
                cardType = MainUiCardTypes.STATUS,
                cardId = "status",
                title = "Template",
                subtitle = "Contract shell",
            ),
            MainUiCardParcel(
                cardType = MainUiCardTypes.KEY_VALUE_LIST,
                cardId = "kv",
                title = "Signals",
                subtitle = "Placeholder",
            ),
        ),
    )

    private val mainUiSchema: PluginMainUiSchemaParcel =
        when (val v = MainUiSchemaContractValidator.validate(rawMainUi, strictV1CardTypesOnly = true)) {
            is ContractValidationResult.Ok -> v.value
            is ContractValidationResult.Err -> MainUiSchemaContractValidator.sanitize(rawMainUi).also {
                Log.w(TAG, "Main UI schema validation: ${v.issues}")
            }
        }

    private var settingsValues: Bundle = defaultSettingsBundle(settingsSchema)

    fun configurePlugin(pluginId: String, name: String) {
        synchronized(lock) {
            if (declaredPluginId.isNotBlank() && declaredPluginId != pluginId) {
                Log.w(TAG, "Plugin id changed from $declaredPluginId to $pluginId")
            }
            declaredPluginId = pluginId
            displayName = name
        }
        notifyObservers()
    }

    fun addObserver(observer: SextantPluginSnapshotObserver): SextantPluginSnapshot {
        observers += observer
        return currentSnapshot()
    }

    fun removeObserver(observer: SextantPluginSnapshotObserver) {
        observers -= observer
    }

    fun currentSnapshot(): SextantPluginSnapshot = synchronized(lock) {
        SextantPluginSnapshot(
            lifecycleState = lifecycleState,
            attached = isAttachedLocked(),
            hostPackageName = hostPackageName,
            hostUid = hostUid,
            lastActionId = lastActionId,
            lastActionMessage = lastActionMessage,
            lastError = lastError,
            cepRuns = cepRuns,
            settingsRuns = settingsRuns,
            mapRuns = mapRuns,
            alertRuns = alertRuns,
            helloRuns = helloRuns,
            settingsEnabled = settingsValues.getBoolean("enable_stream"),
            reportMode = settingsValues.getString("report_mode") ?: "summary",
        )
    }

    fun attachHost(
        init: PluginInitParcel?,
        control: IWearTakPluginControl?,
        controlV2: IWearTakPluginHostControlV2?,
        callback: IWearTakHostCallback?,
    ) {
        synchronized(lock) {
            if (init != null) {
                hostPackageName = init.hostPackageName
                hostUid = init.hostUid
            }
            pluginControl = control
            pluginControlV2 = controlV2
            hostCallback = callback
            lastError = null
            if (lastActionMessage == "waiting for host") {
                lastActionMessage = "host attached"
            }
        }
        notifyObservers()
    }

    fun setHostCallback(callback: IWearTakHostCallback?) {
        synchronized(lock) {
            hostCallback = callback
        }
        notifyObservers()
    }

    fun detachHost() {
        synchronized(lock) {
            pluginControl = null
            pluginControlV2 = null
            hostCallback = null
            lastActionMessage = "host detached"
        }
        notifyObservers()
    }

    fun updateLifecycle(state: PluginLifecycleState) {
        synchronized(lock) {
            lifecycleState = state
        }
        notifyObservers()
    }

    fun updateHostSettings(host: HostSettingsParcel?) {
        synchronized(lock) {
            hostSettings = host
        }
        notifyObservers()
    }

    fun setCepRulesJson(rulesJson: String?) {
        cepRulesJson = rulesJson?.takeIf { it.isNotBlank() }
    }

    fun getPluginInfo(displayNameOverride: String, versionName: String, packageName: String) =
        com.weartak.plugin.sdk.model.PluginInfoParcel(
            pluginId = declaredPluginId.ifBlank { packageName },
            displayName = displayNameOverride,
            versionName = versionName,
            packageName = packageName,
            description = "Reference plugin template with settings, main UI, and control-plane examples.",
        )

    fun getCapabilities(hostedMainUi: Boolean, externalMainActivity: Boolean) =
        PluginCapabilitiesParcel(
            hostedSettingsUi = true,
            hostedMainUi = hostedMainUi,
            externalSettingsActivity = false,
            externalMainActivity = externalMainActivity,
            cotReadyEvents = true,
            mapLayers = true,
            deviceIntegration = false,
            sharedMemory = false,
        )

    fun getStatus(state: PluginLifecycleState): PluginStatusParcel = synchronized(lock) {
        val streamOn = settingsValues.getBoolean("enable_stream")
        val host = hostSettings
        val extras = Bundle().apply {
            putBoolean("feature.cep.ok", cepRuns > 0)
            putBoolean("feature.settings.ok", settingsRuns > 0)
            putBoolean("feature.map.ok", mapRuns > 0)
            putBoolean("feature.alert.ok", alertRuns > 0)
            putBoolean("feature.hello.ok", helloRuns > 0)
            putBoolean("attached", isAttachedLocked())
            putString("host.package", hostPackageName)
            putInt("host.uid", hostUid ?: -1)
            putString("lastAction", lastActionId)
        }
        PluginStatusParcel(
            state,
            message = buildString {
                append(displayName)
                append(" stream=")
                append(if (streamOn) "on" else "off")
                append(" action=")
                append(lastActionId)
                append(" host=")
                append(host?.callsign?.ifBlank { hostPackageName ?: "pending" } ?: (hostPackageName ?: "pending"))
            },
            detailCode = if (streamOn) 1 else 0,
            extras = extras,
        )
    }

    fun getSettingsSchema(): SettingsSchemaParcel = settingsSchema

    fun getSettingsValues(): Bundle = synchronized(lock) { Bundle(settingsValues) }

    fun onHostSettingsChanged(host: HostSettingsParcel?) {
        updateHostSettings(host)
    }

    fun applySettings(values: Bundle?, applyMode: Int): SettingsApplyResultParcel {
        val previous = synchronized(lock) { Bundle(settingsValues) }
        val result = SettingsApplyValidator.applySettingsWithResult(
            settingsSchema,
            previous,
            values,
            hostSettings,
            applyMode,
        )
        if (result.success) {
            synchronized(lock) {
                settingsValues = Bundle(result.values)
                settingsRuns += 1
                lastActionId = ACTION_SETTINGS_DEMO
                lastActionMessage = "settings updated"
                lastError = null
            }
            notifyHostSettingsChanged()
            notifyObservers()
        } else {
            PluginBinderErrorReporter.validationFailed(
                TAG,
                ACTION_SETTINGS_DEMO,
                "settings update rejected",
                hostCallback,
            )
        }
        return result
    }

    fun getMainUiSchema(): PluginMainUiSchemaParcel = mainUiSchema

    fun getMainUiState(): Bundle = synchronized(lock) {
        Bundle().apply {
            putString("kv_row1_label", "Stream")
            putString("kv_row1_value", if (settingsValues.getBoolean("enable_stream")) "on" else "off")
            putString("kv_row2_label", "Report")
            putString("kv_row2_value", settingsValues.getString("report_mode") ?: "summary")
            putString("kv_row3_label", "Actions")
            putString("kv_row3_value", "cep=$cepRuns settings=$settingsRuns map=$mapRuns alert=$alertRuns hello=$helloRuns")
            putString("kv_row4_label", "Host")
            putString("kv_row4_value", hostSettings?.callsign?.ifBlank { hostPackageName ?: "pending" } ?: (hostPackageName ?: "pending"))
            putString("kv_row5_label", "Network")
            putString(
                "kv_row5_value",
                when {
                    hostSettings == null -> "pending"
                    hostSettings?.cotConnected == true -> "CoT up"
                    hostSettings?.takConnected == true -> "TAK up"
                    hostSettings?.sitxConnected == true -> "SITX up"
                    else -> "offline"
                },
            )
            putString("last_action", lastActionId)
            putString("last_message", lastActionMessage)
        }
    }

    fun invokeMainUiAction(actionId: String?, args: Bundle?): SextantActionOutcome {
        val id = actionId ?: return fail("unknown", "missing action id", attached = isAttached())
        return when (id) {
            ACTION_CEP_DEMO -> runCepDemo(args)
            ACTION_SETTINGS_DEMO -> runSettingsDemo()
            ACTION_MAP_DEMO -> runMapDemo(args)
            ACTION_ALERT_DEMO -> runAlertDemo(args)
            ACTION_HELLO_WORLD -> runHelloWorldDemo()
            ACTION_COT_DEMO -> runCotDispatchDemo()
            ACTION_MARKER_PUBLISH -> runMarkerDemo(ACTION_MARKER_PUBLISH, markerOperation = "publish")
            ACTION_MARKER_UPDATE -> runMarkerDemo(ACTION_MARKER_UPDATE, markerOperation = "update")
            ACTION_MARKER_CLEAR -> runMarkerDemo(ACTION_MARKER_CLEAR, markerOperation = "clear")
            ACTION_DEVICE_STATUS -> runDeviceStatusDemo()
            ACTION_SENSOR_EVENT -> runSensorEventDemo()
            ACTION_SENSOR_BATCH -> runSensorBatchDemo()
            ACTION_COT_READY -> runCotReadyDemo(args)
            else -> fail(id, "unknown action id", attached = isAttached())
        }
    }

    fun runCotDispatchDemo(): SextantActionOutcome {
        val control = pluginControl ?: return notAttached(ACTION_COT_DEMO, "host control unavailable")
        return try {
            val timestamp = System.currentTimeMillis()
            val uid = "${declaredPluginId.ifBlank { "com.weartak.plugin.template" }}.demo.cot"
            val cotXml = "<event version=\"2.0\" uid=\"$uid\" type=\"b-m-p-s-p-l\" " +
                "how=\"m-g\" time=\"${cotTimestamp(timestamp)}\" start=\"${cotTimestamp(timestamp)}\" " +
                "stale=\"${cotTimestamp(timestamp + 60000L)}\"/>"
            control.requestCotDispatch(CotRequestParcel("cot_xml", Bundle().apply { putString("xml", cotXml) }))
            recordAction(ACTION_COT_DEMO, "direct CoT dispatched")
        } catch (ex: RemoteException) {
            reportActionError(ACTION_COT_DEMO, "CoT demo failed: ${ex.message ?: "remote error"}")
        } catch (ex: RuntimeException) {
            reportActionError(ACTION_COT_DEMO, "CoT demo failed: ${ex.message ?: "runtime error"}")
        }
    }

    fun runMarkerDemo(actionId: String, markerOperation: String): SextantActionOutcome {
        val control = pluginControl ?: return notAttached(actionId, "host control unavailable")
        return try {
            val marker = demoMarker(markerOperation)
            when (markerOperation) {
                "publish" -> control.requestMarkerPublish(marker)
                "update" -> control.requestMarkerUpdate(marker)
                "clear" -> control.requestMarkerClear(marker)
            }
            recordAction(actionId, "marker $markerOperation requested: ${marker.markerId}")
        } catch (ex: RemoteException) {
            reportActionError(actionId, "marker $markerOperation failed: ${ex.message ?: "remote error"}")
        } catch (ex: RuntimeException) {
            reportActionError(actionId, "marker $markerOperation failed: ${ex.message ?: "runtime error"}")
        }
    }

    fun runDeviceStatusDemo(): SextantActionOutcome {
        val control = pluginControl ?: return notAttached(ACTION_DEVICE_STATUS, "host control unavailable")
        return try {
            val status = control.deviceStatus
            val telemetry = status.telemetry
            val battery = telemetry.getInt(DeviceStatusContract.KEY_BATTERY_PERCENT, -1)
            val tak = telemetry.getBoolean(DeviceStatusContract.KEY_TAK_CONNECTED, false)
            val sitx = telemetry.getBoolean(DeviceStatusContract.KEY_SITX_CONNECTED, false)
            recordAction(ACTION_DEVICE_STATUS, "device ${if (status.online) "online" else "offline"}: ${status.summary}; TAK=$tak SITX=$sitx battery=${if (battery >= 0) "$battery%" else "n/a"}")
        } catch (ex: RemoteException) {
            reportActionError(ACTION_DEVICE_STATUS, "device status failed: ${ex.message ?: "remote error"}")
        } catch (ex: RuntimeException) {
            reportActionError(ACTION_DEVICE_STATUS, "device status failed: ${ex.message ?: "runtime error"}")
        }
    }

    fun runSensorEventDemo(): SextantActionOutcome {
        val callback = hostCallback ?: return notAttached(ACTION_SENSOR_EVENT, "host callback unavailable")
        return try {
            val sequence = nextSensorSequence()
            callback.onSensorEvent(SensorEventParcel("template.demo.sensor", System.currentTimeMillis(), sequence, Bundle().apply { putDouble("value", 42.0) }))
            recordAction(ACTION_SENSOR_EVENT, "sensor event sent, sequence=$sequence")
        } catch (ex: RemoteException) {
            reportActionError(ACTION_SENSOR_EVENT, "sensor event failed: ${ex.message ?: "remote error"}")
        } catch (ex: RuntimeException) {
            reportActionError(ACTION_SENSOR_EVENT, "sensor event failed: ${ex.message ?: "runtime error"}")
        }
    }

    fun runSensorBatchDemo(): SextantActionOutcome {
        val callback = hostCallback ?: return notAttached(ACTION_SENSOR_BATCH, "host callback unavailable")
        return try {
            val timestamp = System.currentTimeMillis()
            val events = (1..3).map { index -> SensorEventParcel("template.demo.sensor", timestamp + index, nextSensorSequence(), Bundle().apply { putDouble("value", index.toDouble()) }) }
            callback.onSensorBatchEvent(events)
            recordAction(ACTION_SENSOR_BATCH, "sensor batch sent: ${events.size} events")
        } catch (ex: RemoteException) {
            reportActionError(ACTION_SENSOR_BATCH, "sensor batch failed: ${ex.message ?: "remote error"}")
        } catch (ex: RuntimeException) {
            reportActionError(ACTION_SENSOR_BATCH, "sensor batch failed: ${ex.message ?: "runtime error"}")
        }
    }

    fun runCotReadyDemo(args: Bundle? = null): SextantActionOutcome {
        val callback = hostCallback ?: return notAttached(ACTION_COT_READY, "host callback unavailable")
        return try {
            val ready = args?.getBoolean("ready", true) ?: true
            callback.onCotReadyEvent(CotReadyEventParcel(ready, if (ready) "template CoT ready" else "template CoT unavailable"))
            recordAction(ACTION_COT_READY, "CoT readiness reported: $ready")
        } catch (ex: RemoteException) {
            reportActionError(ACTION_COT_READY, "CoT ready callback failed: ${ex.message ?: "remote error"}")
        } catch (ex: RuntimeException) {
            reportActionError(ACTION_COT_READY, "CoT ready callback failed: ${ex.message ?: "runtime error"}")
        }
    }

    fun runCepDemo(args: Bundle? = null): SextantActionOutcome {
        val plugin = declaredPluginId.ifBlank { "com.weartak.plugin.unknown" }
        val operation = demoOperation(args)
        val sensorKey = args?.getString("sensorKey")?.takeIf { it.isNotBlank() } ?: "demo.sensor"
        val ruleSetId = args?.getString("ruleSetId")?.takeIf { it.isNotBlank() } ?: "demo-rules"
        val control = pluginControl
        val controlV2 = pluginControlV2
        if (control == null && controlV2 == null) {
            return notAttached(ACTION_CEP_DEMO, "host control unavailable")
        }
        return try {
            val hostAccepted = when (operation) {
                OPERATION_REMOVE -> when {
                    controlV2 != null && (control == null || control.hostCapabilities.cepRuleStreaming) -> {
                        val event = CepRuleStreamEventParcel(
                            operation = CepRuleOperation.REMOVE,
                            pluginId = plugin,
                            sensorKey = sensorKey,
                            ruleSetId = ruleSetId,
                            sequence = nextCepSequence(),
                        )
                        controlV2.applyCepRuleEvent(event).accepted
                    }
                    control != null -> {
                        control.unregisterCepRules(plugin, sensorKey)
                        true
                    }
                    else -> false
                }
                else -> {
                    val rulesJson = resolveCepRulesJson(plugin, sensorKey, ruleSetId, args) ?: return fail(
                        ACTION_CEP_DEMO,
                        "cep rules asset missing",
                        attached = isAttached(),
                    )
                    when (val validated = CepRulesValidator.validateRegistration(plugin, sensorKey, rulesJson)) {
                        is ContractValidationResult.Err -> return fail(
                            ACTION_CEP_DEMO,
                            validated.issues.joinToString { it.message },
                            attached = isAttached(),
                        )
                        is ContractValidationResult.Ok -> Unit
                    }
                    when {
                        controlV2 != null && (control == null || control.hostCapabilities.cepRuleStreaming) -> {
                            val event = CepRuleStreamEventParcel(
                                operation = CepRuleOperation.ADD,
                                pluginId = plugin,
                                sensorKey = sensorKey,
                                ruleSetId = ruleSetId,
                                sequence = nextCepSequence(),
                                rulesJson = rulesJson,
                            )
                            controlV2.applyCepRuleEvent(event).accepted
                        }
                        control != null -> {
                            control.registerCepRules(plugin, sensorKey, rulesJson)
                            true
                        }
                        else -> false
                    }
                }
            }
            synchronized(lock) {
                cepRuns += 1
                lastActionId = ACTION_CEP_DEMO
                lastActionMessage = when {
                    hostAccepted && operation == OPERATION_REMOVE -> "cep removed for $sensorKey"
                    hostAccepted -> "cep registered for $sensorKey"
                    operation == OPERATION_REMOVE -> "cep remove rejected"
                    else -> "cep request rejected"
                }
                lastError = null
            }
            notifyHostAction()
            success(ACTION_CEP_DEMO, lastActionMessage)
        } catch (ex: RemoteException) {
            reportActionError(ACTION_CEP_DEMO, "cep demo failed: ${ex.message ?: "remote error"}")
        } catch (ex: RuntimeException) {
            reportActionError(ACTION_CEP_DEMO, "cep demo failed: ${ex.message ?: "runtime error"}")
        }
    }

    fun runSettingsDemo(): SextantActionOutcome {
        val current = getSettingsValues()
        val patch = Bundle().apply {
            putBoolean("enable_stream", !current.getBoolean("enable_stream"))
        }
        val result = applySettings(patch, SettingsApplyMode.PARTIAL_PATCH)
        if (!result.success) {
            return fail(ACTION_SETTINGS_DEMO, "settings update rejected", attached = isAttached())
        }
        synchronized(lock) {
            lastActionId = ACTION_SETTINGS_DEMO
            lastActionMessage = "settings toggled"
            lastError = null
        }
        notifyObservers()
        return success(ACTION_SETTINGS_DEMO, "settings updated")
    }

    fun runMapDemo(args: Bundle? = null): SextantActionOutcome {
        val control = pluginControl ?: return notAttached(ACTION_MAP_DEMO, "map control unavailable")
        val operation = demoOperation(args)
        return try {
            val mapControl = control.mapLayerControl
            val markerId = args?.getString("markerId")?.takeIf { it.isNotBlank() } ?: "demo.marker"
            val marker = MarkerParcel(
                markerId = markerId,
                title = args?.getString("title")?.takeIf { it.isNotBlank() } ?: "Map Demo",
                attributes = Bundle().apply {
                    putString("source", displayName)
                    putString("purpose", "map-demo")
                },
            )
            val mapAccepted = when (operation) {
                OPERATION_REMOVE -> {
                    if (mapControl == null || !mapControl.isMapLayerSupported()) {
                        return fail(ACTION_MAP_DEMO, "map remove unsupported by host", attached = true)
                    }
                    mapControl.applyLayerCommand(
                        MapLayerCommandParcel(
                            commandCode = 2,
                            layerId = markerId,
                            payload = Bundle().apply {
                                putString("title", marker.title)
                                putString("action", "remove")
                            },
                        ),
                    )
                    true
                }
                else -> when {
                    mapControl != null && mapControl.isMapLayerSupported() -> {
                        mapControl.applyLayerCommand(
                            MapLayerCommandParcel(
                                commandCode = 1,
                                layerId = markerId,
                                payload = Bundle().apply {
                                    putString("title", marker.title)
                                    putString("action", "show")
                                },
                            ),
                        )
                        true
                    }
                    else -> {
                        control.requestMarkerPublish(marker)
                        true
                    }
                }
            }
            synchronized(lock) {
                mapRuns += 1
                lastActionId = ACTION_MAP_DEMO
                lastActionMessage = when {
                    mapAccepted && operation == OPERATION_REMOVE -> "map demo removed $markerId"
                    mapAccepted -> "map demo published $markerId"
                    else -> "map demo sent"
                }
                lastError = null
            }
            notifyHostAction()
            success(ACTION_MAP_DEMO, lastActionMessage)
        } catch (ex: RemoteException) {
            reportActionError(ACTION_MAP_DEMO, "map demo failed: ${ex.message ?: "remote error"}")
        } catch (ex: RuntimeException) {
            reportActionError(ACTION_MAP_DEMO, "map demo failed: ${ex.message ?: "runtime error"}")
        }
    }

    fun runAlertDemo(args: Bundle? = null): SextantActionOutcome {
        val control = pluginControl ?: return notAttached(ACTION_ALERT_DEMO, "alert control unavailable")
        return try {
            val title = args?.getString("title")?.takeIf { it.isNotBlank() } ?: "WearTAK Alert"
            val message = args?.getString("message")?.takeIf { it.isNotBlank() } ?: "Alert demo from plugin"
            val request = AlertRequestParcel(
                title = title,
                message = message,
                severity = args?.getInt("severity") ?: 1,
                channelId = args?.getString("channelId"),
            )
            val result = when (val validated = ControlPlanePayloadValidator.validateAlert(request)) {
                is ContractValidationResult.Ok -> {
                    val caps = control.hostCapabilities
                    when {
                        caps.alerts -> {
                            control.requestAlert(validated.value)
                            "alert requested"
                        }
                        caps.toasts -> {
                            control.requestToast(
                                ToastRequestParcel(
                                    message = validated.value.message,
                                    duration = ToastRequestParcel.DURATION_SHORT,
                                    tag = "alert-fallback",
                                ),
                            )
                            "alert fallback toast"
                        }
                        else -> return fail(ACTION_ALERT_DEMO, "alert unsupported by host", attached = true)
                    }
                }
                is ContractValidationResult.Err -> return fail(ACTION_ALERT_DEMO, validated.issues.joinToString { it.message }, attached = true)
            }
            synchronized(lock) {
                alertRuns += 1
                lastActionId = ACTION_ALERT_DEMO
                lastActionMessage = result
                lastError = null
            }
            notifyHostAction()
            success(ACTION_ALERT_DEMO, result)
        } catch (ex: RemoteException) {
            reportActionError(ACTION_ALERT_DEMO, "alert demo failed: ${ex.message ?: "remote error"}")
        } catch (ex: RuntimeException) {
            reportActionError(ACTION_ALERT_DEMO, "alert demo failed: ${ex.message ?: "runtime error"}")
        }
    }

    fun runHelloWorldDemo(): SextantActionOutcome {
        val control = pluginControl
        return try {
            val message = "Hello world from $displayName"
            val sent = if (control != null) {
                PluginToast.show(TAG, control, message)
            } else {
                false
            }
            synchronized(lock) {
                helloRuns += 1
                lastActionId = ACTION_HELLO_WORLD
                lastActionMessage = if (sent) "hello toast sent" else "hello world recorded"
                lastError = null
            }
            notifyHostAction()
            success(ACTION_HELLO_WORLD, lastActionMessage)
        } catch (ex: RuntimeException) {
            reportActionError(ACTION_HELLO_WORLD, "hello demo failed: ${ex.message ?: "runtime error"}")
        }
    }

    fun resetForTests() {
        synchronized(lock) {
            hostCallback = null
            pluginControl = null
            pluginControlV2 = null
            hostPackageName = null
            hostUid = null
            lifecycleState = PluginLifecycleState.BOUND
            hostSettings = null
            lastActionId = "idle"
            lastActionMessage = "waiting for host"
            lastError = null
            cepRuns = 0
            settingsRuns = 0
            mapRuns = 0
            alertRuns = 0
            helloRuns = 0
            cepSequence = 0L
            cepRulesJson = null
            settingsValues = defaultSettingsBundle(settingsSchema)
        }
        notifyObservers()
    }

    private fun nextCepSequence(): Long = synchronized(lock) { ++cepSequence }

    private fun nextSensorSequence(): Long = synchronized(lock) { ++sensorSequence }

    private fun demoMarker(operation: String): MarkerParcel = MarkerParcel(
        markerId = DEMO_MARKER_ID,
        title = if (operation == "update") "Updated Template Marker" else "Template Marker",
        attributes = Bundle().apply {
            putString(MarkerContract.ATTR_MARKER_TYPE, "a-f-G")
            putString(MarkerContract.ATTR_REMARK, "WearTAK plugin template $operation demo")
            putDouble(MarkerContract.ATTR_LAT, -1.286389)
            putDouble(MarkerContract.ATTR_LON, 36.817223)
        },
    )

    private fun cotTimestamp(timestamp: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(timestamp))

    private fun recordAction(actionId: String, message: String): SextantActionOutcome {
        synchronized(lock) {
            lastActionId = actionId
            lastActionMessage = message
            lastError = null
        }
        notifyHostAction()
        return success(actionId, message)
    }

    private fun notifyHostAction() {
        val cb = hostCallback
        PluginHostNotifier.mainUiStateChanged(TAG, cb)
        PluginHostNotifier.statusChanged(TAG, cb, getStatus(lifecycleState))
        notifyObservers()
    }

    private fun notifyHostSettingsChanged() {
        val cb = hostCallback
        PluginHostNotifier.settingsValuesChanged(TAG, cb)
        PluginHostNotifier.mainUiStateChanged(TAG, cb)
        PluginHostNotifier.statusChanged(TAG, cb, getStatus(lifecycleState))
    }

    private fun notifyObservers() {
        val snapshot = currentSnapshot()
        observers.forEach { observer ->
            try {
                observer.onSnapshotChanged(snapshot)
            } catch (ex: RuntimeException) {
                Log.w(TAG, "observer failed", ex)
            }
        }
    }

    private fun isAttached() = synchronized(lock) { isAttachedLocked() }

    private fun isAttachedLocked(): Boolean = pluginControl != null || pluginControlV2 != null

    private fun success(actionId: String, message: String, attached: Boolean = isAttached()) =
        SextantActionOutcome(actionId = actionId, success = true, message = message, attached = attached)

    private fun notAttached(actionId: String, message: String) =
        SextantActionOutcome(actionId = actionId, success = false, message = "NOT_ATTACHED: $message", attached = false)

    private fun fail(actionId: String, message: String, attached: Boolean): SextantActionOutcome {
        synchronized(lock) {
            lastActionId = actionId
            lastActionMessage = message
            lastError = message
        }
        val cb = hostCallback
        PluginBinderErrorReporter.validationFailed(TAG, actionId, message, cb, PluginErrorCodes.ILLEGAL_STATE)
        notifyObservers()
        return SextantActionOutcome(actionId = actionId, success = false, message = message, attached = attached)
    }

    private fun reportActionError(actionId: String, message: String): SextantActionOutcome =
        fail(actionId, message, attached = isAttached())

    private fun demoOperation(args: Bundle?): String =
        args?.getString(ARG_OPERATION)?.trim()?.lowercase().takeUnless { it.isNullOrBlank() } ?: OPERATION_ADD

    private fun resolveCepRulesJson(
        pluginId: String,
        sensorKey: String,
        ruleSetId: String,
        args: Bundle?,
    ): String? {
        args?.getString("rulesJson")?.takeIf { it.isNotBlank() }?.let { return it }
        val template = cepRulesJson ?: return null
        return template
            .replace("__PLUGIN_ID__", pluginId)
            .replace("__SENSOR_KEY__", sensorKey)
            .replace("__RULE_SET_ID__", ruleSetId)
    }

    private fun defaultSettingsBundle(schema: SettingsSchemaParcel): Bundle =
        Bundle().apply {
            for (field in schema.fields) {
                when (field.fieldType.uppercase()) {
                    SettingsFieldTypes.TOGGLE -> putBoolean(field.fieldId, defaultBoolean(field))
                    SettingsFieldTypes.STRING -> putString(field.fieldId, defaultString(field))
                    SettingsFieldTypes.SELECT -> putString(field.fieldId, defaultString(field, field.options.firstOrNull()?.value.orEmpty()))
                    SettingsFieldTypes.NUMBER -> putDouble(field.fieldId, defaultNumber(field))
                }
            }
        }

    private fun defaultBoolean(field: SettingsFieldParcel): Boolean =
        when (field.defaultValueLiteral?.trim()?.lowercase()) {
            "true" -> true
            else -> false
        }

    private fun defaultString(field: SettingsFieldParcel, fallback: String = ""): String =
        field.defaultValueLiteral?.takeIf { it.isNotBlank() } ?: fallback

    private fun defaultNumber(field: SettingsFieldParcel): Double =
        field.defaultValueLiteral?.toDoubleOrNull() ?: 0.0
}
