package com.autox.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the demo seed, or its removal, once at startup.
 *
 * <pre>
 *   SPRING_PROFILES_ACTIVE=demo                           seed (once; a no-op afterwards)
 *   SPRING_PROFILES_ACTIVE=demo  AUTOX_DEMO_ACTION=remove  remove everything it seeded
 * </pre>
 *
 * <p>Both can go in {@code .env}. Without the {@code demo} profile this class is not
 * instantiated and neither action is reachable — there is deliberately no HTTP endpoint
 * for either, because "delete these listings" is not something to expose behind a flag.
 */
@Component
@Profile("demo")
class DemoRunner implements ApplicationRunner {

    private final DemoData demoData;
    private final String action;

    DemoRunner(DemoData demoData, @Value("${autox.demo.action:seed}") String action) {
        this.demoData = demoData;
        this.action = action;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        switch (action.trim().toLowerCase()) {
            case "seed" -> demoData.seed();
            case "remove" -> demoData.remove();
            // Profile on, hands off: for a test that wants to call seed and remove itself.
            case "none" -> { }
            default -> throw new IllegalStateException(
                    "AUTOX_DEMO_ACTION must be 'seed', 'remove' or 'none', not '" + action + "'.");
        }
    }
}
