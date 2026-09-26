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

import com.shootoff.util.UserNotifier;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;

/**
 * Shows problems reported by non-UI code as JavaFX error dialogs.
 */
public class AlertUserNotifier implements UserNotifier {
	@Override
	public void showError(String title, String header, String message) {
		if (Platform.isFxApplicationThread()) {
			show(title, header, message);
		} else {
			Platform.runLater(() -> show(title, header, message));
		}
	}

	private static void show(String title, String header, String message) {
		final Alert alert = new Alert(AlertType.ERROR);
		alert.setTitle(title);
		alert.setHeaderText(header);
		alert.setResizable(true);
		alert.setContentText(message);
		alert.showAndWait();
	}
}
