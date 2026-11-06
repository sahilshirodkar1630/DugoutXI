package com.sahilprojects.bettingApp.user;

import com.sahilprojects.bettingApp.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** Stored lower-cased by the service. The database also has a unique index on lower(email). */
    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "upi_id")
    private String upiId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Set when an admin hands out a temporary password: the user must choose their own before doing anything else. */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /** Tokens issued before this are refused, so a new password signs out every older session. */
    @Column(name = "password_changed_at")
    private java.time.Instant passwordChangedAt;

    /** The last "forgot password" request, so repeats within the hour do not flood the admins. */
    @Column(name = "password_reset_requested_at")
    private java.time.Instant passwordResetRequestedAt;
}
