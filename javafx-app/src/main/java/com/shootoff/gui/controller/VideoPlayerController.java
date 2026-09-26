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

package com.shootoff.gui.controller;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.video.TimedFrame;
import com.shootoff.camera.video.VideoReader;
import com.shootoff.gui.PlaybackListener;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

public class VideoPlayerController implements PlaybackListener {
	@FXML private TabPane videoTabPane;
	@FXML private Slider timeSlider;
	@FXML private Label timeLabel;
	@FXML private Button togglePlaybackButton;

	private static final Logger logger = LoggerFactory.getLogger(VideoPlayerController.class);

	private final Map<String, PlaybackContext> contexts = new HashMap<>();
	private PlaybackContext currentContext;

	public void init(Map<String, File> videos) {
		togglePlaybackButton.setGraphic(new ImageView(
				new Image(VideoPlayerController.class.getResourceAsStream("/images/gnome_media_playback_start.png"))));
		createTabs(videos);

		if (contexts.isEmpty()) {
			timeSlider.setDisable(true);
			togglePlaybackButton.setDisable(true);
			return;
		}

		currentContext = contexts.get(videoTabPane.getSelectionModel().getSelectedItem().getText());
		timeSlider.setMax(currentContext.getDuration());

		timeSlider.valueProperty().addListener(new ChangeListener<Number>() {
			@Override
			public void changed(ObservableValue<? extends Number> observableValue, Number oldValue, Number newValue) {
				if (newValue == null) {
					timeLabel.setText("");
					return;
				}

				setTime(newValue.longValue());
			}
		});

		videoTabPane.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<Tab>() {
			@Override
			public void changed(ObservableValue<? extends Tab> observable, Tab oldValue, Tab newValue) {
				togglePlaybackButton.setGraphic(new ImageView(new Image(
						VideoPlayerController.class.getResourceAsStream("/images/gnome_media_playback_start.png"))));

				currentContext.pausePlayback();
				currentContext = contexts.get(newValue.getText());

				timeSlider.setMax(currentContext.getDuration());
				timeSlider.setValue(currentContext.getTimestamp());
			}
		});
	}

	private void setTime(long timestamp /* ms */) {
		final Date date = new Date(timestamp);
		final DateFormat formatter = new SimpleDateFormat("mm:ss:SSS");
		timeLabel.setText(formatter.format(date));
	}

	@Override
	public void frameUpdated(long timestamp) {
		timeSlider.setValue(timestamp);

		if (timestamp == currentContext.getDuration()) {
			togglePlaybackButton.setGraphic(new ImageView(new Image(
					VideoPlayerController.class.getResourceAsStream("/images/gnome_media_playback_start.png"))));
		}
	}

	private static class PlaybackContext {
		private final VideoReader reader;
		private final PlaybackListener listener;
		private final long duration;
		private volatile boolean isPlaying = false;
		private final ImageView imageView = new ImageView();
		private long lastTimestamp = 0;

		public PlaybackContext(File videoFile, PlaybackListener listener) throws IOException {
			this.listener = listener;

			reader = new VideoReader(videoFile);
			duration = reader.getDurationMs();
		}

		public long getDuration() {
			return duration;
		}

		public long getTimestamp() {
			return lastTimestamp;
		}

		/**
		 * @return false at the end of the video
		 */
		private boolean showNextFrame(boolean doDelay) {
			final Optional<TimedFrame> frame;
			try {
				frame = reader.next();
			} catch (final IOException e) {
				logger.error("Error while reading video frames", e);
				return false;
			}

			if (!frame.isPresent()) return false;

			final long currentTimestamp = frame.get().getTimestampMs();

			if (doDelay) {
				try {
					Thread.sleep(Math.max(0, currentTimestamp - lastTimestamp));
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}

			lastTimestamp = currentTimestamp;
			final Image image = SwingFXUtils.toFXImage(frame.get().getImage(), null);
			Platform.runLater(() -> {
				imageView.setImage(image);
				listener.frameUpdated(currentTimestamp);
			});

			return true;
		}

		private void playVideo() {
			new Thread(() -> {
				boolean moreFrames = true;
				while (isPlaying && (moreFrames = showNextFrame(true))) {}

				// moreFrames is still true if playback was paused
				if (!moreFrames) {
					isPlaying = false;
					lastTimestamp = getDuration();
					Platform.runLater(() -> listener.frameUpdated(getDuration()));
				}
			}, "PlayVideo").start();
		}

		private void playFromBeginning() {
			lastTimestamp = 0;

			try {
				reader.rewind();
			} catch (final IOException e) {
				logger.error("Error rewinding video", e);
				return;
			}

			playVideo();
		}

		public void nextFrame() {
			showNextFrame(false);
		}

		public void pausePlayback() {
			isPlaying = false;
		}

		public void togglePlayback() {
			isPlaying = !isPlaying;

			if (isPlaying) {
				if (lastTimestamp != getDuration()) {
					playVideo();
				} else {
					playFromBeginning();
				}
			}
		}

		public boolean isPlaying() {
			return isPlaying;
		}

		public ImageView getImageView() {
			return imageView;
		}
	}

	private void createTabs(Map<String, File> videos) {
		for (final Entry<String, File> video : videos.entrySet()) {
			final PlaybackContext context;
			try {
				context = new PlaybackContext(video.getValue(), this);
			} catch (final IOException e) {
				logger.error("Skipping video {} for camera {}", video.getValue(), video.getKey(), e);
				continue;
			}

			final Tab videoTab = new Tab(video.getKey());
			videoTabPane.getTabs().add(videoTab);
			videoTab.setContent(context.getImageView());
			contexts.put(video.getKey(), context);
		}
	}

	@FXML
	public void nextButtonClicked(ActionEvent event) {
		if (currentContext == null) return;

		currentContext.nextFrame();
	}

	@FXML
	public void togglePlaybackButtonClicked(ActionEvent event) {
		if (currentContext == null) return;

		currentContext.togglePlayback();

		if (currentContext.isPlaying()) {
			togglePlaybackButton.setGraphic(new ImageView(new Image(
					VideoPlayerController.class.getResourceAsStream("/images/gnome_media_playback_pause.png"))));
		} else {
			togglePlaybackButton.setGraphic(new ImageView(new Image(
					VideoPlayerController.class.getResourceAsStream("/images/gnome_media_playback_start.png"))));
		}
	}

	public Stage getStage() {
		return (Stage) togglePlaybackButton.getScene().getWindow();
	}
}
