package com.votivo.user_service.service;

import com.votivo.user_service.entity.UserPhoto;
import com.votivo.user_service.entity.UserProfile;
import com.votivo.user_service.image.ImageData;
import com.votivo.user_service.repository.UserPhotoRepository;
import com.votivo.user_service.repository.UserProfileRepository;
import com.votivo.user_service.service.exception.PhotoNotFoundException;
import com.votivo.user_service.service.exception.ProfileAccessDeniedException;
import com.votivo.user_service.service.exception.UserNotFoundException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local transactions only; the profile row serializes photo writes and account deletion. */
@Service
@RequiredArgsConstructor
public class PhotoStore {
    private final UserProfileRepository profiles;
    private final UserPhotoRepository photos;

    @Transactional(readOnly = true)
    public ImageData get(UUID id) {
        requireActive(profiles.findById(id).orElseThrow(UserNotFoundException::new));
        return photos.findById(id).orElseThrow(PhotoNotFoundException::new).image();
    }

    @Transactional
    public void replace(UUID id, ImageData image) {
        requireActive(profiles.findByIdForUpdate(id).orElseThrow(UserNotFoundException::new));
        photos.save(new UserPhoto(id, image, Instant.now()));
    }

    @Transactional
    public void delete(UUID id) {
        requireActive(profiles.findByIdForUpdate(id).orElseThrow(UserNotFoundException::new));
        photos.deleteById(id);
    }

    private void requireActive(UserProfile profile) {
        if (profile.getDeletedAt() != null) throw new UserNotFoundException();
        if (!profile.isEnabled()) throw new ProfileAccessDeniedException();
    }
}
