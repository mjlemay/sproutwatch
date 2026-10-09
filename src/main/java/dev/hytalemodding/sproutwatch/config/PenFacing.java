package dev.hytalemodding.sproutwatch.config;

import java.util.Locale;

/** Horizontal direction the pen camera looks (see forSeat for how a seat picks one). */
public enum PenFacing {
    NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0);

    public final int dx;
    public final int dz;

    PenFacing(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    /** Config form: lowercase name. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Case-insensitive parse; anything unrecognized (including null) is NORTH. */
    public static PenFacing parse(String s) {
        if (s == null) return NORTH;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return NORTH;
        }
    }

    /** The facing whose axis dominates the (dx, dz) delta; a tie prefers the z axis. */
    public static PenFacing toward(double dx, double dz) {
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? EAST : WEST;
        return dz >= 0 ? SOUTH : NORTH;
    }

    /**
     * Camera facing for a seat at (seatX, seatZ) around a pen centred on (centerX, centerZ). A north or
     * south seat is looked back at from the far side, so the sitter is at the top of the screen; east
     * and west seats keep the landscape view (east faces north, west faces south).
     */
    public static PenFacing forSeat(double seatX, double seatZ, double centerX, double centerZ) {
        return switch (toward(seatX - centerX, seatZ - centerZ)) {
            case NORTH, EAST -> NORTH;
            case SOUTH, WEST -> SOUTH;
        };
    }
}
