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

package com.shootoff.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.cameratypes.PS3EyeCamera;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/**
 * The PS3 Eye's gain, exposure and auto-gain window. At most one is open at a time.
 */
public final class PS3EyeSettingsWindow implements PS3EyeCamera.SettingsListener {
	private static final Logger logger = LoggerFactory.getLogger(PS3EyeSettingsWindow.class);

	private static PS3EyeSettingsWindow openWindow = null;

	private final PS3EyeCamera camera;
	private final Stage stage = new Stage();
	private final Label fpsValue = new Label("0");

	private PS3EyeSettingsWindow(PS3EyeCamera camera) {
		this.camera = camera;
		buildScene();
	}

	/**
	 * Opens the settings window for <tt>camera</tt>, replacing any open one. Must be called on
	 * the JavaFX application thread.
	 */
	public static void show(PS3EyeCamera camera) {
		logger.trace("Launch camera settings called");

		if (openWindow != null) openWindow.close();

		final PS3EyeSettingsWindow window = new PS3EyeSettingsWindow(camera);
		openWindow = window;
		camera.setSettingsListener(window);
		window.stage.setOnCloseRequest((e) -> window.detach());
		window.stage.show();
	}

	@Override
	public void fpsUpdated(double fps) {
		Platform.runLater(() -> {
			final String theFPS = Double.toString(fps);
			if (theFPS.length() >= 6) fpsValue.setText(theFPS.substring(0, 5));
		});
	}

	@Override
	public void cameraClosing() {
		Platform.runLater(() -> {
			if (openWindow == this) openWindow = null;
			stage.close();
		});
	}

	private void detach() {
		camera.setSettingsListener(null);
		if (openWindow == this) openWindow = null;
	}

	private void close() {
		detach();
		stage.close();
	}

	private void buildScene() {
		final CheckBox autoGain = new CheckBox("AutoGain");
		final Color textColor = Color.BLACK;

		final Slider gain = new Slider(0, 63, camera.getGain());
		final Slider exposure = new Slider(0, 255, camera.getExposure());

		final Label gainCaption = new Label("Gain:");
		final Label exposureCaption = new Label("Exposure:");
		final Label autoGainCaption = new Label("Auto Gain:");
		final Label fpsCaption = new Label("FPS: ");

		final Label gainValue = new Label(Integer.toString((int) gain.getValue()));
		final Label exposureValue = new Label(Integer.toString((int) exposure.getValue()));

		gain.setShowTickLabels(true);
		gain.setShowTickMarks(true);
		gain.setMajorTickUnit(9);// 63
		gain.setMinorTickCount(9);
		gain.setBlockIncrement(1);
		gain.setSnapToTicks(true);

		exposure.setShowTickLabels(true);
		exposure.setShowTickMarks(true);
		exposure.setMajorTickUnit(50);// 255
		exposure.setMinorTickCount(25);
		exposure.setBlockIncrement(1);
		exposure.setSnapToTicks(true);

		final Group root = new Group();
		final Scene scene = new Scene(root, 425, 200);
		stage.setScene(scene);
		stage.setTitle("PS3EYE Configuration");
		scene.setFill(Color.WHITESMOKE);

		final GridPane grid = new GridPane();
		grid.setPadding(new Insets(10, 10, 10, 10));
		grid.setVgap(10);
		grid.setHgap(70);

		scene.setRoot(grid);

		gainCaption.setTextFill(textColor);
		GridPane.setConstraints(gainCaption, 0, 1);
		grid.getChildren().add(gainCaption);

		exposureCaption.setTextFill(textColor);
		GridPane.setConstraints(exposureCaption, 0, 2);
		grid.getChildren().add(exposureCaption);

		GridPane.setConstraints(autoGainCaption, 0, 4);
		grid.getChildren().add(autoGainCaption);

		GridPane.setConstraints(fpsCaption, 0, 5);
		grid.getChildren().add(fpsCaption);

		GridPane.setConstraints(gain, 1, 1);
		grid.getChildren().add(gain);

		GridPane.setConstraints(exposure, 1, 2);
		grid.getChildren().add(exposure);

		gainValue.setTextFill(textColor);
		GridPane.setConstraints(gainValue, 2, 1);
		grid.getChildren().add(gainValue);

		exposureValue.setTextFill(textColor);
		GridPane.setConstraints(exposureValue, 2, 2);
		grid.getChildren().add(exposureValue);

		GridPane.setConstraints(fpsValue, 1, 5);
		grid.getChildren().add(fpsValue);

		gain.valueProperty().addListener(new ChangeListener<Number>() {
			@Override
			public void changed(ObservableValue<? extends Number> ov, Number old_val, Number new_val) {
				if (logger.isTraceEnabled()) logger.trace("gain set to: {}", Math.round(new_val.doubleValue()));
				camera.setGain((int) Math.round(new_val.doubleValue()));
				gainValue.setText(String.format("%d", (int) Math.round(new_val.doubleValue())));
			}
		});

		exposure.valueProperty().addListener(new ChangeListener<Number>() {
			@Override
			public void changed(ObservableValue<? extends Number> ov, Number old_val, Number new_val) {
				camera.setExposure((int) Math.round(new_val.doubleValue()));
				if (logger.isTraceEnabled())
					logger.trace("exposure level set to: {}", Math.round(new_val.doubleValue()));
				exposureValue.setText(String.format("%d", (int) Math.round(new_val.doubleValue())));
			}
		});

		final boolean isAutoGainSet = camera.isAutoGain();
		if (!isAutoGainSet) {
			autoGain.setText("Off");
			gain.setDisable(false);
			exposure.setDisable(false);
		} else {
			autoGain.setText("On");
			gain.setDisable(true);
			exposure.setDisable(true);
		}

		autoGain.setSelected(isAutoGainSet);

		GridPane.setConstraints(autoGain, 1, 4);
		grid.getChildren().add(autoGain);

		autoGain.selectedProperty().addListener(new ChangeListener<Boolean>() {
			@Override
			public void changed(ObservableValue<? extends Boolean> ov, Boolean old_val, Boolean new_val) {
				if (new_val) {
					autoGain.setText("On");
					gain.setValue(camera.getGain());
					gain.setDisable(true);
					exposure.setDisable(true);
					camera.setAutoGain(true);
				} else {
					camera.setAutoGain(false);
					autoGain.setText("Off");
					gain.setValue(camera.getGain());
					gain.setDisable(false);
					exposure.setDisable(false);
				}
			}
		});
	}
}
