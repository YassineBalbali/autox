package com.autox.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.mail.EmailSender;
import com.autox.mail.LoggingEmailSender;
import com.autox.search.interpret.DisabledSearchInterpreter;
import com.autox.search.interpret.SearchInterpreter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The suite talks to no outside service. Worth a test because the way to get it wrong is silent:
 * a pin in application-test.yml looks right and loses to a developer's .env. Run with
 * {@code AUTOX_MAIL_PROVIDER} and {@code AUTOX_AI_PROVIDER} set to anything, this still passes.
 */
class OutsideServicesPinnedIntegrationTest extends AbstractIntegrationTest {

    @Autowired EmailSender mail;
    @Autowired SearchInterpreter interpreter;

    @Test
    @DisplayName("mail goes to the log and natural-language search is off, whatever .env says")
    void pinned() {
        assertThat(mail).isInstanceOf(LoggingEmailSender.class);
        assertThat(interpreter).isInstanceOf(DisabledSearchInterpreter.class);
    }
}
