package com.votivo.user_service.repository;

import com.votivo.user_service.entity.UserProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserProfile u where u.id = :id")
    Optional<UserProfile> findByIdForUpdate(UUID id);

    @Query("select u.id from UserProfile u where u.identityDeletionPending = true")
    List<UUID> findPendingIdentityDeletionIds();

    @Query("select u.id from UserProfile u where u.deletedAt is null")
    List<UUID> findActiveIds();

    Optional<UserProfile> findByIdAndDeletedAtIsNull(UUID id);

    @Query("""
            select u from UserProfile u
            where u.deletedAt is null
              and (:enabled is null or u.enabled = :enabled)
              and (:search = '' or lower(coalesce(u.email, '')) like :search escape '!'
                   or lower(coalesce(u.firstName, '')) like :search escape '!'
                   or lower(coalesce(u.lastName, '')) like :search escape '!')
            """)
    Page<UserProfile> searchActive(String search, Boolean enabled, Pageable pageable);
}
