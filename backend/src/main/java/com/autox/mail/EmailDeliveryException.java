package com.autox.mail;

/**
 * The provider did not take an email.
 *
 * <p>The message is stored in {@code outbox_email.last_error} and logged, so it must never carry
 * the body, a token or the API key: a sender builds it from the status and the provider's error,
 * not from the request.
 */
public class EmailDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmailDeliveryException(String safeDetail) {
        super(safeDetail);
    }
}
