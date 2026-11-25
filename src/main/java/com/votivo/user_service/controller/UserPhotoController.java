package com.votivo.user_service.controller;

import com.votivo.user_service.service.PhotoService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/users/me/photo")
@RequiredArgsConstructor
public class UserPhotoController {
    private final PhotoService photos;

    @GetMapping
    public ResponseEntity<byte[]> getOwn(@AuthenticationPrincipal Jwt jwt) {
        var image = photos.getOwn(UUID.fromString(jwt.getSubject()));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.format().getContentType()))
                .contentLength(image.size())
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replaceOwn(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file) {
        photos.replaceOwn(UUID.fromString(jwt.getSubject()), file);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOwn(@AuthenticationPrincipal Jwt jwt) {
        photos.deleteOwn(UUID.fromString(jwt.getSubject()));
    }
}
