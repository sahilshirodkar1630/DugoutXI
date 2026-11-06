package com.sahilprojects.bettingApp.tournament;

import com.sahilprojects.bettingApp.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "teams")
@Getter
@Setter
public class Team extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "short_code", nullable = false, unique = true)
    private String shortCode;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
