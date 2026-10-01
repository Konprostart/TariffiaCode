package com.yugahashimoto.andcode.core.lifecycle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundReturnDetectorTest {
    /**
     * The app's own foreground observer is now the sole driver of restoring the runtime on launch,
     * so the very first transition into the foreground - the app's cold start - has to fire too,
     * unlike before when a separate one-shot initializer already covered it.
     */
    @Test
    fun `fires on the very first foreground emission`() {
        val detector = ForegroundReturnDetector()

        assertTrue(detector.onForegroundChanged(true))
    }

    @Test
    fun `does not fire on a background emission`() {
        val detector = ForegroundReturnDetector()

        assertFalse(detector.onForegroundChanged(false))
    }

    @Test
    fun `fires on a foreground emission that follows a background one`() {
        val detector = ForegroundReturnDetector()
        detector.onForegroundChanged(true)

        detector.onForegroundChanged(false)

        assertTrue(detector.onForegroundChanged(true))
    }

    /** Repeated returns from the background must each fire, not just the first one. */
    @Test
    fun `fires on every subsequent return from the background`() {
        val detector = ForegroundReturnDetector()
        detector.onForegroundChanged(true)

        detector.onForegroundChanged(false)
        assertTrue(detector.onForegroundChanged(true))

        detector.onForegroundChanged(false)
        assertTrue(detector.onForegroundChanged(true))
    }

    /**
     * A duplicate `true` with no intervening `false` - the bug this class exists to guard against -
     * must not be reported as a second entry into the foreground.
     */
    @Test
    fun `does not fire twice in a row for consecutive foreground emissions`() {
        val detector = ForegroundReturnDetector()

        assertTrue(detector.onForegroundChanged(true))
        assertFalse(detector.onForegroundChanged(true))
    }
}
