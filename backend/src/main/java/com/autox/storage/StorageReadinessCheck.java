package com.autox.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Confirms storage is usable once the application is up, and creates the bucket in local
 * development.
 *
 * <p>Checked after startup rather than during bean creation, and logged rather than thrown:
 * authentication, listings and search do not depend on storage, so a storage outage should
 * degrade image upload, not prevent the API from serving at all.
 */
@Component
public class StorageReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(StorageReadinessCheck.class);

    private final ObjectStorage storage;

    public StorageReadinessCheck(ObjectStorage storage) {
        this.storage = storage;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        try {
            storage.ensureReady();
            log.info("Object storage is ready");
        } catch (RuntimeException e) {
            log.error("Object storage is not usable; image upload will fail until this is fixed: {}",
                    e.getMessage());
        }
    }
}
