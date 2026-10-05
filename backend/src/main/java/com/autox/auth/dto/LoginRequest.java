package com.autox.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(

        @NotBlank @Email @Size(max = 255)
        String email,

        @NotBlank @Size(max = 128)
        String password,

        /** Optional label for the session list, e.g. "Pixel 8 — AutoX Android". */
        @Size(max = 120)
        String deviceLabel) {
}
