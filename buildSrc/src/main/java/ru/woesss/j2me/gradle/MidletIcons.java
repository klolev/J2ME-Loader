/*
 *  Copyright 2024 J2ME Loader contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ru.woesss.j2me.gradle;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Turns the icon declared by a MIDlet suite into a full set of Android launcher resources.
 *
 * <p>A suite icon is a single small bitmap — 16x16 or so, often pixel art with a transparent
 * background. Android wants one bitmap per density plus an adaptive icon, so the source is
 * rescaled into each bucket and composed onto a background colour picked from the icon
 * itself.
 */
final class MidletIcons {
	/** Launcher icon edge in dp, per {@code android.R.dimen.app_icon_size} for each bucket. */
	private static final Map<String, Integer> DENSITIES = new LinkedHashMap<String, Integer>() {{
		put("mdpi", 48);
		put("hdpi", 72);
		put("xhdpi", 96);
		put("xxhdpi", 144);
		put("xxxhdpi", 192);
	}};

	/** Adaptive icons are 108dp with only the middle 72dp guaranteed visible. */
	private static final int ADAPTIVE_DP = 108;
	private static final double ADAPTIVE_SAFE_FRACTION = 72.0 / ADAPTIVE_DP;

	/** Fraction of the legacy icon square the artwork fills, leaving a little breathing room. */
	private static final double LEGACY_FILL_FRACTION = 0.75;

	/** J2ME Loader's own launcher background, used when the icon suggests nothing better. */
	private static final Color DEFAULT_BACKGROUND = new Color(0x52, 0x5A, 0xA0);

	private MidletIcons() {
	}

	/**
	 * Writes launcher resources for {@code iconData} into {@code resDir}, overriding the
	 * emulator's own icon for this flavor.
	 *
	 * @return true if the icon could be decoded and the resources were written
	 */
	static boolean generate(byte[] iconData, File resDir) throws IOException {
		BufferedImage source;
		try {
			source = ImageIO.read(new ByteArrayInputStream(iconData));
		} catch (IOException e) {
			return false;
		}
		if (source == null) {
			return false;
		}
		Color background = pickBackground(source);
		for (Map.Entry<String, Integer> density : DENSITIES.entrySet()) {
			File dir = new File(resDir, "mipmap-" + density.getKey());
			Files.createDirectories(dir.toPath());
			int size = density.getValue();
			writePng(composeLegacy(source, size, background), new File(dir, "ic_launcher.png"));
			int adaptiveSize = size * ADAPTIVE_DP / DENSITIES.get("mdpi");
			writePng(composeAdaptiveForeground(source, adaptiveSize), new File(dir, "ic_launcher_foreground.png"));
		}

		File anyDpi = new File(resDir, "mipmap-anydpi-v26");
		Files.createDirectories(anyDpi.toPath());
		// The monochrome layer is the same artwork: Android tints it by alpha, so the icon
		// becomes a themed silhouette of the suite's own icon rather than falling back to an
		// untinted one on a themed launcher. This is how the emulator declares its own icon.
		String adaptiveIcon = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
				+ "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
				+ "    <background android:drawable=\"@color/ic_launcher_background\"/>\n"
				+ "    <foreground android:drawable=\"@mipmap/ic_launcher_foreground\"/>\n"
				+ "    <monochrome android:drawable=\"@mipmap/ic_launcher_foreground\"/>\n"
				+ "</adaptive-icon>\n";
		Files.write(new File(anyDpi, "ic_launcher.xml").toPath(), adaptiveIcon.getBytes(StandardCharsets.UTF_8));

		File values = new File(resDir, "values");
		Files.createDirectories(values.toPath());
		String colors = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
				+ "<resources>\n"
				+ String.format("    <color name=\"ic_launcher_background\">#%06X</color>%n", background.getRGB() & 0xFFFFFF)
				+ "</resources>\n";
		Files.write(new File(values, "ic_launcher_background.xml").toPath(), colors.getBytes(StandardCharsets.UTF_8));
		return true;
	}

	/** Legacy icon: the artwork centred on an opaque square, so transparent art still reads. */
	private static BufferedImage composeLegacy(BufferedImage source, int size, Color background) {
		BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = target.createGraphics();
		try {
			g.setColor(background);
			g.fillRect(0, 0, size, size);
			drawCentered(g, source, size, (int) Math.round(size * LEGACY_FILL_FRACTION));
		} finally {
			g.dispose();
		}
		return target;
	}

	/** Adaptive foreground: the artwork inside the safe zone of a transparent 108dp square. */
	private static BufferedImage composeAdaptiveForeground(BufferedImage source, int size) {
		BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = target.createGraphics();
		try {
			drawCentered(g, source, size, (int) Math.round(size * ADAPTIVE_SAFE_FRACTION * LEGACY_FILL_FRACTION));
		} finally {
			g.dispose();
		}
		return target;
	}

	/** Scales the source to fit {@code box}, keeping its aspect ratio, and centres it. */
	private static void drawCentered(Graphics2D g, BufferedImage source, int canvas, int box) {
		int width = source.getWidth();
		int height = source.getHeight();
		double scale = (double) box / Math.max(width, height);
		int drawWidth = Math.max(1, (int) Math.round(width * scale));
		int drawHeight = Math.max(1, (int) Math.round(height * scale));
		// Suite icons are usually tiny pixel art: upscaling it smoothly turns it to mush,
		// so magnification keeps hard pixel edges and only downscaling interpolates.
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale >= 1.0
				? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
				: RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(source, (canvas - drawWidth) / 2, (canvas - drawHeight) / 2, drawWidth, drawHeight, null);
	}

	/**
	 * Picks the backdrop for the icon. An icon drawn for a phone menu usually sits on its own
	 * opaque plate, and reusing that colour keeps the launcher icon looking of a piece; art
	 * with transparent edges gets the emulator's default instead.
	 */
	private static Color pickBackground(BufferedImage source) {
		int width = source.getWidth();
		int height = source.getHeight();
		Map<Integer, Integer> counts = new HashMap<>();
		int border = 0;
		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				if (x != 0 && y != 0 && x != width - 1 && y != height - 1) {
					continue;
				}
				border++;
				int argb = source.getRGB(x, y);
				if ((argb >>> 24) < 0xFF) {
					return DEFAULT_BACKGROUND;
				}
				counts.merge(argb & 0xFFFFFF, 1, Integer::sum);
			}
		}
		if (border == 0) {
			return DEFAULT_BACKGROUND;
		}
		Map.Entry<Integer, Integer> dominant = null;
		for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
			if (dominant == null || entry.getValue() > dominant.getValue()) {
				dominant = entry;
			}
		}
		if (dominant == null || dominant.getValue() * 100 < border * 60) {
			return DEFAULT_BACKGROUND;
		}
		return new Color(dominant.getKey());
	}

	private static void writePng(BufferedImage image, File file) throws IOException {
		if (!ImageIO.write(image, "png", file)) {
			throw new IOException("No PNG encoder available to write " + file);
		}
	}
}
