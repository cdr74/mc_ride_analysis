package dev.cdr74.ridelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingBufferTest {

    @Test
    fun `offer then drain preserves order and values`() {
        val ring = RingBuffer(16)
        ring.offer(Config.STREAM_ACCEL, 100L, floatArrayOf(1f, 2f, 3f, 0.1f, 0.2f, 0.3f), 3)
        ring.offer(Config.STREAM_GYRO, 200L, floatArrayOf(4f, 5f, 6f), 2)

        val seen = mutableListOf<Triple<Long, Int, List<Float>>>()
        val n = ring.drain(100) { s ->
            seen.add(Triple(s.tNs, s.stream, s.values.take(s.n)))
        }

        assertEquals(2, n)
        assertEquals(Triple(100L, Config.STREAM_ACCEL, listOf(1f, 2f, 3f, 0.1f, 0.2f, 0.3f)), seen[0])
        assertEquals(Triple(200L, Config.STREAM_GYRO, listOf(4f, 5f, 6f)), seen[1])
        assertEquals(0L, ring.size())
    }

    @Test
    fun `overflow drops new events and counts them per stream`() {
        val ring = RingBuffer(8)
        repeat(8) { assertTrue(ring.offer(Config.STREAM_ACCEL, it.toLong(), floatArrayOf(it.toFloat()), 0)) }
        assertFalse(ring.offer(Config.STREAM_ACCEL, 8L, floatArrayOf(8f), 0))
        assertFalse(ring.offer(Config.STREAM_GYRO, 9L, floatArrayOf(9f), 0))

        assertEquals(1L, ring.drops.get(Config.STREAM_ACCEL))
        assertEquals(1L, ring.drops.get(Config.STREAM_GYRO))
        assertEquals(2L, ring.totalDrops())

        // Buffered data survives the overflow untouched.
        val timestamps = mutableListOf<Long>()
        ring.drain(100) { timestamps.add(it.tNs) }
        assertEquals((0L..7L).toList(), timestamps)
    }

    @Test
    fun `drain respects max and continues where it left off`() {
        val ring = RingBuffer(16)
        repeat(10) { ring.offer(Config.STREAM_MAG, it.toLong(), floatArrayOf(0f), 0) }

        assertEquals(4, ring.drain(4) { })
        val rest = mutableListOf<Long>()
        assertEquals(6, ring.drain(100) { rest.add(it.tNs) })
        assertEquals((4L..9L).toList(), rest)
    }

    @Test
    fun `wraps around capacity repeatedly`() {
        val ring = RingBuffer(4)
        var next = 0L
        repeat(25) {
            ring.offer(Config.STREAM_ACCEL, it.toLong(), floatArrayOf(it.toFloat()), 0)
            ring.drain(100) { s ->
                assertEquals(next, s.tNs)
                assertEquals(next.toFloat(), s.values[0])
                next++
            }
        }
        assertEquals(25L, next)
        assertEquals(0L, ring.totalDrops())
    }

    @Test
    fun `rejects non power-of-two capacity`() {
        try {
            RingBuffer(100)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
