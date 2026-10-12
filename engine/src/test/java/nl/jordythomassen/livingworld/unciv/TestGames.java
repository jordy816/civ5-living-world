package nl.jordythomassen.livingworld.unciv;

import com.unciv.UncivGame;
import com.unciv.logic.GameInfo;
import com.unciv.logic.GameStarter;
import com.unciv.logic.map.MapParameters;
import com.unciv.logic.map.MapSize;
import com.unciv.models.metadata.GameParameters;
import com.unciv.models.metadata.GameSetupInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Real Unciv games for the tests: small maps, fixed seeds. */
final class TestGames {
	private static Path dir;

	private TestGames() {
	}

	static synchronized void boot() throws IOException {
		if (dir == null) {
			dir = Files.createTempDirectory("livingworld-unciv");
		}
		UncivBoot.ensure(dir, System.err::println);
	}

	static GameInfo start(long seed, String shape) throws IOException {
		boot();
		GameParameters game = new GameParameters();
		game.setNumberOfCityStates(4);
		MapParameters map = new MapParameters();
		map.setMapSize(MapSize.Companion.getTiny());
		map.setShape(shape);
		map.setSeed(seed);
		GameInfo info = GameStarter.Companion.startNewGame(new GameSetupInfo(game, map));
		UncivGame.Companion.getCurrent().setGameInfo(info);
		return info;
	}
}
