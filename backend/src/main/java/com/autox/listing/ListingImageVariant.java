package com.autox.listing;

import com.autox.common.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A stored rendition of an uploaded photo. Dimensions are recorded so the API can
 * emit width/height and a srcset without touching storage — mobile clients pick the
 * variant that fits their screen instead of downloading the original.
 */
@Entity
@Table(name = "listing_image_variant")
public class ListingImageVariant extends CreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "image_id", nullable = false, updatable = false)
    private ListingImage image;

    @Enumerated(EnumType.STRING)
    @Column(name = "variant", length = 16, nullable = false)
    private ImageVariantType variant;

    @Column(name = "storage_key", length = 512, nullable = false)
    private String storageKey;

    @Column(name = "width", nullable = false)
    private int width;

    @Column(name = "height", nullable = false)
    private int height;

    @Column(name = "byte_size")
    private Long byteSize;

    protected ListingImageVariant() {
    }

    public ListingImage getImage() {
        return image;
    }

    public void setImage(ListingImage image) {
        this.image = image;
    }

    public ImageVariantType getVariant() {
        return variant;
    }

    public void setVariant(ImageVariantType variant) {
        this.variant = variant;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public Long getByteSize() {
        return byteSize;
    }

    public void setByteSize(Long byteSize) {
        this.byteSize = byteSize;
    }
}
