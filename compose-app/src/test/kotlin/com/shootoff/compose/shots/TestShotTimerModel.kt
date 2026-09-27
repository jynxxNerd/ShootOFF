package com.shootoff.compose.shots

import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class TestShotTimerModel {
    private val timer = ShotTimerModel()

    private fun shot(millis: Long, color: ShotColor = ShotColor.RED) = ScaledShot(color, 1.0, 2.0, millis)

    @Test
    fun rowsReadLikeTheJavaFxShotTimer() {
        timer.appendShotRow(shot(1000), false, false)
        timer.appendShotRow(shot(2500, ShotColor.GREEN), true, false)

        val rows = timer.rows.value
        assertEquals(listOf("%.2f".format(1.0f), "%.2f".format(2.5f)), rows.map { it.row.time() })
        assertEquals(listOf("-", "%.2f".format(1.5f)), rows.map { it.row.split() })
        assertEquals(listOf("red", "green"), rows.map { it.row.laser() })
        assertTrue(rows[1].row.hadMalfunction())
    }

    @Test
    fun anExercisesRowBecomesTheLatestAndTakesItsValues() {
        timer.addColumn("Score")
        timer.appendShotRow(shot(1000), false, false)

        timer.addTimerRow(2500, "coral")
        assertTrue(timer.setColumnValue("Score", "0"))
        timer.appendShotRow(shot(3000), false, false)

        val rows = timer.rows.value
        assertEquals(listOf(null, "coral", null), rows.map { it.highlight })
        assertEquals(listOf(null, "0", null), rows.map { it.values["Score"] })
        assertEquals("red", rows[1].row.laser())
        assertEquals("%.2f".format(0.5f), rows[2].row.split())
    }

    @Test
    fun anEmptyTimerHasNoRowForAValueOrAHighlight() {
        assertFalse(timer.setColumnValue("Score", "10"))
        timer.styleLastRow("coral")

        assertEquals(emptyList<RowView>(), timer.rows.value)
    }

    @Test
    fun columnsComeAndGoAndClearingKeepsThem() {
        timer.addColumn("Length")
        timer.addColumn("Score")
        timer.appendShotRow(shot(1000), false, false)

        timer.clear()
        assertEquals(emptyList<RowView>(), timer.rows.value)
        assertEquals(listOf("Length", "Score"), timer.columns.value)

        timer.removeColumns(listOf("Length", "Score"))
        assertEquals(emptyList<String>(), timer.columns.value)
    }

    @Test
    fun theShotQueueAndTheExerciseWritingAtOnceKeepOneOrder() {
        val start = CountDownLatch(1)
        val shots = thread {
            start.await()
            for (i in 0 until 500) timer.appendShotRow(shot(i * 10L), false, false)
        }
        val exercise = thread {
            start.await()
            for (i in 0 until 500) timer.addTimerRow(i * 10L + 5, "coral")
        }
        start.countDown()
        shots.join()
        exercise.join()

        val rows = timer.rows.value
        assertEquals(1000, rows.size)
        // Every row's split is from the row right before it
        for (i in 1 until rows.size) {
            val split = rows[i].shot.timestamp / 1000f - rows[i - 1].shot.timestamp / 1000f
            assertEquals("%.2f".format(split), rows[i].row.split(), "row $i")
        }
    }
}
