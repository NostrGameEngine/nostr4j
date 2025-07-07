package org.ngengine.nostrads.protocol.types;

import jakarta.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;


public enum AdSize {
    // Standard horizontal
    HORIZONTAL_480x60("480x60", AdAspectRatio.RATIO_8_1),
    HORIZONTAL_720x90("720x90", AdAspectRatio.RATIO_8_1),
    HORIZONTAL_300x50("300x50", AdAspectRatio.RATIO_6_1),

    // Standard vertical
    VERTICAL_300x600("300x600", AdAspectRatio.RATIO_1_2),
    VERTICAL_160x600("160x600", AdAspectRatio.RATIO_1_3),
    VERTICAL_120x600("120x600", AdAspectRatio.RATIO_1_5),

    // Standard rectangles
    RECTANGLE_250x250("250x250", AdAspectRatio.RATIO_1_1),
    RECTANGLE_200x200("200x200", AdAspectRatio.RATIO_1_1),

    // Immersive
    IMMERSIVE_2048x2048("2048x2048", AdAspectRatio.RATIO_1_1),
    IMMERSIVE_1024x1024("1024x1024", AdAspectRatio.RATIO_1_1),
        
    IMMERSIVE_1024x512("1024x512", AdAspectRatio.RATIO_2_1),
    IMMERSIVE_512x1024("512x1024", AdAspectRatio.RATIO_1_2),

    IMMERSIVE_1280x720("1280x720", AdAspectRatio.RATIO_16_9),
    IMMERSIVE_1920x1080("1920x1080", AdAspectRatio.RATIO_16_9),
    
    IMMERSIVE_512x128("512x128", AdAspectRatio.RATIO_4_1),
    IMMERSIVE_1920x120("1920x120", AdAspectRatio.RATIO_16_1);

    private final String dimensions;
    private final AdAspectRatio aspectRatio;

    AdSize(String dimensions, AdAspectRatio aspectRatio) {
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
    public AdAspectRatio getAspectRatio() {
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
    public static AdSize fromString(String dimensions) {
        for (AdSize slot : values()) {
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
    public static AdSize getMinSlotByAspect(AdAspectRatio aspectRatio) {
        return Arrays.stream(values())
                .filter(slot -> slot.aspectRatio == aspectRatio)
                .min(Comparator.comparingInt(slot -> slot.getWidth() * slot.getHeight()))
                .orElse(null);
    }

    /**
     * Find the smallest slot (by area) that matches the given aspect ratio string
     */
    @Nullable
    public static AdSize getMinSlotByAspect(String aspectRatioString) {
        AdAspectRatio aspectRatio = AdAspectRatio.fromString(aspectRatioString);
        if (aspectRatio == null) {
            return null;
        }
        return getMinSlotByAspect(aspectRatio);
    }

    /**
     * Find the largest slot (by area) that matches the given aspect ratio
     */
    @Nullable
    public static AdSize getMaxSlotByAspect(AdAspectRatio aspectRatio) {
        return Arrays.stream(values())
                .filter(slot -> slot.aspectRatio == aspectRatio)
                .max(Comparator.comparingInt(slot -> slot.getWidth() * slot.getHeight()))
                .orElse(null);
    }

    /**
     * Find the largest slot (by area) that matches the given aspect ratio string
     */
    @Nullable
    public static AdSize getMaxSlotByAspect(String aspectRatioString) {
        AdAspectRatio aspectRatio = AdAspectRatio.fromString(aspectRatioString);
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