package at.vystorm.winter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WinterAdminMenuTest {
    @Test void speedStaysInRangeAndColumnsFollow() {
        assertArrayEquals(new int[]{4, 24}, WinterAdminMenu.speedSettings(1));
        assertArrayEquals(new int[]{16, 96}, WinterAdminMenu.speedSettings(16));
        assertArrayEquals(new int[]{512, 3072}, WinterAdminMenu.speedSettings(9999));
        assertArrayEquals(new int[]{100, 600}, WinterAdminMenu.speedSettings(99.6f));
    }
}
