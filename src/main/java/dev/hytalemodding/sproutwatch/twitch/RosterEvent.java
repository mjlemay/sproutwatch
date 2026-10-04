package dev.hytalemodding.sproutwatch.twitch;

import java.util.List;

/** One roster change parsed from a Twitch IRC line. Logins are always lowercase. */
public sealed interface RosterEvent
    permits RosterEvent.Names, RosterEvent.Join, RosterEvent.Part, RosterEvent.Chat {

    /** A 353 NAMES reply: everyone currently in the channel (one of possibly many bursts). */
    record Names(List<String> logins) implements RosterEvent {}

    /** A viewer joined the channel. */
    record Join(String login) implements RosterEvent {}

    /** A viewer left the channel. */
    record Part(String login) implements RosterEvent {}

    /**
     * A viewer spoke (PRIVMSG): proves presence even without membership, and carries the message
     * text for chat commands.
     */
    record Chat(String viewerKey, String text) implements RosterEvent {}
}
