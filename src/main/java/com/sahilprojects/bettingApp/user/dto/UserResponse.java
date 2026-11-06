package com.sahilprojects.bettingApp.user.dto;

import com.sahilprojects.bettingApp.user.Role;
import com.sahilprojects.bettingApp.user.User;

/** The user's own profile. Other members never get phone or UPI from this type. */
public record UserResponse(
        Long id,
        String name,
        String displayName,
        String email,
        String phoneNumber,
        String photoUrl,
        String upiId,
        Role role,
        /** True after an admin reset: the app asks for a new password before anything else. */
        boolean mustChangePassword) {

    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getName(), u.getDisplayName(), u.getEmail(),
                u.getPhoneNumber(), u.getPhotoUrl(), u.getUpiId(), u.getRole(),
                u.isMustChangePassword());
    }
}
