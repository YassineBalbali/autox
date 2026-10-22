package com.autox.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much one account may write. Both caps count per account, in fixed windows.
 *
 * @param enabled              off nowhere by default: unlike the per-address throttles, a
 *                             per-account cap does not couple tests that each register
 *                             their own users, so the suite runs with it on
 * @param messagesPerWindow    messages one account may send per {@code messageWindow}
 * @param messageWindow        the window for {@code messagesPerWindow}
 * @param conversationsPerWindow conversations one account may start per {@code conversationWindow}
 * @param conversationWindow   the window for {@code conversationsPerWindow}
 */
@ConfigurationProperties(prefix = "autox.messaging.throttle")
public record MessagingThrottleProperties(
        boolean enabled,
        int messagesPerWindow,
        Duration messageWindow,
        int conversationsPerWindow,
        Duration conversationWindow) {

    public MessagingThrottleProperties {
        if (messagesPerWindow <= 0) {
            messagesPerWindow = 20;
        }
        if (messageWindow == null || messageWindow.isNegative() || messageWindow.isZero()) {
            messageWindow = Duration.ofMinutes(10);
        }
        if (conversationsPerWindow <= 0) {
            conversationsPerWindow = 10;
        }
        if (conversationWindow == null || conversationWindow.isNegative() || conversationWindow.isZero()) {
            conversationWindow = Duration.ofDays(1);
        }
    }
}
