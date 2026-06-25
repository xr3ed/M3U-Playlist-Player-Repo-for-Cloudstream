package com.cncverse.M3UPlaylistPlayer

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class EpgHelperTest {
    @Test
    fun testParseEpgXml_valid() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
                <channel id="TestChannel">
                    <display-name>Test Channel</display-name>
                </channel>
                <programme start="20230101000000 +0000" stop="20230101010000 +0000" channel="TestChannel">
                    <title>Test Title</title>
                    <desc>Test Description</desc>
                </programme>
            </tv>
        """.trimIndent()

        val inputStream = ByteArrayInputStream(xml.toByteArray())
        val (programMap, nameToIdMap) = EpgHelper.parseEpgXml(inputStream)

        assertEquals(2, nameToIdMap.size) // 1 from lowerId + 1 from display-name
        assertEquals("testchannel", nameToIdMap["test channel"])

        assertEquals(1, programMap.size)
        assertTrue(programMap.containsKey("testchannel"))
        val programs = programMap["testchannel"]!!
        assertEquals(1, programs.size)
        assertEquals("Test Title", programs[0].title)
        assertEquals("Test Description", programs[0].desc)
    }

    @Test
    fun testParseEpgXml_invalidXml() {
        // Reset lastError to ensure we don't read leftovers from previous tests
        EpgHelper.lastError = null

        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
                <channel id="TestChannel">
                    <display-name>Test Channel</display-name>
                <!-- Missing closing tag -->
            </tv>
        """.trimIndent()

        val inputStream = ByteArrayInputStream(xml.toByteArray())
        val (programMap, nameToIdMap) = EpgHelper.parseEpgXml(inputStream)

        // Error is caught, we should get whatever was successfully parsed or empty maps
        // Based on the catch block, it should return what it got until the error
        assertNotNull(programMap)
        assertNotNull(nameToIdMap)
        assertTrue(EpgHelper.lastError?.startsWith("Parser crash:") ?: false)
    }

    @Test
    fun testParseEpgXml_ioException() {
        // Reset lastError
        EpgHelper.lastError = null

        val errorInputStream = object : InputStream() {
            override fun read(): Int {
                throw IOException("Simulated network error reading stream")
            }
        }

        val (programMap, nameToIdMap) = EpgHelper.parseEpgXml(errorInputStream)

        assertNotNull(programMap)
        assertTrue(programMap.isEmpty())
        assertNotNull(nameToIdMap)
        assertTrue(nameToIdMap.isEmpty())

        val errorMsg = EpgHelper.lastError
        assertNotNull("lastError should not be null", errorMsg)
        assertTrue(errorMsg!!.startsWith("Parser crash:"))
        assertTrue(errorMsg.contains("Simulated network error reading stream"))
    }
}
