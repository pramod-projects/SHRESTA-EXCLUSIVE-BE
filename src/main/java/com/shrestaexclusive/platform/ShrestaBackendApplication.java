package com.shrestaexclusive.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class ShrestaBackendApplication {

    private final Environment env;

    public ShrestaBackendApplication(Environment env) {
        this.env = env;
    }

    public static void main(String[] args) {
        SpringApplication.run(ShrestaBackendApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String port    = env.getProperty("server.port", "8080");
        String profile = String.join(", ", env.getActiveProfiles());
        if (profile.isEmpty()) profile = "default";

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────┐");
        System.out.println("│           SHRESTA BACKEND  ─  READY             │");
        System.out.println("├─────────────────────────────────────────────┤");
        System.out.printf( "│  Profile  : %-32s│%n", profile);
        System.out.printf( "│  Local    : http://localhost:%-16s│%n", port);
        System.out.printf( "│  Health   : http://localhost:%s/actuator/health%n", port);
        System.out.printf( "│  Swagger  : http://localhost:%s/swagger-ui/index.html%n", port);
        System.out.println("└─────────────────────────────────────────────┘");
        System.out.println();
    }
}
