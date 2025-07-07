package org.ngengine.nostrads.protocol.types;

import jakarta.annotation.Nullable;

/**
 * Standard aspect ratios for Ad content
 */
public enum AdAspectRatio {
    RATIO_8_1("8:1", 8f/1f),
    RATIO_6_1("6:1", 6f/1f),
    RATIO_1_2("1:2", 1/2f),
    RATIO_1_3("1:3", 1f/3f),
    RATIO_1_5("1:5", 1f / 5f),
    RATIO_1_1("1:1", 1f / 1f),
    RATIO_2_1("2:1", 2f / 1f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_4_1("4:1", 4f / 1f),
    RATIO_16_1("16:1", 16f / 1f);

    private final String stringValue;
    private final float floatValue;

    AdAspectRatio(String stringValue, float floatValue) {
        this.stringValue = stringValue;
        this.floatValue = floatValue;
    }

    /**
     * Get aspect ratio in W:H format
     */
    public String getStringValue() {
        return stringValue;
    }

    /**
     * Get the calculated aspect ratio as a float
     */
    public float getFloatValue() {
        return floatValue;
    }

    /**
     * Find aspect ratio from string (e.g. "16:9")
     */
    @Nullable
    public static AdAspectRatio fromString(String aspectRatio) {
        for (AdAspectRatio ratio : values()) {
            if (ratio.stringValue.equalsIgnoreCase(aspectRatio)) {
                return ratio;
            }
        }
        return null;
    }

    /**
     * Find the closest aspect ratio
     */
    public static AdAspectRatio findClosest(float ratio) {
        AdAspectRatio closest = RATIO_1_1;
        float minDiff = Math.abs(closest.floatValue - ratio);

        for (AdAspectRatio aspectRatio : values()) {
            float diff = Math.abs(aspectRatio.floatValue - ratio);
            if (diff < minDiff) {
                minDiff = diff;
                closest = aspectRatio;
            }
        }
        return closest;
    }

    /**
     * Calculate aspect ratio from width and height
     */
    public static AdAspectRatio fromDimensions(int width, int height) {
        if (height == 0) {
            throw new IllegalArgumentException("Height cannot be zero");
        }
        return findClosest((float) width / height);
    }

    @Override
    public String toString() {
        return stringValue;
    }
}