package nl.jordythomassen.livingworld.protocol;

import java.util.List;

/** One of Civ's own event hooks firing, passed along for logging and diagnostics. */
public record Hint(long number, String type, List<String> args) {
}
