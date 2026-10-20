package com.weartak.plugin.sextant

object HostTarget {
    val hostPackageName: String?
        get() = com.weartak.plugin.sextant.host.SextantPluginRuntime.currentSnapshot().hostPackageName
}
