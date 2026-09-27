package com.shootoff.compose.targets

import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

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
}
