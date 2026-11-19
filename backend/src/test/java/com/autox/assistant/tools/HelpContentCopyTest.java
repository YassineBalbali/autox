package com.autox.assistant.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The API's copy of the help pages' text is the site's. The image is built from backend/ alone and
 * cannot read frontend/, so the file is copied; this fails when one changes without the other.
 */
class HelpContentCopyTest {

    private static final Path SOURCE = Path.of("..", "frontend", "content", "help.fr.json");
    private static final Path COPY = Path.of("src", "main", "resources", HelpIndex.RESOURCE);

    @Test
    @DisplayName("is identical to frontend/content/help.fr.json")
    void identical() throws Exception {
        assertThat(text(COPY))
                .as("Copy frontend/content/help.fr.json to backend/src/main/resources/" + HelpIndex.RESOURCE)
                .isEqualTo(text(SOURCE));
    }

    /** Line endings aside, which Git may change on checkout. */
    private static String text(Path path) throws Exception {
        return Files.readString(path).replace("\r\n", "\n");
    }
}
