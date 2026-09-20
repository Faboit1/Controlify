/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.utils;

import net.minecraft.util.Mth;

public final class ColorUtils {
	/**
	 * The accent used by Controlify's calibration UI — a soft, slightly purple lilac that reads
	 * clearly against the dark menu background without shouting like a fully saturated violet.
	 */
	public static final int ACCENT_RGB = 0xB39DDB;
	public static final int ACCENT = 0xFF000000 | ACCENT_RGB;

	/** A dimmer accent, for outlines and inactive parts of the same widget. */
	public static final int ACCENT_DIM = 0xFF7E6BA8;

	public static int grey(float brightness, float alpha) {
		int component = Mth.floor(brightness * 0xff);

		int color = Mth.floor(alpha * 0xff); // A
		color = (color << 8) | component; // R
		color = (color << 8) | component; // G
		color = (color << 8) | component; // B

		return color;
	}
}
