package com.votivo.user_service.image.validation;

import lombok.*;

import java.util.Iterator;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.io.ByteArrayInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import com.votivo.user_service.image.ImageData;
import com.votivo.user_service.image.ImageFormat;
import com.votivo.user_service.image.validation.exception.*;

@RequiredArgsConstructor
public class ImageValidator {

    @NonNull
    private final ImagePolicy policy;

    public ImageData validate(byte @NonNull [] data) {
        validateSize(data, policy);

        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            ImageFormat format = validateContent(input, policy);
            return new ImageData(data, format);
        } catch (IOException e) {
            throw new InvalidImageException(e);
        }
    }

    private ImageFormat validateContent(ImageInputStream input, ImagePolicy policy) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            throw new InvalidImageException();
        }

        ImageReader reader = readers.next();
        try {
            reader.setInput(input);

            ImageFormat format = ImageFormat.fromFormatName(reader.getFormatName());

            validateFormat(format, policy);
            validateDimensions(reader, policy);

            reader.read(0); // Decode the first frame to detect corrupt image data.
            return format;
        } finally {
            reader.dispose();
        }
    }

    private void validateSize(byte[] data, ImagePolicy policy) {
        if (data.length == 0) {
            throw new InvalidImageException();
        }

        long maxSize = policy.maxSize().toBytes();
        if (data.length > maxSize) {
            throw new ImageTooLargeException(data.length, maxSize);
        }
    }

    private void validateFormat(ImageFormat format, ImagePolicy policy) {
        if (!policy.allowedFormats().contains(format)) {
            throw new UnsupportedImageFormatException(format.name());
        }
    }

    private void validateDimensions(ImageReader reader, ImagePolicy policy) throws IOException {
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);

        if (width > policy.maxWidthPixels() || height > policy.maxHeightPixels()) {
            throw new ImageDimensionsExceededException(width, height, policy.maxWidthPixels(), policy.maxHeightPixels());
        }
    }
}