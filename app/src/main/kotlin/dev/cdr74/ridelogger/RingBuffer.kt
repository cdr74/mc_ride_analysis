package dev.cdr74.ridelogger

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.min

/**
 * Single-producer / single-consumer ring buffer of pre-allocated sensor sample slots
 * (design.md §3.2). `offer` is called only from the sensor HandlerThread, `drain` only
 * from the writer coroutine. No allocation, no locks; overflow increments a per-stream
 * drop counter instead of blocking (data-integrity rule #1).
 */
class RingBuffer(private val capacity: Int = Config.RING_CAPACITY) {

    init {
        require(capacity > 0 && capacity and (capacity - 1) == 0) { "capacity must be a power of two" }
    }

    class Slot {
        var tNs = 0L
        var stream = 0
        var acc = 0
        var n = 0
        val values = FloatArray(6)
    }

    private val mask = (capacity - 1).toLong()
    private val slots = Array(capacity) { Slot() }
    private val head = AtomicLong(0) // written by producer only
    private val tail = AtomicLong(0) // written by consumer only

    /** Per-stream count of events lost to buffer overflow. */
    val drops = AtomicLongArray(Config.STREAM_COUNT)

    /** Producer side. Copies the event into a pre-allocated slot; never blocks. */
    fun offer(stream: Int, tNs: Long, values: FloatArray, acc: Int): Boolean {
        val h = head.get()
        if (h - tail.get() >= capacity) {
            drops.incrementAndGet(stream)
            return false
        }
        val s = slots[(h and mask).toInt()]
        s.tNs = tNs
        s.stream = stream
        s.acc = acc
        val n = min(values.size, 6)
        s.n = n
        for (i in 0 until n) s.values[i] = values[i]
        head.lazySet(h + 1) // release: publishes the slot contents to the consumer
        return true
    }

    /**
     * Consumer side. Invokes [consume] for up to [max] pending slots in FIFO order.
     * The slot is reused after the callback returns — do not retain it.
     */
    fun drain(max: Int, consume: (Slot) -> Unit): Int {
        var t = tail.get()
        val h = head.get() // acquire
        var count = 0
        while (t < h && count < max) {
            consume(slots[(t and mask).toInt()])
            t++
            count++
            tail.lazySet(t) // frees the slot for the producer as we go
        }
        return count
    }

    fun size(): Long = head.get() - tail.get()

    fun totalDrops(): Long {
        var sum = 0L
        for (i in 0 until Config.STREAM_COUNT) sum += drops.get(i)
        return sum
    }
}
