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

package com.shootoff.camera.cameratypes;

import java.util.OptionalInt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

/**
 * Reads and writes Linux V4L2 camera controls that OpenCV's VideoCapture does
 * not expose. Every failure (missing device, a device that isn't a camera, a
 * camera without the control, no libc) returns empty/false instead of
 * throwing.
 */
public final class V4l2Controls {
	private static final Logger logger = LoggerFactory.getLogger(V4l2Controls.class);

	/**
	 * V4L2_CID_EXPOSURE_AUTO_PRIORITY, shown by v4l2-ctl as
	 * exposure_dynamic_framerate. When 1, auto exposure may lower the frame
	 * rate to lengthen exposure.
	 */
	public static final int EXPOSURE_DYNAMIC_FRAMERATE = 0x009a0903;

	// _IOWR('V', 27, struct v4l2_control) and _IOWR('V', 28, struct v4l2_control)
	private static final long VIDIOC_G_CTRL = 0xC008561BL;
	private static final long VIDIOC_S_CTRL = 0xC008561CL;

	private static final int O_RDWR = 2;

	// struct v4l2_control { __u32 id; __s32 value; }
	private static final int CONTROL_SIZE = 8;

	private interface CLibrary extends Library {
		int open(String path, int flags);

		int close(int fd);

		int ioctl(int fd, NativeLong request, Pointer argument);
	}

	// Loaded on first use so non-Linux platforms never touch libc
	private static final class LibC {
		private static final CLibrary INSTANCE = Native.load("c", CLibrary.class);
	}

	private V4l2Controls() {}

	public static OptionalInt getControl(String device, int controlId) {
		final Memory control = newControl(controlId, 0);

		if (!ioctl(device, VIDIOC_G_CTRL, control)) return OptionalInt.empty();

		return OptionalInt.of(control.getInt(4));
	}

	public static boolean setControl(String device, int controlId, int value) {
		return ioctl(device, VIDIOC_S_CTRL, newControl(controlId, value));
	}

	private static Memory newControl(int controlId, int value) {
		final Memory control = new Memory(CONTROL_SIZE);
		control.setInt(0, controlId);
		control.setInt(4, value);
		return control;
	}

	private static boolean ioctl(String device, long request, Memory control) {
		try {
			final CLibrary libc = LibC.INSTANCE;
			final int fd = libc.open(device, O_RDWR);

			if (fd < 0) {
				logger.debug("Could not open {} for V4L2 control access", device);
				return false;
			}

			try {
				final int result = libc.ioctl(fd, new NativeLong(request), control);

				if (result < 0) logger.debug("V4L2 ioctl {} on {} failed", Long.toHexString(request), device);

				return result >= 0;
			} finally {
				libc.close(fd);
			}
		} catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
			logger.debug("libc is not available for V4L2 control access", e);
			return false;
		}
	}
}
