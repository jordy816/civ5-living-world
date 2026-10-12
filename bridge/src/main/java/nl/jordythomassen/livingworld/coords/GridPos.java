package nl.jordythomassen.livingworld.coords;

/**
 * A continuous position on the hex board, shared by both games.
 *
 * <p>{@code u} runs east in units of one hex width, {@code v} runs north in units of one row.
 * The centre of plot (x, y) is at u = x + rowShift(y), v = y. Everything a player does between
 * hex centres is expressed in this space; Minecraft blocks and Civ world units are both a plain
 * scale-and-offset away from it.
 */
public record GridPos(double u, double v) {
}
