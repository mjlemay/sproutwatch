package dev.hytalemodding.sproutwatch.config;

import java.util.Locale;

/**
 * What lives in the pen (config "Creatures", the Pen tab dropdown). Each preset is a role list in the
 * Roles format: entries equally likely, "|" groups pick one member. Kweebecs uses the configured
 * Roles so customizations survive; the animal presets are fixed vanilla Livestock roles.
 */
public enum CreaturePreset {
    KWEEBECS("kweebecs", "Kweebecs", null),
    /** Half adults, half piglets; boars included (Mertie's choice). */
    PIGS("pigs", "Pigs", new String[]{"Pig|Pig_Wild|Boar", "Pig_Piglet|Pig_Wild_Piglet|Boar_Piglet"}),
    /** Half adults, half chicks. */
    CHICKENS("chickens", "Chickens", new String[]{"Chicken|Chicken_Desert", "Chicken_Chick|Chicken_Desert_Chick"}),
    /** Thirds: chickens (any age), farm pigs (any age, no boars), calves. */
    FARM("farm", "Farm animals", new String[]{"Chicken|Chicken_Desert|Chicken_Chick|Chicken_Desert_Chick",
        "Pig|Pig_Wild|Pig_Piglet|Pig_Wild_Piglet", "Cow_Calf"}),
    /** Friendly kid goblins (pre-release Lab Scrapper idles); burned is 1 in 40, voidtaken 1 in 100. */
    GOBLINS("goblins", "Goblins", goblinPool());

    /** 200 entries: 193 of the six everyday skins, 5 burned (2.5%) and 2 voidtaken (1%). */
    private static String[] goblinPool() {
        String[] pool = new String[200];
        java.util.Arrays.fill(pool, 0, 193,
            "Sprout_Goblin_Moldy|Sprout_Goblin_Mossy|Sprout_Goblin_Muddy|Sprout_Goblin_Rusty|Sprout_Goblin_Stale|Sprout_Goblin_Vapid");
        java.util.Arrays.fill(pool, 193, 198, "Sprout_Goblin_Muddy_Burned");
        java.util.Arrays.fill(pool, 198, 200, "Sprout_Goblin_Voidtaken");
        return pool;
    }

    private final String id;
    private final String displayName;
    private final String[] roles;

    CreaturePreset(String id, String displayName, String[] roles) {
        this.id = id;
        this.displayName = displayName;
        this.roles = roles;
    }

    public String id() { return id; }

    public String displayName() { return displayName; }

    /** The spawn pool; {@code kweebecRoles} (the configured Roles) is used only by KWEEBECS. */
    public String[] roles(String[] kweebecRoles) {
        return roles == null ? kweebecRoles.clone() : roles.clone();
    }

    /** Case-insensitive id; anything unknown is KWEEBECS. */
    public static CreaturePreset parse(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (CreaturePreset p : values()) if (p.id.equals(v)) return p;
        return KWEEBECS;
    }
}
