package com.sahilprojects.bettingApp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BettingAppApplication {

    /*
     * The PostgreSQL driver sends the JVM's default time zone when it connects. On machines whose zone is an old
     * alias such as "Asia/Calcutta", newer PostgreSQL images refuse the connection outright. The app works in UTC
     * instants throughout, so the JVM is pinned to UTC before anything opens a connection. A static block rather
     * than main() so it also applies when tests start the context without going through main().
     */
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(BettingAppApplication.class, args);
    }
}
