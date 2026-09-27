package com.shootoff.compose.targets

/** An [AnimationClock] that runs steps only when told, in time order, so tests see every frame. */
class ManualClock : AnimationClock {
    private val pending = mutableListOf<Pair<Long, () -> Unit>>()
    var now = 0L
        private set

    @Synchronized
    override fun schedule(delayMillis: Long, step: () -> Unit) {
        pending += (now + delayMillis) to step
    }

    /** Runs the next step; false if there is none. */
    fun runNext(): Boolean {
        val next = synchronized(this) {
            val first = pending.minByOrNull { it.first } ?: return false
            pending.remove(first)
            first
        }
        now = next.first
        next.second()
        return true
    }

    fun runAll() {
        while (runNext()) Unit
    }
}
