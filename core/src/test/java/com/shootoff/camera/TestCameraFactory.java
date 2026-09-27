package com.shootoff.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// Final review (Plan 7): CameraFactory mapped list position straight to /dev/video<position>, for both the
// capture-node query and the camera's own open index. A replugged camera that re-enumerates under another
// /dev/videoN (a gap left by an unplugged one) then opens the wrong device, or none. The index should come
// from the sarxos name's own trailing /dev/videoN when it has one, since that's the real node; list
// position is only a fallback for a name without it.
class TestCameraFactory {
	@Test
	void aTrailingDeviceNodeInTheNameWinsOverListPosition() {
		assertEquals(2, CameraFactory.deviceIndex("UVC Camera (046d:0825) /dev/video2", 0));
	}

	@Test
	void noDeviceNodeInTheNameFallsBackToListPosition() {
		assertEquals(3, CameraFactory.deviceIndex("UVC Camera (046d:0825)", 3));
	}

	@Test
	void anOddNameFallsBackToListPositionToo() {
		assertEquals(1, CameraFactory.deviceIndex("/dev/videoBogus", 1));
	}
}
