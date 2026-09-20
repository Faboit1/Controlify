/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.controller.input;

import dev.isxander.controlify.config.settings.device.AxisCalibrationSettings;
import net.minecraft.resources.Identifier;

import java.util.Set;

/**
 * Applies a device's measured {@link dev.isxander.controlify.controller.input.calibration.AxisCalibration}
 * to every axis reading.
 *
 * <p>This sits underneath {@link DeadzoneControllerStateView}: calibration removes the resting
 * offset and restores full travel, and only then does the deadzone decide what counts as input.
 * Running it the other way round would leave the deadzone fighting a drift it cannot see.
 */
public class CalibratedControllerStateView implements ControllerStateView {
	private final ControllerStateView view;
	private final InputComponent input;

	public CalibratedControllerStateView(ControllerStateView view, InputComponent input) {
		this.view = view;
		this.input = input;
	}

	@Override
	public boolean isButtonDown(Identifier button) {
		return view.isButtonDown(button);
	}

	@Override
	public Set<Identifier> getButtons() {
		return view.getButtons();
	}

	@Override
	public float getAxisState(Identifier axis) {
		float raw = view.getAxisState(axis);

		AxisCalibrationSettings calibration = input.calibration();
		if (!calibration.enabled) {
			return raw;
		}

		return calibration.get(axis).apply(raw);
	}

	@Override
	public Set<Identifier> getAxes() {
		return view.getAxes();
	}

	@Override
	public float getAxisResting(Identifier axis) {
		return view.getAxisResting(axis);
	}

	@Override
	public HatState getHatState(Identifier hat) {
		return view.getHatState(hat);
	}

	@Override
	public Set<Identifier> getHats() {
		return view.getHats();
	}
}
