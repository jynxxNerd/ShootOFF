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

package com.shootoff.compose.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.arena.ArenaLayoutView
import com.shootoff.compose.feed.BannerView
import com.shootoff.compose.feed.CameraFeedView
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.targets.AnimationClock
import com.shootoff.compose.targets.EditingOverlay
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.compose.targets.TargetLayer
import com.shootoff.compose.theme.Range
import com.shootoff.geom.Size
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO

/** How long Clear's Undo is offered */
const val UNDO_MILLIS = 8000L

/** What the camera surface says when it has nothing to show */
private const val NO_CAMERA_HINT = "No camera — choose one in Setup."

/** The toolbar's panels, beside the surface */
private enum class Panel { ADD_TARGET, BACKGROUND, LOAD_COURSE, SAVE_COURSE }

/**
 * The Targets screen (spec §4): the arena, or the camera feed, with the shooter's targets to add, move,
 * resize and remove, and on the arena its background and courses. Every change shows on the projector (or
 * the feed) as it is made.
 */
@Composable
fun TargetsScreen(app: AppState, modifier: Modifier = Modifier) {
    val model = app.targetsModel
    val surface by model.surface.collectAsState()
    val arena by app.arena.collectAsState()
    val covered = arena?.covered?.collectAsState()?.value == true
    val message by model.message.collectAsState()
    val undo by model.undo.collectAsState()
    val frame by app.feed.frame.collectAsState()
    var panel by remember { mutableStateOf<Panel?>(null) }
    val colors = Range.colors

    Column(modifier.fillMaxSize().padding(16.dp).testTag("targets-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Targets", fontSize = 22.sp, color = colors.text)
            Spacer(Modifier.width(16.dp))
            for (choice in EditedSurface.entries) {
                FilterChip(
                    selected = surface == choice,
                    onClick = {
                        model.show(choice)
                        panel = null
                    },
                    label = { Text(choice.label) },
                    modifier = Modifier.testTag("surface-${choice.name}"),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { panel = Panel.ADD_TARGET }, modifier = Modifier.testTag("add-target")) { Text("Add target") }
            // Backgrounds and courses are the arena's (spec §4)
            if (surface == EditedSurface.ARENA) {
                FilledTonalButton(onClick = { panel = Panel.BACKGROUND }, modifier = Modifier.testTag("background")) { Text("Background…") }
                FilledTonalButton(onClick = { panel = Panel.LOAD_COURSE }, modifier = Modifier.testTag("load-course")) { Text("Load course…") }
                FilledTonalButton(onClick = { panel = Panel.SAVE_COURSE }, modifier = Modifier.testTag("save-course")) { Text("Save course…") }
            }
            OutlinedButton(onClick = model::clear, modifier = Modifier.testTag("clear")) { Text("Clear") }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = colors.feedEdge, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxSize()) {
                    when (surface) {
                        EditedSurface.ARENA -> ArenaLayoutView(model.layout, arena, Modifier.fillMaxSize().testTag("targets-arena")) { transform ->
                            EditingOverlay(model.layout.targets, model.arenaEditor, transform)
                        }
                        EditedSurface.CAMERA -> {
                            CameraFeedView(app.feed, Modifier.fillMaxSize()) { transform ->
                                TargetLayer(model.feedTargets, transform)
                                EditingOverlay(model.feedTargets, model.cameraEditor, transform)
                            }
                            if (frame == null) {
                                Text(NO_CAMERA_HINT, color = colors.muted, modifier = Modifier.align(Alignment.Center).testTag("no-camera"))
                            }
                        }
                    }
                    // Over the surface, so that they don't change its size while they show
                    Column(Modifier.align(Alignment.TopCenter).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (covered && surface == EditedSurface.ARENA) {
                            Text(CALIBRATING_NOTE, color = colors.warning, modifier = Modifier.testTag("calibrating-note"))
                        }
                        message?.let { BannerView(it, onDismiss = model::dismissMessage) }
                    }
                    undo?.let { cleared ->
                        // Offered for a while, then gone
                        LaunchedEffect(cleared) {
                            delay(UNDO_MILLIS)
                            model.dropUndo(cleared)
                        }
                        Snackbar(
                            action = { TextButton(onClick = model::undoClear, modifier = Modifier.testTag("undo")) { Text("Undo") } },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).testTag("cleared"),
                        ) { Text("Cleared the ${cleared.surface.label.lowercase()}") }
                    }
                }
            }
            panel?.let { shown ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.card,
                    border = BorderStroke(1.dp, colors.cardBorder),
                    modifier = Modifier.width(300.dp).fillMaxHeight().testTag("panel"),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(panelTitle(shown), color = colors.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { panel = null }, modifier = Modifier.testTag("close-panel")) { Text("Close") }
                        }
                        val close = { panel = null }
                        when (shown) {
                            Panel.ADD_TARGET -> AddTargetPanel(model, close)
                            Panel.BACKGROUND -> BackgroundPanel(model, close)
                            Panel.LOAD_COURSE -> LoadCoursePanel(model, close)
                            Panel.SAVE_COURSE -> SaveCoursePanel(model, close)
                        }
                    }
                }
            }
        }
    }
}

