package com.autox.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Mail goes to a {@link RecordingEmailSender} the test can read, instead of the log.
 *
 * <p>One annotation for every class that reads its mail, so they all describe the same
 * configuration and share one cached application context. Each used to declare its own nested
 * configuration class, which Spring counts as a different configuration: five classes, five
 * contexts started for the same thing. Imported rather than nested, so {@code @Nested} classes
 * inherit it too.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@TestPropertySource(properties = "autox.mail.provider = recording")
@Import(RecordingMail.Configuration.class)
public @interface RecordingMail {

    @TestConfiguration
    class Configuration {

        @Bean
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }
}
