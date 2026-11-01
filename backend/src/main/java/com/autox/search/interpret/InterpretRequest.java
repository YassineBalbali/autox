package com.autox.search.interpret;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A visitor's description of the car they want, in their own words.
 *
 * @param query at most 200 characters: a sentence, not a document. It is the only thing sent
 *              to the language model.
 */
public record InterpretRequest(
        @Schema(example = "SUV diesel automatique moins de 15 000 € en bon état")
        @NotBlank @Size(max = InterpretRequest.MAX_LENGTH)
        String query) {

    public static final int MAX_LENGTH = 200;
}
