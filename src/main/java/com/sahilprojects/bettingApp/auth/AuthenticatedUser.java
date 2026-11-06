package com.sahilprojects.bettingApp.auth;

import com.sahilprojects.bettingApp.user.Role;

/** The caller, as seen by services. Built from the database on every request, so a disabled user is cut off at once. */
public record AuthenticatedUser(Long id, String email, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