private fun panelTitle(panel: Panel) = when (panel) {
    Panel.ADD_TARGET -> "Add target"
    Panel.BACKGROUND -> "Background"
    Panel.LOAD_COURSE -> "Load course"
    Panel.SAVE_COURSE -> "Save course"
}

@Composable
private fun AddTargetPanel(model: TargetsModel, close: () -> Unit) {
    val choices = remember { model.files.targetChoices() }
    val colors = Range.colors
    if (choices.isEmpty()) Text("No targets in ${model.files.targets.path}", color = colors.muted)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("target-list")) {
        items(choices, key = { it.file.path }) { choice ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().clickable {
                    model.addTarget(choice)
                    close()
                }.padding(4.dp).testTag("target-${choice.name}"),
            ) {
                TargetThumbnail(choice.file, Modifier.size(48.dp))
                Text(choice.name, color = colors.text)
            }
        }
    }
}

/** A target drawn small, read off the UI thread; still, and blank if it can't be read */
@Composable
private fun TargetThumbnail(file: File, modifier: Modifier = Modifier) {
    val thumbnail by produceState<SurfaceTargets?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // A clock that never ticks: thumbnails don't animate
                SurfaceTargets(clock = AnimationClock { _, _ -> }).also { targets ->
                    val target = targets.add(TargetDefinitions.load(file.toPath()), ResourceResolver.files())
                    val bounds = target.bounds
                    targets.set.move(target.id, -bounds.minX, -bounds.minY)
                }
            }.getOrNull()
        }
    }
    BoxWithConstraints(modifier) {
        val targets = thumbnail ?: return@BoxWithConstraints
        val bounds = targets.set.targets.firstOrNull()?.bounds ?: return@BoxWithConstraints
        TargetLayer(targets, SurfaceTransform.fit(Size(bounds.width, bounds.height), constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
    }
}

@Composable
private fun BackgroundPanel(model: TargetsModel, close: () -> Unit) {
    val colors = Range.colors
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            PanelRow("None", "background-none") {
                model.useBackground(null)
                close()
            }
        }
        item {
            PanelRow("An image file…", "background-file") {
                model.pickBackground()
                close()
            }
        }
        items(BUNDLED_BACKGROUNDS, key = { it.resource }) { background ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().clickable {
                    model.useBackground(background)
                    close()
                }.padding(4.dp).testTag("background-${background.name}"),
            ) {
                val image by produceState<ImageBitmap?>(null, background) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { BackgroundThumbnails::class.java.getResourceAsStream(background.resource)?.use { ImageIO.read(it) }?.toComposeImageBitmap() }.getOrNull()
                    }
                }
                Box(Modifier.size(64.dp, 36.dp)) {
                    image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Text(background.name, color = colors.text)
            }
        }
    }
}

// Where the bundled backgrounds' thumbnails are read from
private object BackgroundThumbnails

@Composable
private fun LoadCoursePanel(model: TargetsModel, close: () -> Unit) {
    val choices = remember { model.files.courseChoices() }
    val colors = Range.colors
    if (choices.isEmpty()) Text("No courses in ${model.files.courses.path}", color = colors.muted)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("course-list")) {
        for ((group, courses) in choices.groupBy { it.group }) {
            if (group.isNotEmpty()) item(key = "group-$group") { Text(group.replaceFirstChar { it.uppercase() }, color = colors.muted, fontSize = 12.sp) }
            items(courses, key = { it.file.path }) { choice ->
                PanelRow(choice.name, "course-${choice.name}") {
                    model.loadCourse(choice)
                    close()
                }
            }
        }
    }
}

@Composable
private fun SaveCoursePanel(model: TargetsModel, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<SaveOutcome?>(null) }
    val colors = Range.colors
    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it
            outcome = null
        },
        label = { Text("Course name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("course-name"),
    )
    Text("Saved in ${model.files.courses.path}", color = colors.muted, fontSize = 12.sp)
    when (outcome) {
        SaveOutcome.EXISTS -> {
            Text("There is already a course named ${name.trim()}. Replace it?", color = colors.warning, modifier = Modifier.testTag("replace-question"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        model.saveCourse(name, replace = true)
                        close()
                    },
                    modifier = Modifier.testTag("replace"),
                ) { Text("Replace") }
                TextButton(onClick = { outcome = null }) { Text("Cancel") }
            }
        }
        else -> {
            if (outcome == SaveOutcome.BAD_NAME) {
                Text("Give the course a name, without / or \\", color = colors.warning, modifier = Modifier.testTag("name-hint"))
            }
            FilledTonalButton(
                onClick = {
                    outcome = model.saveCourse(name)
                    if (outcome == SaveOutcome.SAVING) close()
                },
                modifier = Modifier.testTag("save"),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun PanelRow(label: String, tag: String, onClick: () -> Unit) {
    Text(
        label,
        color = Range.colors.text,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp).testTag(tag),
    )
}
