/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.compose.drill

import com.shootoff.plugins.TextToSpeech
import com.shootoff.sound.SoundPlayer
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Optional
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineListener

/** Where a running exercise's sounds go: the speakers, or a recorder in tests. */
interface SoundOutput {
    /**
     * Plays [sound] and closes it, then runs [whenDone].
     *
     * @param name the name the exercise used, for logs and tests
     */
    fun play(name: String, sound: InputStream, whenDone: () -> Unit)

    fun say(text: String)

    companion object {
        val speakers = object : SoundOutput {
            override fun play(name: String, sound: InputStream, whenDone: () -> Unit) {
                if (SoundPlayer.isSilenced()) {
                    println(name)
                    sound.close()
                    whenDone()
                    return
                }

                val listener = LineListener { event ->
                    if (LineEvent.Type.STOP == event.type) {
                        event.line.close()
                        whenDone()
                    }
                }
                SoundPlayer.play(BufferedInputStream(sound), Optional.of(listener))
            }

            // Synthesis takes a while; keep it off the exercise thread
            override fun say(text: String) {
                Thread({ TextToSpeech.say(text) }, "Exercise speech").start()
            }
        }
    }
}
