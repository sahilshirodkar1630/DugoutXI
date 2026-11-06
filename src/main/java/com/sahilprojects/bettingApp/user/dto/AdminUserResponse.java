package com.sahilprojects.bettingApp.user.dto;

import com.sahilprojects.bettingApp.user.Role;
import com.sahilprojects.bettingApp.user.User;

/**
 * A user as an admin sees them in the user list. Unlike UserResponse this carries the active flag, because
 * switching accounts on and off is the main thing the admin screen is for. Contact details are left out: an admin
 * managing accounts has no need for somebody's UPI id.
 */
public record AdminUserResponse(
        Long id,
        String name,
        String displayName,
        String email,
        Role role,
        boolean active) {

    public static AdminUserResponse from(User u) {
        return new AdminUserResponse(u.getId(), u.getName(), u.getDisplayName(), u.getEmail(),
                u.getRole(), u.isActive());
    }
}
