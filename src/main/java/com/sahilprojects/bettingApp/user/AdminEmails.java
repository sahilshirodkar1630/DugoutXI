package com.sahilprojects.bettingApp.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How the first admins come about: the email addresses in app.admin-emails (ADMIN_EMAILS, comma separated) are made
 * admins when they register, and on every startup if they already have an account. After that, admins promote or
 * demote others from the Accounts screen, with a reason that goes in the audit log.
 *
 * The role still never comes from a request: this list is server configuration that only whoever runs the app can
 * change.
 */
@Component
public class AdminEmails {

    private final Set<String> emails;

    public AdminEmails(@Value("${app.admin-emails:}") String configured) {
        this.emails = Arrays.stream(configured.split(","))
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .filter(e -> !e.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public static AdminEmails of(String... emails) {
        return new AdminEmails(String.join(",", emails));
    }

    public boolean contains(String email) {
        return email != null && emails.contains(email.trim().toLowerCase(Locale.ROOT));
    }

    public Set<String> all() {
        return emails;
    }
}
