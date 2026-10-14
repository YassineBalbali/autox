package com.autox.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Confirms storage is usable once the application is up, and creates the bucket in local
 * development.
 *
 * <p>Checked after startup rather than during bean creation, and logged rather than thrown:
 * authentication, listings and search do not depend on storage, so a storage outage should
 * degrade image upload, not prevent the API from serving at all.
 *
 * <p>Then again on a schedule (autox.scheduling.storage-check-interval, twelve hours by
 * default). That reports a storage outage that begins after startup. It is also what keeps
 * a free Supabase project awake: Supabase pauses one after a week without database
 * activity, the storage server records every bucket and object in that database, and a
 * demo can easily go a week without a visitor viewing a photo. Paused, every photo and
 * every upload fails until someone resumes the project by hand.
 */
@Component
public class StorageReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(StorageReadinessCheck.class);

    private final ObjectStorage storage;

    public StorageReadinessCheck(ObjectStorage storage) {
        this.storage = storage;
    }

    @Scheduled(fixedDelayString = "${autox.scheduling.storage-check-interval}",
            initialDelayString = "${autox.scheduling.storage-check-interval}")
    public void recheck() {
        try {
            storage.ensureReady();
            log.debug("Object storage is still ready");
        } catch (RuntimeException e) {
            log.error("Object storage is not usable; image upload and photos will fail until this is fixed: {}",
                    e.getMessage());
        }
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
