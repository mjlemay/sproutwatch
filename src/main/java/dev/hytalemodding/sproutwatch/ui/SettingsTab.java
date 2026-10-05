package dev.hytalemodding.sproutwatch.ui;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/**
 * The settings page's tab bar, in display order: one TextButton and one content Group per tab.
 * The active tab is shown by swapping each button's Style between two TextButtonStyles
 * (@TabStyle / @TabSelectedStyle) with the same Value.ref style-swap vanilla EntitySpawnPage /
 * UIGalleryPage use, so a switch is a partial update and never a rebuild. Unlike vanilla, which
 * refs styles declared in other documents, these are declared in the page's own document.
 */
enum SettingsTab {
    CONNECT("#TabConnect", "#ConnectTab"),
    PEN("#TabPen", "#PenTab"),
    DETAILS("#TabDetails", "#DetailsTab"),
    VIEWERS("#TabViewers", "#ViewersTab"),
    LISTENER("#TabListener", "#ListenerTab");

    /** Style refs into the page's own document; Value.ref(doc, name) resolves "@name" declared in doc. */
    static final Value<String> STYLE = Value.ref(SproutwatchSettingsPage.DOCUMENT, "TabStyle");
    static final Value<String> SELECTED_STYLE = Value.ref(SproutwatchSettingsPage.DOCUMENT, "TabSelectedStyle");

    final String button;
    final String group;

    SettingsTab(String button, String group) {
        this.button = button;
        this.group = group;
    }

    /**
     * Every button's style and every group's visibility for {@code active}. Complete on its own: a partial update suffices.
     * Exercised only in-game: UICommandBuilder's static init requires the Hytale log manager, which a JUnit JVM cannot
     * install without breaking the JUL handler capture other tests rely on.
     */
    static void apply(UICommandBuilder commands, SettingsTab active) {
        for (SettingsTab t : values()) {
            commands.set(t.button + ".Style", t == active ? SELECTED_STYLE : STYLE);
            commands.set(t.group + ".Visible", t == active);
        }
    }

    /** Binds each button once per build; bindings survive partial updates. */
    static void bind(UIEventBuilder events) {
        for (SettingsTab t : values()) {
            events.addEventBinding(CustomUIEventBindingType.Activating, t.button,
                new EventData().append(SettingsEvent.KEY_ACTION, PageAction.TAB.wireName()).append(SettingsEvent.KEY_TAB, t.name()));
        }
    }

    /** The tab the client named, or {@code current} when the name is missing or unknown. */
    static SettingsTab parse(String name, SettingsTab current) {
        if (name == null) return current;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return current;
        }
    }
}
