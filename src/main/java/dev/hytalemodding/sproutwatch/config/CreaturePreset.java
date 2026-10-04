package dev.hytalemodding.sproutwatch.config;

import java.util.Locale;

/**
 * What lives in the pen (config "Creatures", the Pen tab dropdown). Each preset is a role list in the
 * Roles format: entries equally likely, "|" groups pick one member. Kweebecs uses the configured
 * Roles so customisations survive; the animal presets are fixed vanilla Livestock roles.
 */
public enum CreaturePreset {
    KWEEBECS("kweebecs", "Kweebecs", null),
    /** Half adults, half piglets; boars included (Mertie's choice). */
    PIGS("pigs", "Pigs", new String[]{"Pig|Pig_Wild|Boar", "Pig_Piglet|Pig_Wild_Piglet|Boar_Piglet"}),
    /** Half adults, half chicks. */
    CHICKENS("chickens", "Chickens", new String[]{"Chicken|Chicken_Desert", "Chicken_Chick|Chicken_Desert_Chick"}),
    /** Thirds: chickens (any age), farm pigs (any age, no boars), calves. */
    FARM("farm", "Farm animals", new String[]{"Chicken|Chicken_Desert|Chicken_Chick|Chicken_Desert_Chick",
        "Pig|Pig_Wild|Pig_Piglet|Pig_Wild_Piglet", "Cow_Calf"});

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
