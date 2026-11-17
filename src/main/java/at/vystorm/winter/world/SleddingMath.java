package at.vystorm.winter.world;

/** Speed rule of {@link Sledding}, kept separate so it can be tested without a server. */
public final class SleddingMath {
    private SleddingMath() {}

    /**
     * New horizontal boat speed in blocks per tick on snow: a gentle push each tick, more when going down, never above
     * {@code max} and never slower than now. Below 0.02 (standing still) nothing changes.
     */
    public static double speed(double horizontal, double multiplier, boolean downhill, double max) {
        if (horizontal < 0.02) return horizontal;
        double push = horizontal * (1 + (multiplier - 1) * 0.08) + (downhill ? 0.035 : 0);
        return Math.max(horizontal, Math.min(max, push));
    }
}
