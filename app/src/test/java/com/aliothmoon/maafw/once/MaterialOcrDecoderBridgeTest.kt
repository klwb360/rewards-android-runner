package com.aliothmoon.maafw.once

import org.junit.Assert.*
import org.junit.Test
import java.util.function.Consumer

class MaterialOcrDecoderBridgeTest {
    private val binding = FrameStructureEvidence.Binding(Any(), Any(), Any(), Any())
    private fun run(raw: ByteArray, consumer: Consumer<List<FrameStructureEvidence.Line>>) =
        OcrDetailDecoder.decode(raw, binding, 1104, 402, consumer)

    @Test fun nativeBoxesAndLowScoresArriveUnchanged() {
        var rows: List<FrameStructureEvidence.Line>? = null
        val result = run("""{"all":[{"text":"Day 4","score":0.6,"box":[44,50,66,32]}]}""".toByteArray()) { rows = it }
        assertTrue(result.transportAccepted())
        val row = rows!!.single()
        assertEquals("Day 4", row.text())
        assertEquals(0.6, row.score(), 0.0)
        assertEquals(44, row.x()); assertEquals(66, row.width())
    }
    @Test fun malformedTransportsNeverReachBridge() {
        for (raw in listOf(
            """{"all":[],"all":[]}""".toByteArray(),
            """{"all":[{"text":"x","score":1,"box":[-1,0,1,1]}]}""".toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28), ByteArray(65537),
        )) {
            var called = false
            val result = run(raw) { called = true }
            assertFalse(result.transportAccepted()); assertFalse(called)
        }
    }
    @Test fun outOfBoundsAndInvalidConfidenceNeverReachBridge() {
        for (row in listOf(
            """{"text":"x","score":0.99,"box":[1100,0,9,9]}""",
            """{"text":"x","score":1.1,"box":[1,1,9,9]}""",
            """{"text":"x","score":0.99,"box":[1,1,0,9]}""",
        )) {
            var called = false
            val result = run("{\"all\":[$row]}".toByteArray()) { called = true }
            assertFalse(result.quality!!.shapeValid()); assertFalse(called)
        }
    }
    @Test fun emptyBatchIsNotInvented() {
        var rows: List<FrameStructureEvidence.Line>? = null
        val result = run("{\"all\":[]}".toByteArray()) { rows = it }
        assertEquals(OcrDetailDecoder.Parse.EMPTY, result.parse)
        assertTrue(rows!!.isEmpty())
    }
    @Test fun defaultDecoderRemainsScalarOnly() {
        val result = OcrDetailDecoder.decode("{\"all\":[]}".toByteArray(), binding, 1104, 402)
        assertEquals(OcrDetailDecoder.Parse.EMPTY, result.parse)
        assertFalse(result.structure.supported())
    }
}
