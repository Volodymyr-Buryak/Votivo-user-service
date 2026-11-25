package com.votivo.user_service.image;

import com.votivo.user_service.config.PhotoProperties;
import com.votivo.user_service.image.validation.ImageValidator;
import com.votivo.user_service.image.validation.exception.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.*;

class ImageValidatorTest {
    private ImageValidator validator(long maxBytes, int width, int height, Set<ImageFormat> formats) {
        return new ImageValidator(new PhotoProperties(DataSize.ofBytes(maxBytes), width, height, formats));
    }

    @Test
    void detectsActualFormatAndAcceptsExactLimits() throws Exception {
        for (ImageFormat format : ImageFormat.values()) {
            byte[] bytes = image(format.getExtension(), 3, 2);
            var result = validator(bytes.length, 3, 2, Set.of(format)).validate(bytes);
            assertThat(result.format()).isEqualTo(format);
            assertThat(result.bytes()).isEqualTo(bytes);
        }
    }

    @Test
    void rejectsEmptyInvalidTruncatedOversizedAndDisallowedImages() throws Exception {
        var validator = validator(10000, 10, 10, Set.of(ImageFormat.PNG));
        assertThatThrownBy(() -> validator.validate(new byte[0])).isInstanceOf(InvalidImageException.class);
        assertThatThrownBy(() -> validator.validate(new byte[]{1, 2, 3})).isInstanceOf(InvalidImageException.class);
        byte[] png = image("png", 2, 2);
        assertThatThrownBy(() -> validator.validate(Arrays.copyOf(png, 40))).isInstanceOf(InvalidImageException.class);
        assertThatThrownBy(() -> validator(png.length - 1, 10, 10, Set.of(ImageFormat.PNG)).validate(png))
                .isInstanceOf(ImageTooLargeException.class);
        byte[] jpeg = image("jpg", 2, 2);
        assertThatThrownBy(() -> validator.validate(jpeg)).isInstanceOf(UnsupportedImageFormatException.class);
        byte[] gif = image("gif", 2, 2);
        assertThatThrownBy(() -> validator.validate(gif)).isInstanceOf(UnsupportedImageFormatException.class);
    }

    @Test
    void rejectsEachDimensionBeforeDecoding() throws Exception {
        var validator = validator(10000, 2, 2, Set.of(ImageFormat.PNG));
        byte[] wide = image("png", 3, 2);
        byte[] tall = image("png", 2, 3);
        assertThatThrownBy(() -> validator.validate(wide)).isInstanceOf(ImageDimensionsExceededException.class);
        assertThatThrownBy(() -> validator.validate(tall)).isInstanceOf(ImageDimensionsExceededException.class);
    }

    public static byte[] image(String format, int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output)).isTrue();
        return output.toByteArray();
    }
}
