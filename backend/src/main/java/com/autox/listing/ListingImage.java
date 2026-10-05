package com.autox.listing;

import com.autox.common.CreatedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One photo of a listing — the original upload, with its renditions in
 * {@link ListingImageVariant}.
 *
 * <p>Lifecycle: the row is inserted with {@code PENDING} when a pre-signed upload
 * URL is issued, then flipped to {@code READY} once storage confirms the object.
 * Bytes never pass through the API. Rows still {@code PENDING} past
 * {@link #uploadExpiresAt} are swept, together with any orphaned object.
 */
@Entity
@Table(name = "listing_image")
public class ListingImage extends CreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id", nullable = false, updatable = false)
    private Listing listing;

    /** Gallery order, 0-based. Position 0 is the cover photo. */
    @Column(name = "position", nullable = false)
    private short position;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ImageStatus status = ImageStatus.PENDING;

    /** Object key only — the bucket and CDN host are configuration, not data. */
    @Column(name = "storage_key", length = 512, nullable = false)
    private String storageKey;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "content_type", length = 80)
    private String contentType;

    @Column(name = "byte_size")
    private Long byteSize;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    /** When the pre-signed URL stops being usable. */
    @Column(name = "upload_expires_at")
    private Instant uploadExpiresAt;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @OneToMany(mappedBy = "image", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<ListingImageVariant> variants = new LinkedHashSet<>();

    protected ListingImage() {
    }

    public Listing getListing() {
        return listing;
    }

    public void setListing(Listing listing) {
        this.listing = listing;
    }

    public short getPosition() {
        return position;
    }

    public void setPosition(short position) {
        this.position = position;
    }

    public ImageStatus getStatus() {
        return status;
    }

    public void setStatus(ImageStatus status) {
        this.status = status;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getByteSize() {
        return byteSize;
    }

    public void setByteSize(Long byteSize) {
        this.byteSize = byteSize;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public void setChecksumSha256(String checksumSha256) {
        this.checksumSha256 = checksumSha256;
    }

    public Instant getUploadExpiresAt() {
        return uploadExpiresAt;
    }

    public void setUploadExpiresAt(Instant uploadExpiresAt) {
        this.uploadExpiresAt = uploadExpiresAt;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public Set<ListingImageVariant> getVariants() {
        return variants;
    }
}
