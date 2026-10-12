package nl.jordythomassen.livingworld.protocol;

import nl.jordythomassen.livingworld.coords.CivWorldLayout;

/**
 * Where Civ's engine puts four reference plots in its own world units: (0,0), (1,0), (0,1) and
 * (0,2). From these we learn the hex spacing and which way odd rows are shifted, instead of
 * trusting a formula.
 */
public record Geometry(double x00, double y00, double x10, double y10, double x01, double y01, double x02, double y02) {
	public double columnStep() {
		return x10 - x00;
	}

	public double rowStep() {
		return (y02 - y00) / 2.0;
	}

	/** Shift of odd rows in hex widths: +0.5 (east) or -0.5 (west). */
	public double oddRowShift() {
		double shift = (x01 - x00) / columnStep();
		if (Math.abs(Math.abs(shift) - 0.5) > 0.05 || Math.abs(x02 - x00) > Math.abs(columnStep()) * 0.05
			|| Math.abs((y01 - y00) - rowStep()) > Math.abs(rowStep()) * 0.05) {
			throw new IllegalStateException("Civ's hex layout is not the offset-row grid we expect: " + this);
		}
		return shift > 0 ? 0.5 : -0.5;
	}

	public CivWorldLayout layout() {
		return new CivWorldLayout(x00, y00, columnStep(), rowStep());
	}
}
