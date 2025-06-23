package org.ngengine.nostr4j.ads;

import jakarta.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;


public enum SdanSize {
    // Standard horizontal
    HORIZONTAL_468x60("468x60", SdanAspectRatio.RATIO_7_8_1),
    HORIZONTAL_728x90("728x90", SdanAspectRatio.RATIO_8_1_1),
    HORIZONTAL_320x50("320x50", SdanAspectRatio.RATIO_6_4_1),

    // Standard vertical
    VERTICAL_300x600("300x600", SdanAspectRatio.RATIO_1_2),
    VERTICAL_160x600("160x600", SdanAspectRatio.RATIO_1_3_75),
    VERTICAL_120x600("120x600", SdanAspectRatio.RATIO_1_5),

    // Standard rectangles
    RECTANGLE_336x280("336x280", SdanAspectRatio.RATIO_1_2_1),
    RECTANGLE_300x250("300x250", SdanAspectRatio.RATIO_1_2_1),
    RECTANGLE_250x250("250x250", SdanAspectRatio.RATIO_1_1),
    RECTANGLE_200x200("200x200", SdanAspectRatio.RATIO_1_1),

    // Immersive
    IMMERSIVE_1024x512("1024x512", SdanAspectRatio.RATIO_2_1),
    IMMERSIVE_512x1024("512x1024", SdanAspectRatio.RATIO_1_2),
    IMMERSIVE_1024x1024("1024x1024", SdanAspectRatio.RATIO_1_1),
    IMMERSIVE_1280x720("1280x720", SdanAspectRatio.RATIO_16_9),
    IMMERSIVE_1920x1080("1920x1080", SdanAspectRatio.RATIO_16_9),
    IMMERSIVE_2048x2048("2048x2048", SdanAspectRatio.RATIO_1_1),
    IMMERSIVE_512x128("512x128", SdanAspectRatio.RATIO_4_1),
    IMMERSIVE_1920x120("1920x120", SdanAspectRatio.RATIO_16_1);

    private final String dimensions;
    private final SdanAspectRatio aspectRatio;

    SdanSize(String dimensions, SdanAspectRatio aspectRatio) {
        this.dimensions = dimensions;
        this.aspectRatio = aspectRatio;
    }

    /**
     * Get dimensions in WxH format
     */
    public String getDimensions() {
        return dimensions;
    }

    /**
     * Get aspect ratio
     */
    public SdanAspectRatio getAspectRatio() {
        return aspectRatio;
    }

    /**
     * Get aspect ratio in W:H format (for compatibility)
     */
    public String getAspectRatioString() {
        return aspectRatio.getStringValue();
    }

    /**
     * Get width from dimensions
     */
    public int getWidth() {
        String[] parts = dimensions.split("x");
        return Integer.parseInt(parts[0]);
    }

    /**
     * Get height from dimensions
     */
    public int getHeight() {
        String[] parts = dimensions.split("x");
        return Integer.parseInt(parts[1]);
    }

    /**
     * Get the calculated aspect ratio as a float
     */
    public float getAspectRatioValue() {
        return aspectRatio.getFloatValue();
    }

    /**
     * Find a slot by dimensions string (e.g., "300x250")
     */
    @Nullable
    public static SdanSize fromString(String dimensions) {
        for (SdanSize slot : values()) {
            if (slot.dimensions.equalsIgnoreCase(dimensions)) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Find the smallest slot (by area) that matches the given aspect ratio
     */
    @Nullable
    public static SdanSize getMinSlotByAspect(SdanAspectRatio aspectRatio) {
        return Arrays.stream(values())
                .filter(slot -> slot.aspectRatio == aspectRatio)
                .min(Comparator.comparingInt(slot -> slot.getWidth() * slot.getHeight()))
                .orElse(null);
    }

    /**
     * Find the smallest slot (by area) that matches the given aspect ratio string
     */
    @Nullable
    public static SdanSize getMinSlotByAspect(String aspectRatioString) {
        SdanAspectRatio aspectRatio = SdanAspectRatio.fromString(aspectRatioString);
        if (aspectRatio == null) {
            return null;
        }
        return getMinSlotByAspect(aspectRatio);
    }

    /**
     * Find the largest slot (by area) that matches the given aspect ratio
     */
    @Nullable
    public static SdanSize getMaxSlotByAspect(SdanAspectRatio aspectRatio) {
        return Arrays.stream(values())
                .filter(slot -> slot.aspectRatio == aspectRatio)
                .max(Comparator.comparingInt(slot -> slot.getWidth() * slot.getHeight()))
                .orElse(null);
    }

    /**
     * Find the largest slot (by area) that matches the given aspect ratio string
     */
    @Nullable
    public static SdanSize getMaxSlotByAspect(String aspectRatioString) {
        SdanAspectRatio aspectRatio = SdanAspectRatio.fromString(aspectRatioString);
        if (aspectRatio == null) {
            return null;
        }
        return getMaxSlotByAspect(aspectRatio);
    }

    /**
     * Get a string representation
     */
    @Override
    public String toString() {
        return dimensions;
    }
}