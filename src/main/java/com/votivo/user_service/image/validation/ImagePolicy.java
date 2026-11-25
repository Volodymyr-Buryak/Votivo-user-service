package com.votivo.user_service.image.validation;

import java.util.Set;
import com.votivo.user_service.image.ImageFormat;
import org.springframework.util.unit.DataSize;

public interface ImagePolicy {
    DataSize maxSize();
    int maxWidthPixels();
    int maxHeightPixels();
    Set<ImageFormat> allowedFormats();
}