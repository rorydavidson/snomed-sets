package org.snomed.valuesets.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AvatarImagesTests {

	@Test
	void shrinksALargePictureToFitInsideTheMaximumKeepingItsShape() throws IOException {
		BufferedImage stored = decode(AvatarImages.normalise(encode(1000, 500, "png")));

		assertThat(stored.getWidth()).isEqualTo(AvatarImages.MAX_SIDE);
		assertThat(stored.getHeight()).isEqualTo(AvatarImages.MAX_SIDE / 2);
	}

	@Test
	void storesAJpegAsAPngAndLeavesASmallPictureItsSize() throws IOException {
		byte[] stored = AvatarImages.normalise(encode(40, 30, "jpg"));

		assertThat(stored).startsWith(0x89, 'P', 'N', 'G');
		assertThat(decode(stored).getWidth()).isEqualTo(40);
	}

	@Test
	void refusesOtherFormatsAndFilesThatAreNotPictures() throws IOException {
		assertThatThrownBy(() -> AvatarImages.normalise(encode(10, 10, "gif")))
				.isInstanceOf(ResponseStatusException.class).hasMessageContaining("PNG or JPEG");
		assertThatThrownBy(() -> AvatarImages.normalise("<svg onload=alert(1)>".getBytes()))
				.isInstanceOf(ResponseStatusException.class).hasMessageContaining("PNG or JPEG");
	}

	@Test
	void refusesAPictureTooLargeToDecodeSafely() throws IOException {
		assertThatThrownBy(() -> AvatarImages.normalise(encode(7000, 2, "png")))
				.isInstanceOf(ResponseStatusException.class).hasMessageContaining("too large");
	}

	private static byte[] encode(int width, int height, String format) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, format, out);
		return out.toByteArray();
	}

	private static BufferedImage decode(byte[] png) throws IOException {
		return ImageIO.read(new ByteArrayInputStream(png));
	}
}
