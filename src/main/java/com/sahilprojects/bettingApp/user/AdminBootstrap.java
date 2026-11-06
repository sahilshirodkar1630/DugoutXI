package com.sahilprojects.bettingApp.user;

import com.sahilprojects.bettingApp.audit.ActorType;
import com.sahilprojects.bettingApp.audit.AuditAction;
import com.sahilprojects.bettingApp.audit.AuditEntry;
import com.sahilprojects.bettingApp.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/** On startup, makes the accounts listed in app.admin-emails admins (see AdminEmails). Audited as a system change. */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminEmails adminEmails;
    private final UserRepository users;
    private final AuditService auditService;
    private final TransactionTemplate tx;

    public AdminBootstrap(AdminEmails adminEmails, UserRepository users, AuditService auditService,
                          PlatformTransactionManager transactionManager) {
        this.adminEmails = adminEmails;
        this.users = users;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        promoteListed();
    }

    /** Public so it can be tested directly. Returns how many accounts were promoted. */
    public int promoteListed() {
        Integer promoted = tx.execute(status -> {
            int count = 0;
            for (String email : adminEmails.all()) {
                User user = users.findByEmailIgnoreCase(email).orElse(null);
                if (user == null || user.getRole() == Role.ADMIN) continue;
                user.setRole(Role.ADMIN);
                auditService.record(AuditEntry.builder()
                        .action(AuditAction.USER_ROLE_CHANGED)
                        .entityType("USER").entityId(user.getId())
                        .actorType(ActorType.SYSTEM).reason("Listed in app.admin-emails")
                        .before(Map.<String, Object>of("role", Role.USER.name()))
                        .after(Map.<String, Object>of("role", Role.ADMIN.name()))
                        .build());
                log.info("Made {} an admin (listed in app.admin-emails)", email);
                count++;
            }
            return count;
        });
        return promoted == null ? 0 : promoted;
    }
}
