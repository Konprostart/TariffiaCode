package com.konprostart.tariffiacode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

/**
 * Static verification that the RELEASE launcher icon is TariffiaCode branded (not the old AndCode
 * artwork). The release source set is `app/src/main/res`, so these assertions describe exactly what the
 * release APK ships.
 *
 * The legacy PNG is decoded by [decodePng] instead of `javax.imageio`, which is not on the Android
 * unit-test classpath. It handles the 8-bit truecolor (RGB/RGBA), non-interlaced formats Android's
 * launcher icons use, so the dimension and dominant-colour checks are unchanged.
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
        val image = decodePng(root.resolve("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png").readBytes())
        assertEquals("release launcher icon must be square 192x192", 192, image.width)
        assertEquals("release launcher icon must be square 192x192", 192, image.height)
        val dominant = dominantOpaqueColor(image)
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

    /**
     * The foreground and monochrome layers must carry the TC mark, not the retired AC artwork.
     *
     * Both marks are monoline letterforms with a code glyph, so "the glyph exists" cannot tell
     * them apart. The reliable discriminator is mass distribution: the TC design has a tall solid
     * C bowl that over-fills the lower-right quadrant (measured ~0.39 of all mark pixels), whereas
     * the old AC artwork is close to evenly balanced across quadrants (~0.16-0.29, lower-right
     * ~0.29). The thresholds below sit well between the two so a revert fails the build.
     */
    @Test
    fun `launcher mark is the TC ligature and not the retired AC artwork`() {
        val root = repositoryRoot()
        val foreground = decodePng(root.resolve("app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png").readBytes())
        val monochrome = decodePng(root.resolve("app/src/main/res/mipmap-xxxhdpi/ic_launcher_monochrome.png").readBytes())

        for ((layer, image) in listOf("foreground" to foreground, "monochrome" to monochrome)) {
            val mask = opaqueMask(image)
            val total = mask.sum()
            assertTrue("$layer must contain mark pixels", total > 0)

            val lowerRight = quadrant(mask, image, 1, 1) / total.toDouble()
            val upperLeft = quadrant(mask, image, 0, 0) / total.toDouble()

            // TC: solid C bowl dominates the lower-right; the old AC art did not.
            assertTrue(
                "$layer lower-right mass must be the TC C bowl (was $lowerRight)",
                lowerRight > 0.33,
            )
            // TC: the C opening leaves the upper-left lighter than the old, more even AC art.
            assertTrue(
                "$layer upper-left mass must be the TC T crossbar (was $upperLeft)",
                upperLeft in 0.16..0.27,
            )
        }
    }

    /**
     * Returns a mask of the mark pixels.
     *
     * The foreground, monochrome and in-app layers are all drawn as the mark on a fully
     * transparent background, so any pixel that is not transparent is mark. [decodePng] reports
     * transparent pixels with alpha 0, which is what this relies on.
     */
    private fun opaqueMask(image: RgbImage): BooleanArray {
        val mask = BooleanArray(image.pixels.size)
        for (i in image.pixels.indices) {
            mask[i] = ((image.pixels[i] ushr 24) and 0xFF) >= 50
        }
        return mask
    }

    /** Counts mask pixels in the given quadrant: qx/qy are 0 (left/top) or 1 (right/bottom). */
    private fun quadrant(
        mask: BooleanArray,
        image: RgbImage,
        qx: Int,
        qy: Int,
    ): Int {
        val midX = image.width / 2
        val midY = image.height / 2
        var count = 0
        for (y in 0 until image.height) {
            val inY = if (qy == 0) y < midY else y >= midY
            if (!inY) continue
            for (x in 0 until image.width) {
                val inX = if (qx == 0) x < midX else x >= midX
                if (inX && mask[y * image.width + x]) count++
            }
        }
        return count
    }

    /**
     * The in-app drawer logo must show the same TC mark as the launcher, so the app and the
     * home screen cannot drift apart. Both are the mark on transparency, so comparing the share
     * of mark pixels in each quadrant is enough to prove they are the same silhouette.
     */
    @Test
    fun `in-app logo matches the launcher mark`() {
        val root = repositoryRoot()
        val logo = decodePng(root.resolve("app/src/main/res/drawable-nodpi/tariffiacode_logo.png").readBytes())
        val foreground = decodePng(root.resolve("app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png").readBytes())

        val logoMask = opaqueMask(logo)
        val foregroundMask = opaqueMask(foreground)
        val logoTotal = logoMask.sum().toDouble()
        val foregroundTotal = foregroundMask.sum().toDouble()

        for (qx in 0..1) {
            for (qy in 0..1) {
                val logoShare = quadrant(logoMask, logo, qx, qy) / logoTotal
                val foregroundShare = quadrant(foregroundMask, foreground, qx, qy) / foregroundTotal
                assertEquals(
                    "in-app logo must match the launcher mark in quadrant ($qx,$qy)",
                    foregroundShare,
                    logoShare,
                    0.05,
                )
            }
        }
    }

    private class RgbImage(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    )

    /** Decodes an 8-bit truecolor (RGB or RGBA), non-interlaced PNG into ARGB pixels. */
    private fun decodePng(bytes: ByteArray): RgbImage {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        require(bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(signature)) { "not a PNG file" }
        var offset = 8
        var width = 0
        var height = 0
        var channels = 0
        val idat = ByteArrayOutputStream()
        while (offset + 8 <= bytes.size) {
            val length = readInt(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            when (type) {
                "IHDR" -> {
                    width = readInt(bytes, dataStart)
                    height = readInt(bytes, dataStart + 4)
                    val bitDepth = bytes[dataStart + 8].toInt()
                    val colorType = bytes[dataStart + 9].toInt()
                    require(bitDepth == 8 && (colorType == 2 || colorType == 6)) {
                        "unsupported PNG (bitDepth=$bitDepth colorType=$colorType)"
                    }
                    channels = if (colorType == 2) 3 else 4
                }
                "IDAT" -> idat.write(bytes, dataStart, length)
                "IEND" -> break
            }
            offset = dataStart + length + 4
        }
        require(width > 0 && height > 0) { "PNG missing IHDR" }
        return unfilter(inflate(idat.toByteArray()), width, height, channels)
    }

    private fun readInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buffer = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val read = inflater.inflate(buffer)
            if (read == 0 && inflater.needsInput()) break
            out.write(buffer, 0, read)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun unfilter(
        raw: ByteArray,
        width: Int,
        height: Int,
        channels: Int,
    ): RgbImage {
        val stride = width * channels
        val pixels = IntArray(width * height)
        val current = ByteArray(stride)
        val previous = ByteArray(stride)
        var offset = 0
        for (y in 0 until height) {
            val filter = raw[offset++].toInt()
            System.arraycopy(raw, offset, current, 0, stride)
            offset += stride
            for (i in 0 until stride) {
                val left = if (i >= channels) current[i - channels].toInt() and 0xFF else 0
                val up = previous[i].toInt() and 0xFF
                val upLeft = if (i >= channels) previous[i - channels].toInt() and 0xFF else 0
                val value = current[i].toInt() and 0xFF
                val reconstructed =
                    when (filter) {
                        0 -> value
                        1 -> value + left
                        2 -> value + up
                        3 -> value + (left + up) / 2
                        4 -> value + paeth(left, up, upLeft)
                        else -> error("unknown PNG filter $filter")
                    }
                current[i] = reconstructed.toByte()
            }
            for (x in 0 until width) {
                val base = x * channels
                val red = current[base].toInt() and 0xFF
                val green = current[base + 1].toInt() and 0xFF
                val blue = current[base + 2].toInt() and 0xFF
                val alpha = if (channels == 4) current[base + 3].toInt() and 0xFF else 0xFF
                pixels[y * width + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
            System.arraycopy(current, 0, previous, 0, stride)
        }
        return RgbImage(width, height, pixels)
    }

    private fun paeth(
        a: Int,
        b: Int,
        c: Int,
    ): Int {
        val estimate = a + b - c
        val pa = kotlin.math.abs(estimate - a)
        val pb = kotlin.math.abs(estimate - b)
        val pc = kotlin.math.abs(estimate - c)
        return if (pa <= pb && pa <= pc) {
            a
        } else if (pb <= pc) {
            b
        } else {
            c
        }
    }

    private fun dominantOpaqueColor(image: RgbImage): Int {
        val counts = HashMap<Int, Int>()
        for (y in 0 until image.height step 4) {
            for (x in 0 until image.width step 4) {
                val argb = image.pixels[y * image.width + x]
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
