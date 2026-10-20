package com.weartak.plugin.sextant.host

import android.os.Bundle
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakMapLayerControl
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.AlertRequestParcel
import com.weartak.plugin.sdk.model.CotRequestParcel
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.model.DeviceStatusParcel
import com.weartak.plugin.sdk.model.HostCapabilitiesParcel
import com.weartak.plugin.sdk.model.HostSettingsParcel
import com.weartak.plugin.sdk.model.CotReadyEventParcel
import com.weartak.plugin.sdk.model.PluginErrorCodes
import com.weartak.plugin.sdk.model.PluginErrorParcel
import com.weartak.plugin.sdk.model.MapLayerCommandParcel
import com.weartak.plugin.sdk.model.MarkerParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SensorEventParcel
import com.weartak.plugin.sdk.model.SharedBufferParcel
import com.weartak.plugin.sdk.model.SettingsApplyMode
import com.weartak.plugin.sdk.model.ToastRequestParcel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Robolectric

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SextantPluginBinderTest {

    private var lifecycle: PluginLifecycleMachine? = null
    private var binder: SextantPluginBinder? = null

    private fun newBinderPair(): Pair<PluginLifecycleMachine, SextantPluginBinder> {
        val lc = PluginLifecycleMachine()
        lc.dispatch(PluginLifecycleEvent.BIND_ATTACHED)
        val b = SextantPluginBinder(lc)
        lifecycle = lc
        binder = b
        return lc to b
    }

    @After
    fun tearDown() {
        binder?.shutdownPlugin()
        SextantPluginRuntime.resetForTests()
        binder = null
        lifecycle = null
    }

    private fun driveToRunning(b: SextantPluginBinder, cb: RecordingHostCallback) {
        b.registerHostCallback(cb)
        b.initializePluginV2(null, null, null)
        b.startPlugin()
        assertEquals(PluginLifecycleState.RUNNING, b.getStatus().state)
    }

    @Test
    fun initializePlugin_legacyStub_doesNotAdvanceLifecycle() {
        val (_, b) = newBinderPair()
        b.initializePlugin(null, null)
        assertEquals(PluginLifecycleState.BOUND, b.getStatus().state)
    }

    @Test
    fun capabilities_exposeLauncherAndHostedMainUi() {
        val (_, b) = newBinderPair()
        val caps = b.getCapabilities()
        assertTrue(caps.hostedMainUi)
        assertTrue(caps.externalMainActivity)
    }

    @Test
    fun invokeMainUiAction_helloWorld_updatesLastAction() {
        val (_, b) = newBinderPair()
        val cb = RecordingHostCallback()
        driveToRunning(b, cb)
        b.invokeMainUiAction(SextantPluginRuntime.ACTION_HELLO_WORLD, Bundle())
        assertEquals(SextantPluginRuntime.ACTION_HELLO_WORLD, b.getMainUiState().getString("last_action"))
        assertTrue(b.getMainUiState().getString("last_message")?.contains("hello") == true)
    }

    @Test
    fun mapRun_withoutHost_returnsNotAttached() {
        SextantPluginRuntime.resetForTests()
        val outcome = SextantPluginRuntime.runMapDemo()
        assertFalse(outcome.success)
        assertFalse(outcome.attached)
        assertTrue(outcome.message.contains("NOT_ATTACHED"))
    }

    @Test
    fun cepRun_removeDoesNotNeedAssetWhenCleared() {
        val service = Robolectric.buildService(SextantPluginService::class.java).create().get()
        try {
            SextantPluginRuntime.resetForTests()
            val (_, b) = newBinderPair()
            val cb = RecordingHostCallback()
            val control = RecordingPluginControl(
                hostCapabilities = HostCapabilitiesParcel(cepRules = true),
            )
            b.registerHostCallback(cb)
            b.initializePluginV2(null, control, null)
            b.startPlugin()

            val removeOutcome = SextantPluginRuntime.runCepDemo(Bundle().apply { putString("operation", "remove") })
            assertTrue(removeOutcome.success)
            assertTrue(removeOutcome.message.contains("removed"))
            assertEquals(1, control.unregisterCalls.size)
            assertTrue(control.registerCalls.isEmpty())
        } finally {
            service.onDestroy()
            SextantPluginRuntime.resetForTests()
        }
    }

    @Test
    fun mapRun_addAndRemove_routesThroughLayerCommands() {
        val (_, b) = newBinderPair()
        val cb = RecordingHostCallback()
        val control = RecordingPluginControl(
            hostCapabilities = HostCapabilitiesParcel(mapLayers = true),
        )
        b.registerHostCallback(cb)
        b.initializePluginV2(null, control, null)
        b.startPlugin()

        val addOutcome = SextantPluginRuntime.runMapDemo()
        assertTrue(addOutcome.success)
        assertTrue(addOutcome.message.contains("published"))
        assertEquals(1, control.mapLayerControl.commands.size)
        assertEquals(1, control.mapLayerControl.commands.single().commandCode)

        val removeOutcome = SextantPluginRuntime.runMapDemo(Bundle().apply { putString("operation", "remove") })
        assertTrue(removeOutcome.success)
        assertTrue(removeOutcome.message.contains("removed"))
        assertEquals(2, control.mapLayerControl.commands.size)
        assertEquals(2, control.mapLayerControl.commands.last().commandCode)
    }

    @Test
    fun restartRequiredWhileRunning_transitionsToReadyAndUpdatesDerivedState() {
        val (_, b) = newBinderPair()
        val cb = RecordingHostCallback()
        driveToRunning(b, cb)
        cb.clearNotifications()
        b.applySettings(Bundle().apply { putBoolean("enable_stream", false) })
        assertEquals(PluginLifecycleState.READY, b.getStatus().state)
        assertTrue(b.getStatus().message.contains("stream=off"))
        assertEquals(0, b.getStatus().detailCode)
        assertEquals(listOf("settings", "main", "status"), cb.notificationOrder)
        assertFalse(b.getSettingsValues().getBoolean("enable_stream"))
        assertEquals("off", b.getMainUiState().getString("kv_row1_value"))
    }

    @Test
    fun invalidSettings_reportsValidationError_andDoesNotMutateToggles() {
        val (_, b) = newBinderPair()
        val cb = RecordingHostCallback()
        driveToRunning(b, cb)
        b.applySettings(Bundle().apply { putBoolean("bad", true) })
        assertEquals(1, cb.errors.size)
        assertEquals(PluginErrorCodes.VALIDATION_FAILED, cb.errors.single().code)
        assertTrue(b.getSettingsValues().getBoolean("enable_stream"))
    }

    @Test
    fun afterShutdown_applySettingsDoesNotMutateToggles() {
        val (_, b) = newBinderPair()
        val cb = RecordingHostCallback()
        driveToRunning(b, cb)
        b.applySettings(Bundle().apply { putBoolean("enable_stream", true) })
        b.shutdownPlugin()
        cb.clearAll()
        b.applySettings(Bundle().apply { putBoolean("enable_stream", false) })
        assertTrue(b.getSettingsValues().getBoolean("enable_stream"))
        assertTrue(cb.notificationOrder.isEmpty())
        assertTrue(cb.errors.isEmpty())
    }

    @Test
    fun schema_exposes_all_settings_configurables_and_host_snapshot_is_reflected() {
        val (_, b) = newBinderPair()
        b.onHostSettingsChanged(
            HostSettingsParcel(
                revision = 7,
                hostApiVersion = 1,
                hostPackageName = "com.host.example",
                snapshotTimeUtcMillis = 123456789L,
                callsign = "TEST-01",
                team = "Cyan",
                role = "Leader",
                reportingIntervalSecs = 30,
                desiredReportingIntervalSecs = 15,
                takConnected = true,
                sitxConnected = false,
                cotConnected = true,
            ),
        )

        val schema = b.getSettingsSchema()
        val fieldIds = schema.fields.map { it.fieldId }
        assertTrue(fieldIds.containsAll(
            listOf(
                "enable_stream",
                "report_mode",
                "operator_label",
                "stream_interval_ms",
                "burst_window_secs",
                "fallback_mode",
                "offline_note",
                "host_callsign",
                "host_team",
                "host_role",
                "tak_connected",
                "sitx_connected",
                "cot_connected",
            ),
        ))

        val status = b.getStatus()
        assertTrue(status.message.contains("TEST-01"))
        assertTrue(status.message.contains("host="))

        val mainUi = b.getMainUiState()
        assertEquals("TEST-01", mainUi.getString("kv_row4_value"))
        assertEquals("CoT up", mainUi.getString("kv_row5_value"))

        val applyResult = b.applySettingsWithResult(
            Bundle().apply {
                putBoolean("enable_stream", false)
                putString("report_mode", "summary")
                putString("operator_label", "Alpha")
                putDouble("stream_interval_ms", 2500.0)
                putDouble("burst_window_secs", 10.0)
            },
            SettingsApplyMode.PARTIAL_PATCH,
        )
        assertTrue(applyResult.success)
        assertNotNull(applyResult.values)
    }

    @Test
    fun afterShutdown_startPlugin_lifecycleRemainsShutdown() {
        val (_, b) = newBinderPair()
        val cb = RecordingHostCallback()
        driveToRunning(b, cb)
        b.shutdownPlugin()
        b.startPlugin()
        assertEquals(PluginLifecycleState.SHUTDOWN, b.getStatus().state)
    }

    private class RecordingHostCallback : IWearTakHostCallback.Stub() {
        val notificationOrder = mutableListOf<String>()
        val errors = mutableListOf<PluginErrorParcel>()

        fun clearNotifications() {
            notificationOrder.clear()
        }

        fun clearAll() {
            notificationOrder.clear()
            errors.clear()
        }

        override fun onPluginSettingsValuesChanged() {
            notificationOrder += "settings"
        }

        override fun onPluginMainUiStateChanged() {
            notificationOrder += "main"
        }

        override fun onPluginStatusChanged(status: PluginStatusParcel?) {
            notificationOrder += "status"
        }

        override fun onPluginError(error: PluginErrorParcel) {
            errors += error
        }

        override fun onSensorEvent(event: SensorEventParcel?) = Unit
        override fun onSensorBatchEvent(events: MutableList<SensorEventParcel>?) = Unit
        override fun onCotReadyEvent(event: CotReadyEventParcel?) = Unit
        override fun onPluginSettingsSchemaChanged() = Unit
        override fun onPluginMainUiSchemaChanged() = Unit
        override fun onSharedBufferAvailable(buffer: SharedBufferParcel?) = Unit
    }

    private class RecordingPluginControl(
        private val hostCapabilities: HostCapabilitiesParcel = HostCapabilitiesParcel(),
    ) : IWearTakPluginControl.Stub() {

        data class RegisterCall(val pluginId: String, val sensorKey: String, val rulesJson: String)

        val registerCalls = mutableListOf<RegisterCall>()
        val unregisterCalls = mutableListOf<Pair<String, String>>()
        val mapLayerControl = RecordingMapLayerControl()

        override fun requestCotDispatch(request: CotRequestParcel?) = Unit

        override fun requestAlert(request: AlertRequestParcel?) = Unit

        override fun requestToast(request: ToastRequestParcel?) = Unit

        override fun requestMarkerPublish(marker: MarkerParcel?) = Unit

        override fun requestMarkerUpdate(marker: MarkerParcel?) = Unit

        override fun requestMarkerClear(marker: MarkerParcel?) = Unit

        override fun getDeviceStatus(): DeviceStatusParcel = DeviceStatusParcel(online = false)

        override fun registerCepRules(pluginId: String?, sensorKey: String?, rulesJson: String?) {
            if (pluginId != null && sensorKey != null && rulesJson != null) {
                registerCalls += RegisterCall(pluginId, sensorKey, rulesJson)
            }
        }

        override fun unregisterCepRules(pluginId: String?, sensorKey: String?) {
            if (pluginId != null && sensorKey != null) {
                unregisterCalls += pluginId to sensorKey
            }
        }

        override fun applyCepRuleEvent(event: com.weartak.plugin.sdk.model.CepRuleEventParcel?) = Unit

        override fun isCotConnected(): Boolean = false

        override fun getHostCapabilities(): HostCapabilitiesParcel = hostCapabilities

        override fun getMapLayerControl(): IWearTakMapLayerControl? = mapLayerControl
    }

    private class RecordingMapLayerControl : IWearTakMapLayerControl.Stub() {
        val commands = mutableListOf<MapLayerCommandParcel>()

        override fun isMapLayerSupported(): Boolean = true

        override fun applyLayerCommand(command: MapLayerCommandParcel?): Int {
            if (command != null) {
                commands += command
            }
            return 0
        }
    }
}
