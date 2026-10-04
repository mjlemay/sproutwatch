package dev.hytalemodding.sproutwatch.twitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure parser for Twitch IRC lines under the twitch.tv/membership capability.
 * Never throws: anything it does not understand is null.
 *
 * Shapes handled (a leading "@tags " block is skipped if present):
 *   :x.tmi.twitch.tv 353 nick = #chan :a b c      -> Names
 *   :a!a@a.tmi.twitch.tv JOIN #chan               -> Join
 *   :a!a@a.tmi.twitch.tv PART #chan               -> Part
 *   :a!a@a.tmi.twitch.tv PRIVMSG #chan :text      -> Chat (text = everything after the " :")
 */
public final class MembershipParser {

    private MembershipParser() {}

    public static RosterEvent parse(String line) {
        if (line == null || line.isEmpty()) return null;
        String remaining = line;
        if (remaining.startsWith("@")) {
            int sp = remaining.indexOf(' ');
            if (sp < 0) return null;
            remaining = remaining.substring(sp + 1);
        }
        if (!remaining.startsWith(":")) return null;
        int sp = remaining.indexOf(' ');
        if (sp < 0) return null;
        String prefix = remaining.substring(1, sp);
        String rest = remaining.substring(sp + 1);
        int cmdEnd = rest.indexOf(' ');
        String command = cmdEnd < 0 ? rest : rest.substring(0, cmdEnd);

        switch (command) {
            case "353": {
                int colon = rest.indexOf(" :");
                if (colon < 0) return null;
                List<String> logins = new ArrayList<>();
                for (String n : rest.substring(colon + 2).trim().split("\\s+")) {
                    if (!n.isEmpty()) logins.add(n.toLowerCase(Locale.ROOT));
                }
                return logins.isEmpty() ? null : new RosterEvent.Names(List.copyOf(logins));
            }
            case "JOIN": {
                String login = loginOf(prefix);
                return login == null ? null : new RosterEvent.Join(login);
            }
            case "PART": {
                String login = loginOf(prefix);
                return login == null ? null : new RosterEvent.Part(login);
            }
            case "PRIVMSG": {
                String login = loginOf(prefix);
                if (login == null) return null;
                int colon = rest.indexOf(" :", cmdEnd < 0 ? rest.length() : cmdEnd);
                String text = colon < 0 ? "" : rest.substring(colon + 2).replace("\r", "");
                return new RosterEvent.Chat(login, text);
            }
            default:
                return null;
        }
    }

    /** "nick!user@host" -> "nick" lowercased; a server prefix (no '!') yields null. */
    static String loginOf(String prefix) {
        int bang = prefix.indexOf('!');
        if (bang <= 0) return null;
        String login = prefix.substring(0, bang).trim().toLowerCase(Locale.ROOT);
        return login.isEmpty() ? null : login;
    }
}
