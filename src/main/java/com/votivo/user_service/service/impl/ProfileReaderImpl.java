package com.votivo.user_service.service.impl;

import com.votivo.user_service.dto.response.UserPageResponse;
import com.votivo.user_service.dto.response.UserResponse;
import com.votivo.user_service.entity.UserProfile;
import com.votivo.user_service.repository.UserProfileRepository;
import com.votivo.user_service.service.ProfileReader;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileReaderImpl implements ProfileReader {
    private final UserProfileRepository repository;

    @Override
    public Optional<UserResponse> find(UUID id) {
        return repository.findByIdAndDeletedAtIsNull(id).map(this::response);
    }

    @Override
    public boolean exists(UUID id) {
        return repository.existsById(id);
    }

    @Override
    public UserPageResponse list(
            String search,
            Boolean enabled,
            int page,
            int size
    ) {
        var result = repository.searchActive(searchPattern(search), enabled,
                PageRequest.of(page, size, Sort.by("id")));
        return UserPageResponse.builder()
                .items(result.getContent().stream().map(this::response).toList())
                .page(page)
                .size(size)
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .build();
    }

    private String searchPattern(String search) {
        String term = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        if (term.isEmpty()) return "";
        return "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private UserResponse response(UserProfile profile) {
        return UserResponse.builder()
                .id(profile.getId())
                .email(profile.getEmail())
                .firstName(profile.getFirstName())
                .lastName(profile.getLastName())
                .emailVerified(profile.isEmailVerified())
                .enabled(profile.isEnabled())
                .roles(profile.getRoles())
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .build();
    }
}
