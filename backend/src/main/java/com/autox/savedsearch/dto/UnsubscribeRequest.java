package com.autox.savedsearch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param token the value from the e-mail's link */
public record UnsubscribeRequest(@NotBlank @Size(max = 200) String token) {
}
