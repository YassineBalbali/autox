package com.autox.config;

import jakarta.validation.MessageInterpolator;
import java.util.Locale;
import org.hibernate.validator.messageinterpolation.ResourceBundleMessageInterpolator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Pins validation messages to English.
 *
 * <p>Bean Validation's built-in constraints resolve their text from the ambient locale,
 * so on a French-locale JVM the API answered with a French "ne doit pas être vide" next
 * to our own English messages. Mixed languages in one response is worse than either
 * language consistently.
 *
 * <p>The contract is that {@code message} is for developers and {@code code} plus
 * {@code field} are what clients render from. End-user text is the client's job, in the
 * user's language — which is the only thing that works once a listing can be read in
 * five countries. {@code Accept-Language} is accepted by clients, not by this API.
 */
@Configuration
public class ValidationConfig {

    /** Replaces Spring Boot's auto-configured validator, which uses the default locale. */
    @Bean
    public LocalValidatorFactoryBean defaultValidator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setMessageInterpolator(new EnglishMessageInterpolator());
        return validator;
    }

    /** Delegates to the standard interpolator but forces the locale on every call. */
    static class EnglishMessageInterpolator implements MessageInterpolator {

        private final MessageInterpolator delegate = new ResourceBundleMessageInterpolator();

        @Override
        public String interpolate(String messageTemplate, Context context) {
            return delegate.interpolate(messageTemplate, context, Locale.ENGLISH);
        }

        @Override
        public String interpolate(String messageTemplate, Context context, Locale locale) {
            return delegate.interpolate(messageTemplate, context, Locale.ENGLISH);
        }
    }
}
