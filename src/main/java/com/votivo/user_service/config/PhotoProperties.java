package com.votivo.user_service.config;

import com.votivo.user_service.image.ImageFormat;
import com.votivo.user_service.image.validation.ImagePolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.photo")
public record PhotoProperties(
        @NotNull DataSize maxSize,
        @Positive int maxWidthPixels,
        @Positive int maxHeightPixels,
        @NotEmpty Set<@NotNull ImageFormat> allowedFormats
) implements ImagePolicy {
    @AssertTrue(message = "app.photo.max-size must be positive and fit in a byte array")
    public boolean isMaxSizeValid() {
        return maxSize != null && maxSize.toBytes() > 0 && maxSize.toBytes() < Integer.MAX_VALUE;
    }
}
