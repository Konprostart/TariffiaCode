package com.konprostart.tariffiacode.feature.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class DevServerPreviewTest {
    @Test
    fun `detects a vite local url`() {
        assertEquals(
            listOf(5173),
            DevServerPreview.detectPorts("VITE ready\n  Local:   http://localhost:5173/\n"),
        )
    }

    @Test
    fun `detects loopback and 0000 hosts, with or without a scheme`() {
        assertEquals(listOf(3000), DevServerPreview.detectPorts("http://127.0.0.1:3000"))
        assertEquals(listOf(8080), DevServerPreview.detectPorts("listening on localhost:8080"))
        assertEquals(listOf(5174), DevServerPreview.detectPorts("0.0.0.0:5174"))
        assertEquals(listOf(4321), DevServerPreview.detectPorts("https://127.0.0.1:4321"))
    }

    @Test
    fun `dedupes and keeps first-seen order`() {
        val text = "http://localhost:3000 then http://127.0.0.1:5173 then again http://localhost:3000"
        assertEquals(listOf(3000, 5173), DevServerPreview.detectPorts(text))
    }

    @Test
    fun `excludes the opencode server port`() {
        assertEquals(emptyList<Int>(), DevServerPreview.detectPorts("open http://127.0.0.1:4096/global/health"))
    }

    @Test
    fun `ignores remote hosts and bare ports`() {
        assertEquals(
            emptyList<Int>(),
            DevServerPreview.detectPorts("https://example.com:8443/path and just the number 5173"),
        )
    }

    @Test
    fun `caps the number of ports`() {
        val text = (5000..5010).joinToString(" ") { "http://localhost:$it" }
        assertEquals(4, DevServerPreview.detectPorts(text).size)
    }
}
