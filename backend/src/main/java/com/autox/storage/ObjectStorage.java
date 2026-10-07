package com.autox.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * Object storage, as the rest of the application needs it.
 *
 * <p>Deliberately narrow: pre-sign an upload, ask what actually arrived, read it, write a
 * derivative, delete. Nothing here mentions a vendor, a bucket layout or an SDK type, so
 * the implementation can be swapped without touching a caller.
 *
 * <p>Image bytes never pass through the API on the way in — the client PUTs straight to the
 * URL from {@link #presignUpload}. The server reads an object only to derive variants from
 * it, which is a server-to-storage transfer, not a client upload.
 */
public interface ObjectStorage {

    /**
     * A URL the client may PUT one object to, once, before it expires.
     *
     * <p>The content type and length are bound into the signature, so a client that
     * declared a 2 MB JPEG cannot then upload a 2 GB video: storage rejects the mismatch
     * before a byte of it is ours.
     */
    PresignedUpload presignUpload(String key, String contentType, long contentLength, Duration ttl);

    /** What storage actually holds at this key, if anything. Used to confirm an upload. */
    Optional<StoredObject> stat(String key);

    /** Reads an object fully. Only used for images small enough to have been accepted. */
    byte[] read(String key);

    /** Writes a derived object, such as a resized variant. */
    void write(String key, byte[] content, String contentType);

    /** Deleting an absent key is not an error: cleanup has to be repeatable. */
    void delete(String key);

    void deleteAll(Collection<String> keys);

    /**
     * A URL a browser can load the object from.
     *
     * <p>Either a permanent CDN URL or a time-limited signed one, depending on whether the
     * bucket is public — which is why callers must treat this as opaque and not cache it
     * beyond the response they put it in.
     */
    String readUrl(String key);

    /** Ensures the target container exists. A no-op against a provisioned bucket. */
    void ensureReady();

    record PresignedUpload(String url, String method, java.util.Map<String, String> requiredHeaders,
                           Instant expiresAt, long maxBytes) {
    }

    record StoredObject(String key, long byteSize, String contentType) {
    }
}
