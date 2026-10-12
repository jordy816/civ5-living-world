package nl.jordythomassen.livingworld.gameplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import org.junit.jupiter.api.Test;

class TechGateTest {
	@Test
	void civsTechTreeDecidesWhatCanBeCrafted() {
		Set<String> ancient = Set.of("AGRICULTURE", "POTTERY");
		assertNull(TechGate.missing("minecraft:stone_pickaxe", ancient), "stone tools are free from the start");
		assertNull(TechGate.missing("minecraft:oak_planks", ancient));
		assertNull(TechGate.missing("minecraft:bread", ancient));
		assertEquals("IRON_WORKING", TechGate.missing("minecraft:iron_pickaxe", ancient));
		assertEquals("BRONZE_WORKING", TechGate.missing("minecraft:copper_axe", ancient));
		assertEquals("WRITING", TechGate.missing("minecraft:bookshelf", ancient));
		assertEquals("SAILING", TechGate.missing("minecraft:oak_boat", ancient));
		assertEquals("ARCHERY", TechGate.missing("minecraft:bow", ancient));
		assertEquals("GUNPOWDER", TechGate.missing("minecraft:tnt", ancient));
		Set<String> later = Set.of("AGRICULTURE", "POTTERY", "BRONZE_WORKING", "IRON_WORKING", "WRITING");
		assertNull(TechGate.missing("minecraft:iron_pickaxe", later));
		assertNull(TechGate.missing("minecraft:copper_axe", later));
		assertNull(TechGate.missing("minecraft:book", later));
		assertEquals("STEEL", TechGate.missing("minecraft:diamond_sword", later));
	}

	@Test
	void ironIngotsAndOtherMaterialsAreNeverHeldBack() {
		// Only finished tools are gated: smelting and carrying materials is always possible.
		assertNull(TechGate.required("minecraft:iron_ingot"));
		assertNull(TechGate.required("minecraft:iron_block"));
		assertNull(TechGate.required("minecraft:iron_bars"));
		assertNull(TechGate.required("minecraft:copper_block"));
	}

	@Test
	void withoutACivGameNothingIsHeldBack() {
		assertNull(TechGate.missing("minecraft:iron_pickaxe", Set.of()));
	}

	@Test
	void learningATechnologySaysWhatItUnlocks() {
		assertEquals("copper tools and armour", TechGate.unlocks("BRONZE_WORKING"));
		assertNull(TechGate.unlocks("POTTERY"));
		assertEquals("Bronze Working", TechGate.words("BRONZE_WORKING"));
		assertEquals("The Wheel", TechGate.words("THE_WHEEL"));
	}
}
