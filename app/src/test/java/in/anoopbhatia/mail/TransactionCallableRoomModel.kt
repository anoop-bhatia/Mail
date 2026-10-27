package `in`.anoopbhatia.mail

import java.util.concurrent.Callable

/** A small host model of Room's pinned synchronous transaction runner. */
class TransactionCallableRoomModel {
    @JvmField val trace: MutableList<String> = mutableListOf()
    @JvmField val committed: MutableList<String> = mutableListOf()
    @JvmField var beginFailure: Throwable? = null
    @JvmField var commitFailure: Throwable? = null
    @JvmField var endFailure: Throwable? = null
    @JvmField var swallowKnownEndFailure: Boolean = false

    private class Frame {
        val writes = mutableListOf<String>()
        var successful = false
        var poisoned = false
    }

    private val frames = ArrayDeque<Frame>()

    fun begin() {
        trace += "begin"
        frames.addLast(Frame())
        beginFailure?.let { throw it }
    }

    fun write(value: String) {
        check(frames.isNotEmpty()) { "write outside transaction" }
        trace += "write:$value"
        frames.last().writes += value
    }

    fun commit() {
        trace += "commit"
        commitFailure?.let { throw it }
        frames.last().successful = !frames.last().poisoned
    }

    fun end() {
        trace += "end"
        val frame = if (frames.isEmpty()) null else frames.removeLast()
        if (frame != null && frame.successful) {
            if (frames.isEmpty()) committed += frame.writes
            else frames.last().writes += frame.writes
        } else if (frame != null && frames.isNotEmpty()) {
            // Room rolls back a failed nested transaction and the outer frame is poisoned.
            frames.last().poisoned = true
        }
        val failure = endFailure
        if (failure != null) {
            if (swallowKnownEndFailure && isKnownEndFailure(failure)) {
                trace += "logged-end"
            } else {
                throw failure
            }
        }
    }

    /** Deliberately mirrors Room's begin; try { callback; commit } finally { end }. */
    fun <T> run(callback: Callable<T>): T {
        begin()
        try {
            val result = callback.call()
            commit()
            return result
        } finally {
            end()
        }
    }

    private fun isKnownEndFailure(failure: Throwable): Boolean {
        val message = failure.message ?: return false
        return message.contains("no current transaction") ||
            message.contains("no transaction is active")
    }
}
