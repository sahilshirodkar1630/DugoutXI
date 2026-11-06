package com.sahilprojects.bettingApp.user;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    List<User> findByRoleAndActiveTrue(Role role);

    boolean existsByEmailIgnoreCase(String email);

    /** The admin user list, newest first. */
    List<User> findAllByOrderByIdDesc(Pageable pageable);

    /**
     * The admin user list narrowed by a search across name, display name and email, ignoring case. The term is
     * never null here: a nullable parameter in a JPQL "is null" test cannot be typed by PostgreSQL.
     */
    @Query("select u from User u where lower(u.name) like lower(concat('%', :search, '%')) "
            + "or lower(u.displayName) like lower(concat('%', :search, '%')) "
            + "or lower(u.email) like lower(concat('%', :search, '%')) "
            + "order by u.id desc")
    List<User> search(@Param("search") String search, Pageable pageable);
}
