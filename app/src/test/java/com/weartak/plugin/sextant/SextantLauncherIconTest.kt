package com.weartak.plugin.sextant

import android.content.res.Configuration
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.VectorDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SextantLauncherIconTest {
    @Test fun launcherResolvesToBitmapAtEveryPackagedDensity() {
        val application = RuntimeEnvironment.getApplication()
        assertEquals(R.mipmap.sextant_launcher, application.applicationInfo.icon)
        for ((density, pixels) in listOf(160 to 48, 240 to 72, 320 to 96, 480 to 144, 640 to 192)) {
            val configuration = Configuration(application.resources.configuration).apply {
                densityDpi = density
            }
            val context = application.createConfigurationContext(configuration)
            val icon = context.getDrawable(application.applicationInfo.icon)
            assertTrue("Launcher must be a bitmap at density $density", icon is BitmapDrawable)
            val bitmap = (icon as BitmapDrawable).bitmap
            assertEquals(pixels, bitmap.width)
            assertEquals(pixels, bitmap.height)
            assertEquals(density, bitmap.density)
        }
    }

    @Test fun internalVectorRemainsAvailable() {
        val application = RuntimeEnvironment.getApplication()
        assertTrue(application.getDrawable(R.drawable.sextant_launcher) is VectorDrawable)
    }
}
