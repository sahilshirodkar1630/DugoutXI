package com.sahilprojects.bettingApp.tournament;

import com.sahilprojects.bettingApp.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "tournaments")
@Getter
@Setter
public class Tournament extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Maps the tournament_teams link table. No entity of its own. */
    @ManyToMany
    @JoinTable(name = "tournament_teams",
            joinColumns = @JoinColumn(name = "tournament_id"),
            inverseJoinColumns = @JoinColumn(name = "team_id"))
    private Set<Team> teams = new HashSet<>();
}
