package com.votivo.user_service.entity;

import lombok.*;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import jakarta.persistence.*;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_profiles")
public class UserProfile {
    @Id
    private UUID id;
    private String email;
    private String firstName;
    private String lastName;
    private boolean emailVerified;
    private boolean enabled;

    @Builder.Default
    @ElementCollection
    @Column(name = "role")
    @Getter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    private Set<UserRole> roles = new HashSet<>();

    private Instant createdAt;
    private Instant updatedAt;
    private Instant syncedAt;
    private Instant deletedAt;

    @Version
    private Long version;

    public void synchronize(
            String email,
            String firstName,
            String lastName,
            boolean emailVerified,
            boolean enabled,
            Set<UserRole> roles,
            Instant createdAt,
            Instant now
    ) {

        boolean changed = updatedAt == null || deletedAt != null || !Objects.equals(this.email, email)
                || !Objects.equals(this.firstName, firstName) || !Objects.equals(this.lastName, lastName)
                || this.emailVerified != emailVerified || this.enabled != enabled
                || !this.roles.equals(roles) || !Objects.equals(this.createdAt, createdAt);

        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.emailVerified = emailVerified;
        this.enabled = enabled;
        this.roles = new HashSet<>(roles);
        this.createdAt = createdAt;
        this.deletedAt = null;
        this.syncedAt = now;

        if (changed) this.updatedAt = now;
    }

    public void softDelete(Instant now) {
        if (deletedAt == null) {
            email = null;
            firstName = null;
            lastName = null;
            emailVerified = false;
            enabled = false;
            roles = new HashSet<>();
            deletedAt = now;
            updatedAt = now;
        }

        syncedAt = now;
    }

    public Set<UserRole> getRoles() {
        return Set.copyOf(roles);
    }

}
