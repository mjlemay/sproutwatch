package dev.hytalemodding.sproutwatch.hud;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * One player's top-right panel (StatusHud.ui): the configured message and the next-tick bar. A
 * keyed CustomUIHud, so it sits alongside other mods' panels. Updates are partial and change-gated:
 * the message and anchor only when the text changes, the bar only when it moves a visible step.
 * Player's world thread only.
 */
final class SproutwatchHud extends CustomUIHud {

    static final String KEY = "Sproutwatch.Hud";
    static final String DOCUMENT = "Hud/Sproutwatch/StatusHud.ui";
    /** Bar changes smaller than this are not sent (about 1 pixel of the 352px bar). */
    private static final float BAR_STEP = 0.0028f;

    private String message;
    private float progress;

    SproutwatchHud(PlayerRef playerRef, String message, float progress) {
        super(playerRef, KEY);
        this.message = message;
        this.progress = progress;
    }

    @Override
    protected void build(UICommandBuilder commands) {
        commands.append(DOCUMENT);
        applyMessage(commands, message);
        commands.set("#HudNextBar.Value", progress);
    }

    /** Sends whatever changed since the last push; nothing when nothing did. */
    void refresh(String nextMessage, float nextProgress) {
        UICommandBuilder commands = new UICommandBuilder();
        boolean changed = false;
        if (!nextMessage.equals(message)) {
            message = nextMessage;
            applyMessage(commands, message);
            changed = true;
        }
        // A reset (tick fired) always goes out; filling only in visible steps.
        if (nextProgress < progress || nextProgress - progress >= BAR_STEP || (nextProgress == 1f && progress != 1f)) {
            progress = nextProgress;
            commands.set("#HudNextBar.Value", progress);
            changed = true;
        }
        if (changed) update(false, commands);
    }

    private static void applyMessage(UICommandBuilder commands, String message) {
        commands.set("#HudMessage.Text", message);
        Anchor anchor = new Anchor();
        anchor.setRight(Value.of(HudLayout.RIGHT));
        anchor.setTop(Value.of(HudLayout.TOP));
        anchor.setWidth(Value.of(HudLayout.WIDTH));
        anchor.setHeight(Value.of(HudLayout.height(message)));
        commands.setObject("#SproutwatchPanel.Anchor", anchor);
    }
}
