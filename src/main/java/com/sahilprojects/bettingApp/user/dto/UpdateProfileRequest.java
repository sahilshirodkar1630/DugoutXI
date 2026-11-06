package com.sahilprojects.bettingApp.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The only fields a user can change about themselves. There is no role or active flag here on purpose,
 * so a profile update can never change access. A field left out (null) stays as it is.
 */
public record UpdateProfileRequest(
        @Size(max = 100) String name,
        @Size(max = 100) String displayName,
        @Size(max = 20) String phoneNumber,
        @Pattern(regexp = "^https?://\\S+$", message = "must be an http or https URL") @Size(max = 500) String photoUrl,
        @Pattern(regexp = "^[\\w.\\-]{2,256}@[a-zA-Z]{2,64}$", message = "must look like name@bank") String upiId) {
}
