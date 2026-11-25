package com.votivo.user_service.service;

import com.votivo.user_service.config.PhotoProperties;
import com.votivo.user_service.image.ImageData;
import com.votivo.user_service.image.validation.ImageValidator;
import com.votivo.user_service.image.validation.exception.ImageTooLargeException;
import com.votivo.user_service.image.validation.exception.InvalidImageException;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class PhotoService {
    private final ProfileService profiles;
    private final PhotoStore store;
    private final ImageValidator validator;
    private final PhotoProperties properties;

    public ImageData getOwn(UUID id) {
        profiles.getOwn(id);
        return store.get(id);
    }

    public void replaceOwn(UUID id, MultipartFile file) {
        profiles.getOwn(id);
        long max = properties.maxSize().toBytes();
        if (file.getSize() > max) throw new ImageTooLargeException(file.getSize(), max);
        // Bound the read as well as checking multipart metadata.
        try (var input = file.getInputStream()) {
            byte[] data = input.readNBytes((int) max + 1);
            ImageData image = validator.validate(data);
            store.replace(id, image);
        } catch (IOException exception) {
            throw new InvalidImageException(exception);
        }
    }

    public void deleteOwn(UUID id) {
        profiles.getOwn(id);
        store.delete(id);
    }
}
