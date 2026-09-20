/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.controller.input.calibration;

import dev.isxander.controlify.controller.input.ControllerStateView;
import dev.isxander.controlify.controller.input.DeadzoneGroup;
import dev.isxander.controlify.controller.input.HatState;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibrationTest {
	private static final Identifier UP = axis("left_stick_up");
	private static final Identifier DOWN = axis("left_stick_down");
	private static final Identifier LEFT = axis("left_stick_left");
	private static final Identifier RIGHT = axis("left_stick_right");
	private static final Identifier TRIGGER = axis("left_trigger");

	private static final DeadzoneGroup LEFT_STICK =
			new DeadzoneGroup(Identifier.fromNamespaceAndPath("controlify", "left_stick"), List.of(UP, DOWN, LEFT, RIGHT));

	@Test
	void identityCalibrationLeavesReadingsAlone() {
		assertEquals(0f, AxisCalibration.IDENTITY.apply(0f), 1.0E-5f);
		assertEquals(0.5f, AxisCalibration.IDENTITY.apply(0.5f), 1.0E-5f);
		assertEquals(1f, AxisCalibration.IDENTITY.apply(1f), 1.0E-5f);
		assertEquals(-1f, AxisCalibration.IDENTITY.apply(-1f), 1.0E-5f);
		assertEquals(-0.5f, AxisCalibration.IDENTITY.apply(-0.5f), 1.0E-5f);
		assertTrue(AxisCalibration.IDENTITY.isTrivial());
	}

	@Test
	void restingOffsetIsRemoved() {
		AxisCalibration calibration = new AxisCalibration(0.08f, 0f, 1f);

		assertEquals(0f, calibration.apply(0.08f), 1.0E-5f, "the resting point must read as no input");
		assertEquals(0f, calibration.apply(0.02f), 1.0E-5f, "below rest still reads as no input");
		assertEquals(0.5f, calibration.apply(0.54f), 1.0E-3f, "travel above rest rescales to fill the range");
		assertEquals(1f, calibration.apply(1f), 1.0E-5f);
	}

	@Test
	void shortTravelIsRescaledToFullStrength() {
		// a worn stick that only reaches 0.9 should still be able to sprint
		AxisCalibration calibration = new AxisCalibration(0f, 0f, 0.9f);

		assertEquals(1f, calibration.apply(0.9f), 1.0E-5f);
		assertEquals(0.5f, calibration.apply(0.45f), 1.0E-5f);
		assertEquals(1f, calibration.apply(1f), 1.0E-5f, "readings past the measured max clamp, never overshoot");
	}

	@Test
	void bothSidesOfASignedAxisScaleIndependently() {
		AxisCalibration calibration = new AxisCalibration(0f, -0.5f, 1f);

		assertEquals(-1f, calibration.apply(-0.5f), 1.0E-5f);
		assertEquals(1f, calibration.apply(1f), 1.0E-5f);
	}

	@Test
	void unmeasuredSideIsPassedThroughRatherThanAmplified() {
		// max sits right on top of rest, so that side was never exercised
		AxisCalibration calibration = new AxisCalibration(0f, -1f, 0.01f);

		assertEquals(0.4f, calibration.apply(0.4f), 1.0E-5f);
	}

	@Test
	void driftOnAOneWayAxisNeverTurnsIntoNegativeInput() {
		// a split stick axis that only runs 0..1, resting high because of drift. Its lowest
		// reading during a roll is 0, which must not be mistaken for a negative extreme.
		AxisCalibration calibration = new AxisCalibration(0.08f, 0f, 1f);

		assertFalse(calibration.isBipolar());
		assertEquals(0f, calibration.apply(0f), 1.0E-5f);
		assertEquals(0f, calibration.apply(0.05f), 1.0E-5f);
		assertEquals(0f, calibration.apply(0.08f), 1.0E-5f);
	}

	@Test
	void anAxisSeenTravellingBothWaysIsTreatedAsBipolar() {
		assertTrue(new AxisCalibration(0f, -0.95f, 0.95f).isBipolar());
		assertFalse(new AxisCalibration(0f, -0.1f, 1f).isBipolar());
	}

	@Test
	void anUnmeasuredNegativeSidePassesThroughUntouched() {
		// a two-way joystick axis whose range step was skipped: we must not clamp its negative
		// half away just because we never watched it move there
		AxisCalibration calibration = new AxisCalibration(0f, 0f, 1f);

		assertEquals(-0.6f, calibration.apply(-0.6f), 1.0E-5f);
	}

	@Test
	void travelAlwaysContainsTheRestingPoint() {
		AxisCalibration calibration = new AxisCalibration(0.2f, 0.5f, 0.1f);

		assertTrue(calibration.min() <= calibration.rest());
		assertTrue(calibration.max() >= calibration.rest());
	}

	@Test
	void recorderMeasuresDriftAndRecommendsADeadzoneThatCoversIt() {
		CalibrationRecorder recorder = new CalibrationRecorder();

		// a stick resting a little off-centre, jittering by a couple of percent
		for (int tick = 0; tick < 40; tick++) {
			float jitter = (tick % 2 == 0) ? 0.01f : -0.01f;
			recorder.sampleRest(state(Map.of(
					UP, 0f,
					DOWN, 0.06f + jitter,
					LEFT, 0f,
					RIGHT, 0f
			)));
		}

		assertFalse(recorder.movedDuringRest());
		assertEquals(0.06f, recorder.restOffset(DOWN), 1.0E-3f);
		assertEquals(0.06f, recorder.worstRestOffset(LEFT_STICK.axes()), 1.0E-3f);

		AxisCalibration down = recorder.calibrationFor(DOWN);
		assertEquals(0f, down.apply(0.06f), 1.0E-3f, "the measured rest must calibrate to zero");

		float deadzone = recorder.recommendedDeadzone(LEFT_STICK);
		float worstJitterAfterCalibration = down.apply(0.07f);
		assertTrue(deadzone > worstJitterAfterCalibration,
				"deadzone " + deadzone + " should swallow the remaining jitter " + worstJitterAfterCalibration);
		assertTrue(deadzone <= CalibrationRecorder.MAX_RECOMMENDED_DEADZONE);
	}

	@Test
	void recorderNoticesAControllerThatWasNotLeftAlone() {
		CalibrationRecorder recorder = new CalibrationRecorder();

		recorder.sampleRest(state(Map.of(UP, 0f)));
		recorder.sampleRest(state(Map.of(UP, 0.8f)));

		assertTrue(recorder.movedDuringRest());

		recorder.resetRest();
		assertFalse(recorder.movedDuringRest());
	}

	@Test
	void skippingTheRangePassLeavesTravelUncalibrated() {
		CalibrationRecorder recorder = new CalibrationRecorder();
		recorder.sampleRest(state(Map.of(TRIGGER, 0.03f)));
		recorder.sampleRange(state(Map.of(TRIGGER, 0.4f)), List.of(TRIGGER));

		assertFalse(recorder.isExplored(TRIGGER), "0.4 is nowhere near a full pull");

		recorder.resetRange(List.of(TRIGGER));
		AxisCalibration calibration = recorder.calibrationFor(TRIGGER);

		assertEquals(0.03f, calibration.rest(), 1.0E-5f, "drift correction survives a skipped step");
		assertEquals(1f, calibration.max(), 1.0E-5f, "but travel falls back to the full range");
	}

	@Test
	void anAxisIsExploredOnceItReachesItsExtreme() {
		CalibrationRecorder recorder = new CalibrationRecorder();
		recorder.sampleRest(state(Map.of(TRIGGER, 0f)));

		recorder.sampleRange(state(Map.of(TRIGGER, 0.5f)), List.of(TRIGGER));
		assertFalse(recorder.isExplored(TRIGGER));

		recorder.sampleRange(state(Map.of(TRIGGER, 0.95f)), List.of(TRIGGER));
		assertTrue(recorder.isExplored(TRIGGER));
		assertEquals(0.95f, recorder.reach(TRIGGER), 1.0E-5f);
	}

	@Test
	void rollingAStickAroundCoversEverySector() {
		CalibrationRecorder recorder = new CalibrationRecorder();

		for (int step = 0; step < 64; step++) {
			double angle = step / 64.0 * Math.PI * 2;
			float x = (float) Math.cos(angle);
			float y = (float) Math.sin(angle);

			recorder.sampleCoverage(LEFT_STICK, state(Map.of(
					UP, Math.max(0f, -y),
					DOWN, Math.max(0f, y),
					LEFT, Math.max(0f, -x),
					RIGHT, Math.max(0f, x)
			)));
		}

		assertEquals(CalibrationRecorder.COVERAGE_SECTORS, recorder.coverageCount(LEFT_STICK));
	}

	@Test
	void pushingAStickOnlyLeftAndRightDoesNotCountAsExplored() {
		CalibrationRecorder recorder = new CalibrationRecorder();

		recorder.sampleCoverage(LEFT_STICK, state(Map.of(UP, 0f, DOWN, 0f, LEFT, 1f, RIGHT, 0f)));
		recorder.sampleCoverage(LEFT_STICK, state(Map.of(UP, 0f, DOWN, 0f, LEFT, 0f, RIGHT, 1f)));

		assertEquals(2, recorder.coverageCount(LEFT_STICK));
		assertFalse(recorder.isGroupExplored(LEFT_STICK));
	}

	@Test
	void onlyNonTrivialCalibrationsAreKept() {
		CalibrationRecorder recorder = new CalibrationRecorder();
		recorder.sampleRest(state(Map.of(UP, 0f, DOWN, 0.05f)));

		Map<Identifier, AxisCalibration> calibrations = recorder.calibrations();

		assertFalse(calibrations.containsKey(UP), "a perfectly centred axis needs no correction stored");
		assertTrue(calibrations.containsKey(DOWN));
	}

	private static Identifier axis(String path) {
		return Identifier.fromNamespaceAndPath("controlify", "axis/" + path);
	}

	private static ControllerStateView state(Map<Identifier, Float> axes) {
		Map<Identifier, Float> copy = new LinkedHashMap<>(axes);
		return new ControllerStateView() {
			@Override
			public boolean isButtonDown(Identifier button) {
				return false;
			}

			@Override
			public Set<Identifier> getButtons() {
				return Set.of();
			}

			@Override
			public float getAxisState(Identifier axis) {
				return copy.getOrDefault(axis, 0f);
			}

			@Override
			public Set<Identifier> getAxes() {
				return copy.keySet();
			}

			@Override
			public float getAxisResting(Identifier axis) {
				return 0f;
			}

			@Override
			public HatState getHatState(Identifier hat) {
				return HatState.CENTERED;
			}

			@Override
			public Set<Identifier> getHats() {
				return Set.of();
			}
		};
	}
}
