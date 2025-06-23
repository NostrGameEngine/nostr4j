package org.ngengine.nostr4j.ads;

import jakarta.annotation.Nullable;

/**
 * Standard aspect ratios for SDAN content
 */
public enum SdanAspectRatio {
    RATIO_1_1("1:1", 1.0f),
    RATIO_1_2("1:2", 0.5f),
    RATIO_1_3_75("1:3.75", 1.0f / 3.75f),
    RATIO_1_5("1:5", 0.2f),
    RATIO_1_2_1("1.2:1", 1.2f),
    RATIO_2_1("2:1", 2.0f),
    RATIO_4_1("4:1", 4.0f),
    RATIO_6_4_1("6.4:1", 6.4f),
    RATIO_7_8_1("7.8:1", 7.8f),
    RATIO_8_1_1("8.1:1", 8.1f),
    RATIO_16_1("16:1", 16.0f),
    RATIO_16_9("16:9", 16.0f / 9.0f);

    private final String stringValue;
    private final float floatValue;

    SdanAspectRatio(String stringValue, float floatValue) {
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
    public static SdanAspectRatio fromString(String aspectRatio) {
        for (SdanAspectRatio ratio : values()) {
            if (ratio.stringValue.equalsIgnoreCase(aspectRatio)) {
                return ratio;
            }
        }
        return null;
    }

    /**
     * Find the closest aspect ratio
     */
    public static SdanAspectRatio findClosest(float ratio) {
        SdanAspectRatio closest = RATIO_1_1;
        float minDiff = Math.abs(closest.floatValue - ratio);

        for (SdanAspectRatio aspectRatio : values()) {
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
    public static SdanAspectRatio fromDimensions(int width, int height) {
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