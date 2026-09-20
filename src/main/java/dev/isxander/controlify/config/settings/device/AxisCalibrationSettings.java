/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.settings.device;

import dev.isxander.controlify.config.dto.device.AxisCalibrationConfig;
import dev.isxander.controlify.controller.input.calibration.AxisCalibration;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The live, mutable view of a device's {@link AxisCalibrationConfig}. */
public class AxisCalibrationSettings {
	/** Shared stand-in for a device whose settings have not been attached yet. */
	public static final AxisCalibrationSettings NONE = new AxisCalibrationSettings();

	public boolean enabled;
	public long calibratedAt;
	private final Map<Identifier, AxisCalibration> axes;

	private AxisCalibrationSettings() {
		this.enabled = true;
		this.calibratedAt = 0L;
		this.axes = new LinkedHashMap<>();
	}

	public AxisCalibrationSettings(boolean enabled, long calibratedAt, Map<Identifier, AxisCalibration> axes) {
		this.enabled = enabled;
		this.calibratedAt = calibratedAt;
		this.axes = new LinkedHashMap<>(axes);
	}

	public static AxisCalibrationSettings defaults() {
		return new AxisCalibrationSettings();
	}

	public AxisCalibration get(Identifier axis) {
		return axes.getOrDefault(axis, AxisCalibration.IDENTITY);
	}

	public Map<Identifier, AxisCalibration> axes() {
		return Collections.unmodifiableMap(axes);
	}

	/** Whether any axis on this device has actually been measured. */
	public boolean isCalibrated() {
		return !axes.isEmpty();
	}

	/** Replaces the whole calibration, stamping it with the time it was measured. */
	public void replaceAll(Map<Identifier, AxisCalibration> newAxes, long timestamp) {
		axes.clear();
		axes.putAll(newAxes);
		calibratedAt = timestamp;
	}

	public void clear() {
		axes.clear();
		calibratedAt = 0L;
	}

	public static AxisCalibrationSettings fromDTO(AxisCalibrationConfig dto) {
		return new AxisCalibrationSettings(dto.enabled(), dto.calibratedAt(), dto.axes());
	}

	public AxisCalibrationConfig toDTO() {
		return new AxisCalibrationConfig(enabled, calibratedAt, Map.copyOf(axes));
	}
}
