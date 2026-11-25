package com.votivo.user_service.entity;

import com.votivo.user_service.image.ImageData;
import com.votivo.user_service.image.ImageFormat;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_photos")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPhoto {
    @Id
    private UUID userId;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] data;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ImageFormat format;

    @Column(nullable = false)
    private Instant updatedAt;

    public UserPhoto(UUID userId, ImageData image, Instant now) {
        this.userId = userId;
        this.data = image.bytes();
        this.format = image.format();
        this.updatedAt = now;
    }

    public ImageData image() {
        return new ImageData(data, format);
    }
}
