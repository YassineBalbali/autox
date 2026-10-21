package com.autox.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link S3ObjectStorage} against a real S3 implementation, MinIO. */
class S3ObjectStorageIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectStorage storage;

    @Test
    @DisplayName("deletes more keys than S3 takes in one request")
    void deletesMoreThanOneBatch() {
        // Found removing the 120-listing demo seed: 1 440 keys in one request, which S3
        // refuses whole, so nothing at all was deleted.
        String prefix = "test/delete-all-" + UUID.randomUUID() + "/";
        List<String> keys = IntStream.range(0, S3ObjectStorage.DELETE_BATCH + 1)
                .mapToObj(i -> prefix + i).toList();
        keys.forEach(key -> storage.write(key, new byte[] {1}, "application/octet-stream"));

        storage.deleteAll(keys);

        assertThat(storage.stat(keys.getFirst())).isEmpty();
        assertThat(storage.stat(keys.getLast())).isEmpty();
    }

    @Test
    @DisplayName("deleting no keys sends nothing and is not an error")
    void deletesNothing() {
        storage.deleteAll(List.of());
    }
}
