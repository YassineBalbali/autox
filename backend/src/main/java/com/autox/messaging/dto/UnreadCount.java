package com.autox.messaging.dto;

/** How many of the caller's conversations have a message they have not opened. */
public record UnreadCount(int conversations) {
}
