package com.shootoff.compose.targets

import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetDefinitions
import com.shootoff.targets.model.TargetSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * An [AnimationClock] that runs every scheduled step on a small pool of real threads, with no
 * delay, so a test can race it against another real thread (e.g. one calling [RegionAnimations.reset]
 * in a loop). [awaitQuiescence] blocks until nothing more is scheduled.
 */
private class RacingClock : AnimationClock {
    private val pool = Executors.newFixedThreadPool(32)
    private val pending = AtomicInteger(0)

    override fun schedule(delayMillis: Long, step: () -> Unit) {
        pending.incrementAndGet()
        pool.submit {
            try {
                step()
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    fun awaitQuiescence() {
        while (pending.get() > 0) Thread.sleep(1)
        pool.shutdown()
    }
}

class TestRegionAnimations {
    private val clock = ManualClock()
    private val targets = SurfaceTargets(clock = clock)
    private val animations = targets.animations
    private lateinit var popper: RegionKey
    private lateinit var image: RegionImage

    @BeforeEach
    fun addThePepperPopper() {
        val target = targets.add(TargetDefinitions.load(Paths.get("targets/Pepper_Popper.target")), ResourceResolver.files())
        popper = RegionKey(target.id, 0)
        image = targets.image(target.id, 0)!!
        assertTrue(image.frames.size > 2)
    }

    private fun mask() = targets.set.get(popper.target).get().getImageMask(0).get()

    private val last get() = image.frames.size - 1

    @Test
    fun playingStepsThroughEveryFrameOnceOverOneCycle() {
        assertTrue(animations.isOnFirstFrame(popper))
        assertSame(image.masks[0], mask())

        animations.play(popper)
        clock.runNext()
        assertEquals(1, animations.frameOf(popper))
        assertSame(image.masks[1], mask())

        clock.runAll()

        assertEquals(last, animations.frameOf(popper))
        assertSame(image.masks[last], mask())
        assertFalse(animations.isOnFirstFrame(popper))
        assertEquals(image.cycleMillis / image.frames.size * image.frames.size, clock.now)
    }

    @Test
    fun aReversedAnimationPlaysBackToTheFirstFrame() {
        animations.play(popper)
        clock.runAll()

        animations.reverse(popper)
        // Reversed, the frame it starts from is the last
        assertTrue(animations.isOnFirstFrame(popper))

        animations.play(popper)
        clock.runAll()

        assertEquals(0, animations.frameOf(popper))
        assertSame(image.masks[0], mask())
    }

    @Test
    fun reversingWhilePlayingWaitsForThePlayToFinish() {
        animations.play(popper)
        clock.runNext()

        animations.reverse(popper)
        assertFalse(animations.isOnFirstFrame(popper))
        clock.runAll()

        assertEquals(last, animations.frameOf(popper))
        assertTrue(animations.isOnFirstFrame(popper))
    }

    @Test
    fun resetAfterGoesBackToTheFirstFrameOnceDone() {
        animations.play(popper, resetAfter = true)
        clock.runAll()

        assertEquals(0, animations.frameOf(popper))
        assertTrue(animations.isOnFirstFrame(popper))
    }

    @Test
    fun resetStopsAPlayAndShowsTheFirstFrame() {
        animations.play(popper)
        clock.runNext()
        clock.runNext()

        animations.resetAll()
        clock.runAll()

        assertEquals(0, animations.frameOf(popper))
        assertSame(image.masks[0], mask())
    }

    @Test
    fun unregisteringATargetStopsAnInFlightPlayAtOnce() {
        val stepMillis = maxOf(1L, image.cycleMillis / image.frames.size)

        animations.play(popper)
        clock.runNext() // frame 0 -> 1; not the last frame (image.frames.size > 2), so it reschedules

        targets.remove(popper.target) // unregisters: the in-flight step's generation is stale from here on

        clock.runAll() // the already-scheduled next step must see the mismatch and stop, not reschedule
        assertEquals(2 * stepMillis, clock.now)
    }

    @Test
    fun playingAndResettingConcurrentlyNeverLeavesTheMaskDisagreeingWithTheFrame() {
        // A TargetSet with many targets ahead of the ones under test: TargetSet.setImageMask does a
        // linear indexOf scan to find its target, so each mask push takes measurably long, unlocked,
        // giving a concurrent reset a real window to land between a step reading its new frame and the
        // mask that's meant to go with it.
        val paddedSet = TargetSet()
        val filler = TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 1.0, 1.0, "red", mapOf())))
        repeat(50_000) { paddedSet.add(filler) }

        val clock = RacingClock()
        val targets = SurfaceTargets(set = paddedSet, clock = clock)
        val keys = (0 until 4).map {
            val target = targets.add(TargetDefinitions.load(Paths.get("targets/Pepper_Popper.target")), ResourceResolver.files())
            RegionKey(target.id, 0)
        }
        val animations = targets.animations

        val stop = AtomicBoolean(false)
        val threads = keys.flatMap { key ->
            listOf(
                Thread { while (!stop.get()) animations.play(key) },
                Thread { while (!stop.get()) animations.reset(key) },
            )
        }
        threads.forEach { it.start() }
        Thread.sleep(300)
        stop.set(true)
        threads.forEach { it.join() }
        clock.awaitQuiescence()

        for (key in keys) {
            val image = targets.image(key.target, 0)!!
            val frame = animations.frameOf(key)
            val mask = targets.set.get(key.target).get().getImageMask(0).get()
            assertSame(image.masks[frame], mask, "region $key")
        }
    }
}
