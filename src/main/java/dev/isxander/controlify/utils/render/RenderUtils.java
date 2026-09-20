/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.utils.render;

import dev.isxander.controlify.utils.ColorUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

public final class RenderUtils {
	private static final Identifier GREEN_BACK_BAR = Identifier.withDefaultNamespace("boss_bar/green_background");
	private static final Identifier GREEN_FRONT_BAR = Identifier.withDefaultNamespace("boss_bar/green_progress");

	private RenderUtils() {
	}

	public static void extractBar(GuiGraphicsExtractor graphics, int centerX, int y, float progress) {
		int width = Mth.lerpDiscrete(progress, 0, 182);

		int x = centerX - 182 / 2;

		graphics.blitSprite(
				RenderPipelines.GUI_TEXTURED,
				GREEN_BACK_BAR,
				182, 5,
				0, 0,
				x, y,
				182, 5
		);
		if (width > 0) {
			graphics.blitSprite(
					RenderPipelines.GUI_TEXTURED,
					GREEN_FRONT_BAR,
					182, 5,
					0, 0,
					x, y,
					width, 5
			);
		}
	}

	/**
	 * A flat progress bar in Controlify's accent colour, for screens that want to match the
	 * calibration wizard rather than the vanilla boss bar.
	 */
	public static void extractAccentBar(GuiGraphicsExtractor graphics, int centerX, int y, int width, float progress) {
		int x = centerX - width / 2;
		int filled = Mth.lerpDiscrete(Mth.clamp(progress, 0f, 1f), 0, width);

		graphics.fill(x - 1, y - 1, x + width + 1, y + 6, 0xFF1A1420);
		graphics.fill(x, y, x + width, y + 5, ColorUtils.ACCENT_DIM & 0x60FFFFFF);
		if (filled > 0) {
			graphics.fill(x, y, x + filled, y + 5, ColorUtils.ACCENT);
		}
	}
}
