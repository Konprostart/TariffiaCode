package com.konprostart.tariffiacode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Static verification that the RELEASE launcher icon is TariffiaCode branded (not the old AndCode
 * artwork). The release source set is `app/src/main/res`, so these assertions describe exactly what the
 * release APK ships.
 */
class IconBrandingTest {
    private val tariffiaNavy = 0x142435

    @Test
    fun `release launcher icon uses TariffiaCode branding`() {
        val root = repositoryRoot()

        val adaptive = root.resolve("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue("adaptive icon must use the mipmap foreground", adaptive.contains("@mipmap/ic_launcher_foreground"))

        val colors = root.resolve("app/src/main/res/values/tariffiacode_icon_colors.xml").readText()
        assertTrue("background must be the TariffiaCode navy", colors.contains("""ic_launcher_background">#142435<"""))

        val manifest = root.resolve("app/src/main/AndroidManifest.xml").readText()
        assertTrue("manifest must point at the launcher icon", manifest.contains("@mipmap/ic_launcher"))
    }

    @Test
    fun `release legacy launcher png is the TariffiaCode navy icon`() {
        val root = repositoryRoot()
        val png = ImageIO.read(root.resolve("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"))
        val dominant = dominantOpaqueColor(png)
        assertEquals("release launcher icon background must be TariffiaCode navy", tariffiaNavy, dominant)
    }

    @Test
    fun `old AndCode foreground vector is gone`() {
        val root = repositoryRoot()
        assertFalse(
            "the unused AndCode vector must not remain",
            root.resolve("app/src/main/res/drawable/ic_launcher_foreground.xml").exists(),
        )
    }

    private fun dominantOpaqueColor(image: java.awt.image.BufferedImage): Int {
        val counts = HashMap<Int, Int>()
        for (y in 0 until image.height step 4) {
            for (x in 0 until image.width step 4) {
                val argb = image.getRGB(x, y)
                if ((argb ushr 24) and 0xFF < 50) continue
                val rgb = argb and 0xFFFFFF
                counts[rgb] = (counts[rgb] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }?.key ?: error("launcher icon has no opaque pixels")
    }

    private fun repositoryRoot(): File {
        val workingDirectory = System.getProperty("user.dir") ?: error("Test working directory is unavailable")
        var directory = File(workingDirectory)
        while (true) {
            if (directory.resolve(".release-version").isFile) return directory
            directory = directory.parentFile ?: error("Could not locate repository root from $workingDirectory")
        }
    }
}
