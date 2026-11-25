package com.votivo.user_service.repository;

import com.votivo.user_service.entity.UserPhoto;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPhotoRepository extends JpaRepository<UserPhoto, UUID> {
}
