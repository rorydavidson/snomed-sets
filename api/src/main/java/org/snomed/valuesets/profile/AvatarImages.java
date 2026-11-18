package org.snomed.valuesets.profile;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns an uploaded picture into the PNG that is stored. Whatever was uploaded is decoded and drawn
 * again, so nothing but pixels is kept: no metadata such as location, and nothing hidden in the file.
 */
final class AvatarImages {

	static final int MAX_SIDE = 256;
	/** Checked before decoding, so a small file that claims to be a huge image is refused. */
	private static final int MAX_SOURCE_SIDE = 6000;
	private static final Set<String> ACCEPTED_FORMATS = Set.of("png", "jpeg");

	private AvatarImages() {
	}

	static byte[] normalise(byte[] upload) {
		BufferedImage source = read(upload);
		double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(source.getWidth(), source.getHeight()));
		int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

		BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = resized.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.drawImage(source, 0, 0, width, height, null);
		} finally {
			graphics.dispose();
		}

		ByteArrayOutputStream png = new ByteArrayOutputStream();
		try {
			ImageIO.write(resized, "png", png);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return png.toByteArray();
	}

	private static BufferedImage read(byte[] upload) {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(upload))) {
			Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
			if (readers == null || !readers.hasNext()) {
				throw unreadable();
			}
			ImageReader reader = readers.next();
			try {
				if (!ACCEPTED_FORMATS.contains(reader.getFormatName().toLowerCase())) {
					throw unreadable();
				}
				reader.setInput(input, true, true);
				if (reader.getWidth(0) > MAX_SOURCE_SIDE || reader.getHeight(0) > MAX_SOURCE_SIDE) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
							"The picture is too large. Use one at most " + MAX_SOURCE_SIDE + " pixels on each side.");
				}
				return reader.read(0);
			} finally {
				reader.dispose();
			}
		} catch (IOException e) {
			throw unreadable();
		}
	}

	private static ResponseStatusException unreadable() {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, "The picture must be a PNG or JPEG image.");
	}
}
