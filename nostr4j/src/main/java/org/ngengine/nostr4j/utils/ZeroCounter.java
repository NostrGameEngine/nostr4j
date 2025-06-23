package org.ngengine.nostr4j.utils;

public class ZeroCounter {
    public static int countLeadingZeroes(String hex) {
        int count = 0;

        for (int i = 0; i < hex.length(); i++) {
            int nibble = Character.digit(hex.charAt(i), 16);
            if (nibble == 0) {
                count += 4;
            } else {
                count += Integer.numberOfLeadingZeros(nibble) - 28;
                break;
            }
        }

        return count;
    }
}
