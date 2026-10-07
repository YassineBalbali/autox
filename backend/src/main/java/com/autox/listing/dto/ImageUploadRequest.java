package com.autox.listing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Asks for a pre-signed upload URL.
 *
 * <p>The declared size is not a courtesy: it is signed into the URL, so storage rejects an
 * upload of any other length. That is what stops a client from announcing a 2 MB photo and
 * then pushing 2 GB through a URL we handed it.
 */
public record ImageUploadRequest(

        @NotBlank @Size(max = 255) String filename,

        @NotBlank @Size(max = 80) String contentType,

        @NotNull @Min(1) Long byteSize) {
}
