package nl.jordythomassen.livingworld.mc;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import nl.jordythomassen.livingworld.board.BoardModel;
import nl.jordythomassen.livingworld.coords.GridPos;
import nl.jordythomassen.livingworld.coords.PlotCoord;

/**
 * Puts the board into the world: compares each column with what the board says it should be and
 * changes only the blocks that differ. Chunks are done a few per tick, nearest to a player
 * first, so walking stays smooth while the land fills in or changes.
 */
final class BoardPainter {
	/** Tell clients, but do not let neighbours react: painted water stays put, sand does not fall. */
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
	private static final long BUDGET_NANOS = 6_000_000L;
	private static final int MAX_PER_TICK = 6;
	/**
	 * Somebody looks at the whole land at once, from above: paint as fast as a tick allows. On
	 * foot the land beyond the fog can take its time, and smooth walking matters more.
	 */
	boolean hurry;
	private static final long HURRY_BUDGET_NANOS = 28_000_000L;
	private static final int HURRY_MAX_PER_TICK = 40;

	private final LongSet dirty = new LongOpenHashSet();
	/**
	 * Chunks painted wholesale, with the tick in which to send their light to the players again.
	 * The game tells a player of changed blocks itself (for every chunk it has sent them), but a
	 * chunk goes out with whatever light it has at that moment, and what a player's own game works
	 * out for blocks that change by the thousand is not always right: black patches on new land.
	 * The light as the server has it, sent once more when it has settled, puts that right.
	 */
	private final Long2IntOpenHashMap resend = new Long2IntOpenHashMap();
	private static final int RESEND_AFTER_TICKS = 15;
	/** Chunks sent once already: they go out a second time a few seconds later, for light that crossed in from a neighbour painted after them. */
	private final LongSet sentOnce = new LongOpenHashSet();
	private static final int SECOND_SENDING_TICKS = 100;
	/** Fewer changed blocks than this in a chunk is ordinary play, which the game handles itself. */
	private static final int WHOLESALE = 64;
	/**
	 * Per chunk, for each of its 256 columns, a fingerprint of what the board said the column
	 * was when it was last painted. A column is only written again when the board says something
	 * else, so whatever a player builds stays until Civ changes that very spot. The second half
	 * of each array holds the Civ turn in which the column's wood, crops and ore last grew back.
	 */
	private final java.util.Map<Long, int[]> painted = new java.util.HashMap<>();
	/** Changes every Civ turn: columns holding wood, crops or ore are painted again then, so the land yields anew. */
	private int season;
	private BoardModel model;
	private int[] bounds = new int[4];
	private final int[] column = new int[BoardModel.TOP - BoardModel.BASE];
	private long paintedChunks;
	private long changedBlocks;

	void setModel(BoardModel newModel, boolean everythingChanged, ServerLevel level) {
		model = newModel;
		if (newModel == null) {
			dirty.clear();
			resend.clear();
			sentOnce.clear();
			return;
		}
		bounds = newModel.bounds();
		if (everythingChanged) {
			dirty.clear();
			painted.clear();
			// Everything that is loaded right now; the rest is painted as it loads.
			for (ServerPlayer player : level.players()) {
				ChunkPos centre = player.chunkPosition();
				int radius = level.getServer().getPlayerList().getViewDistance() + 1;
				for (int cx = centre.x() - radius; cx <= centre.x() + radius; cx++) {
					for (int cz = centre.z() - radius; cz <= centre.z() + radius; cz++) {
						markChunk(cx, cz);
					}
				}
			}
		}
	}

	private boolean inBounds(int chunkX, int chunkZ) {
		return chunkX * 16 + 15 >= bounds[0] && chunkX * 16 <= bounds[2] && chunkZ * 16 + 15 >= bounds[1] && chunkZ * 16 <= bounds[3];
	}

	void markChunk(int chunkX, int chunkZ) {
		if (model != null && inBounds(chunkX, chunkZ)) {
			dirty.add(ChunkPos.pack(chunkX, chunkZ));
		}
	}

	/** A plot changed in Civ: repaint it and its neighbours (borders, rivers and roads are shared). */
	void markPlot(PlotCoord plot) {
		if (model == null) {
			return;
		}
		GridPos centre = model.state().session().grid().center(plot);
		double reach = model.layout().hexWidth() * 1.6;
		int minX = (int) Math.floor(model.layout().x(centre) - reach) >> 4;
		int maxX = (int) Math.floor(model.layout().x(centre) + reach) >> 4;
		int minZ = (int) Math.floor(model.layout().z(centre) - reach) >> 4;
		int maxZ = (int) Math.floor(model.layout().z(centre) + reach) >> 4;
		for (int cx = minX; cx <= maxX; cx++) {
			for (int cz = minZ; cz <= maxZ; cz++) {
				markChunk(cx, cz);
			}
		}
	}

	int waiting() {
		return dirty.size();
	}

	/** How many chunks near the players have never been painted at all: land that is still missing, not land that is merely looked over again. */
	int missing() {
		int count = 0;
		var it = dirty.iterator();
		while (it.hasNext()) {
			if (!painted.containsKey(it.nextLong())) {
				count++;
			}
		}
		return count;
	}

	/** A new Civ turn: what was harvested grows back. */
	void setSeason(int turn, ServerLevel level) {
		if (turn == season) {
			return;
		}
		season = turn;
		markAround(level);
	}

	/** Marks every chunk in view of a player. */
	void markAround(ServerLevel level) {
		for (ServerPlayer player : level.players()) {
			ChunkPos centre = player.chunkPosition();
			int radius = level.getServer().getPlayerList().getViewDistance() + 1;
			for (int cx = centre.x() - radius; cx <= centre.x() + radius; cx++) {
				for (int cz = centre.z() - radius; cz <= centre.z() + radius; cz++) {
					markChunk(cx, cz);
				}
			}
		}
	}

	/** Forgets what was painted near a position, so everything there is put back as Civ has it. */
	void forgetAround(int chunkX, int chunkZ, int radius) {
		for (int cx = chunkX - radius; cx <= chunkX + radius; cx++) {
			for (int cz = chunkZ - radius; cz <= chunkZ + radius; cz++) {
				painted.remove(ChunkPos.pack(cx, cz));
				markChunk(cx, cz);
			}
		}
	}

	private static final int FILE_MAGIC = 0x4C575032;

	/** Keeps the fingerprints with the world, so player builds also survive a restart. */
	void save(java.nio.file.Path file, String gameId, double hexWidth) {
		try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(file)))) {
			out.writeInt(FILE_MAGIC);
			out.writeUTF(gameId);
			out.writeDouble(hexWidth);
			out.writeInt(season);
			out.writeInt(painted.size());
			for (java.util.Map.Entry<Long, int[]> e : painted.entrySet()) {
				out.writeLong(e.getKey());
				for (int h : e.getValue()) {
					out.writeInt(h);
				}
			}
		} catch (java.io.IOException e) {
			LivingWorldMod.LOG.warn("Living World: could not save {}", file, e);
		}
	}

	/** Reads the fingerprints back if they belong to this Civ game and scale; otherwise starts clean. */
	void load(java.nio.file.Path file, String gameId, double hexWidth) {
		painted.clear();
		if (!java.nio.file.Files.exists(file)) {
			return;
		}
		try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(file)))) {
			if (in.readInt() != FILE_MAGIC || !in.readUTF().equals(gameId) || in.readDouble() != hexWidth) {
				return;
			}
			season = in.readInt();
			int count = in.readInt();
			for (int i = 0; i < count && i < 200_000; i++) {
				long key = in.readLong();
				int[] hashes = new int[512];
				for (int j = 0; j < 512; j++) {
					hashes[j] = in.readInt();
				}
				painted.put(key, hashes);
			}
		} catch (java.io.IOException e) {
			painted.clear();
			LivingWorldMod.LOG.warn("Living World: could not read {}, repainting everything", file, e);
		}
	}

	int rememberedChunks() {
		return painted.size();
	}

	long paintedChunks() {
		return paintedChunks;
	}

	long changedBlocks() {
		return changedBlocks;
	}

	/** Paints as many waiting chunks as fit in this tick's time, nearest to a player first. */
	void tick(ServerLevel level) {
		sendOutPainted(level);
		if (model != null && level.getServer().getTickCount() % 20 == 0) {
			markUnpainted(level);
		}
		if (model == null || dirty.isEmpty()) {
			return;
		}
		List<ServerPlayer> players = level.players();
		long start = System.nanoTime();
		int most = hurry ? HURRY_MAX_PER_TICK : MAX_PER_TICK;
		long budget = hurry ? HURRY_BUDGET_NANOS : BUDGET_NANOS;
		for (int done = 0; done < most && !dirty.isEmpty(); done++) {
			long best = 0;
			long bestDistance = Long.MAX_VALUE;
			boolean found = false;
			var it = dirty.iterator();
			while (it.hasNext()) {
				long key = it.nextLong();
				int cx = ChunkPos.getX(key);
				int cz = ChunkPos.getZ(key);
				if (level.getChunkSource().getChunkNow(cx, cz) == null) {
					// Not loaded any more; it is painted when it loads again.
					it.remove();
					continue;
				}
				long distance = Long.MAX_VALUE - 1;
				for (ServerPlayer player : players) {
					long dx = cx - player.chunkPosition().x();
					long dz = cz - player.chunkPosition().z();
					distance = Math.min(distance, dx * dx + dz * dz);
				}
				if (distance < bestDistance) {
					bestDistance = distance;
					best = key;
					found = true;
				}
			}
			if (!found) {
				return;
			}
			dirty.remove(best);
			paint(level, level.getChunkSource().getChunkNow(ChunkPos.getX(best), ChunkPos.getZ(best)));
			if (System.nanoTime() - start > budget) {
				return;
			}
		}
	}

	/**
	 * Marks what is loaded near a player and was never painted. Chunks are marked as they load,
	 * but one that loaded before this game's board was known (while the world was starting, or
	 * just outside what was marked then) would otherwise stay a hole in the land for good.
	 */
	private void markUnpainted(ServerLevel level) {
		int radius = level.getServer().getPlayerList().getViewDistance() + 2;
		for (ServerPlayer player : level.players()) {
			ChunkPos centre = player.chunkPosition();
			for (int cx = centre.x() - radius; cx <= centre.x() + radius; cx++) {
				for (int cz = centre.z() - radius; cz <= centre.z() + radius; cz++) {
					long key = ChunkPos.pack(cx, cz);
					if (!painted.containsKey(key) && !dirty.contains(key) && inBounds(cx, cz) && level.getChunkSource().getChunkNow(cx, cz) != null) {
						dirty.add(key);
					}
				}
			}
		}
	}

	/** Sends the chunks that were painted wholesale to their players again, once nothing near them is being painted any more. */
	private void sendOutPainted(ServerLevel level) {
		if (resend.isEmpty()) {
			return;
		}
		int now = level.getServer().getTickCount();
		List<Long> onceMore = null;
		var it = resend.long2IntEntrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			if (entry.getIntValue() > now) {
				continue;
			}
			long key = entry.getLongKey();
			int cx = ChunkPos.getX(key);
			int cz = ChunkPos.getZ(key);
			if (dirty.contains(key)) {
				// About to be painted again: one sending afterwards is enough.
				entry.setValue(now + RESEND_AFTER_TICKS);
				continue;
			}
			it.remove();
			if (level.getChunkSource().getChunkNow(cx, cz) == null) {
				sentOnce.remove(key);
				continue;
			}
			if (sentOnce.add(key)) {
				if (onceMore == null) {
					onceMore = new ArrayList<>();
				}
				onceMore.add(key);
			} else {
				sentOnce.remove(key);
			}
			// After the light of what was painted has been worked out, which happens on another thread.
			level.getChunkSource().getLightEngine().waitForPendingTasks(cx, cz).thenRunAsync(() -> {
				// Only a chunk the game has sent, or is ready to send: one it still holds back goes out later, with its light as it is then.
				LevelChunk chunk = level.getChunkSource().chunkMap.getChunkToSend(key);
				if (chunk == null) {
					return;
				}
				List<ServerPlayer> watching = level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false);
				if (watching.isEmpty()) {
					return;
				}
				ClientboundLightUpdatePacket light = new ClientboundLightUpdatePacket(chunk.getPos(), level.getLightEngine(), null, null);
				for (ServerPlayer player : watching) {
					player.connection.send(light);
				}
			}, level.getServer());
		}
		if (onceMore != null) {
			for (long key : onceMore) {
				resend.put(key, now + SECOND_SENDING_TICKS);
			}
		}
	}

	/** Paints one chunk right now (used by the self-test). */
	void paintNow(ServerLevel level, LevelChunk chunk) {
		dirty.remove(chunk.getPos().pack());
		paint(level, chunk);
	}

	private void paint(ServerLevel level, LevelChunk chunk) {
		if (chunk == null) {
			return;
		}
		paintedChunks++;
		long changedBefore = changedBlocks;
		int[] hashes = painted.computeIfAbsent(chunk.getPos().pack(), k -> new int[512]);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		List<BlockPos> fences = null;
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				int x = baseX + lx;
				int z = baseZ + lz;
				if (model.column(x, z, column) == BoardModel.NOTHING) {
					// Beyond this game's map. Land left there by an earlier, larger game goes.
					pos.set(x, BoardModel.BASE, z);
					if (!chunk.getBlockState(pos).isAir()) {
						for (int y = BoardModel.BASE; y < BoardModel.TOP; y++) {
							pos.set(x, y, z);
							if (!chunk.getBlockState(pos).isAir()) {
								level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
								changed(level, pos);
							}
						}
						hashes[lx * 16 + lz] = 0;
					}
					continue;
				}
				int fingerprint = java.util.Arrays.hashCode(column);
				if (fingerprint == 0) {
					fingerprint = 1;
				}
				boolean regrows = false;
				for (int cell : column) {
					if (nl.jordythomassen.livingworld.board.Material.of(cell).regrows()) {
						regrows = true;
						break;
					}
				}
				int index = lx * 16 + lz;
				if (hashes[index] == fingerprint) {
					// The board still says what it said when this column was painted: whatever a
					// player did here stays. Only what the land yields comes back, once per Civ turn.
					if (regrows && hashes[256 + index] != season) {
						hashes[256 + index] = season;
						for (int y = BoardModel.BASE; y < BoardModel.TOP; y++) {
							int cell = column[y - BoardModel.BASE];
							if (!nl.jordythomassen.livingworld.board.Material.of(cell).regrows()) {
								continue;
							}
							BlockState wanted = BlockPalette.of(cell);
							pos.set(x, y, z);
							if (chunk.getBlockState(pos) != wanted) {
								level.setBlock(pos, wanted, FLAGS);
								changed(level, pos);
							}
						}
					}
					continue;
				}
				hashes[index] = fingerprint;
				hashes[256 + index] = season;
				for (int y = BoardModel.BASE; y < BoardModel.TOP; y++) {
					BlockState wanted = BlockPalette.of(column[y - BoardModel.BASE]);
					pos.set(x, y, z);
					BlockState have = chunk.getBlockState(pos);
					boolean joins = BlockPalette.joins(wanted);
					if (have == wanted || (joins && have.is(wanted.getBlock()) && sameButJoined(have, wanted))) {
						continue;
					}
					level.setBlock(pos, wanted, FLAGS);
					changed(level, pos);
					if (joins) {
						if (fences == null) {
							fences = new ArrayList<>();
						}
						fences.add(pos.immutable());
					}
				}
			}
		}
		if (fences != null) {
			// Fences, panes and bars were placed without telling their neighbours, and stairs without
			// looking round the corner; join them up now that everything stands.
			for (BlockPos fence : fences) {
				BlockState joined = Block.updateFromNeighbourShapes(level.getBlockState(fence), level, fence);
				level.setBlock(fence, joined, FLAGS);
			}
		}
		if (changedBlocks - changedBefore >= WHOLESALE) {
			// This chunk, and the ones around it that are already painted: light crosses their edges.
			int due = level.getServer().getTickCount() + RESEND_AFTER_TICKS;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					long key = ChunkPos.pack(chunk.getPos().x() + dx, chunk.getPos().z() + dz);
					if ((dx == 0 && dz == 0) || painted.containsKey(key)) {
						resend.put(key, due);
						sentOnce.remove(key);
					}
				}
			}
		}
	}

	private void changed(ServerLevel level, BlockPos pos) {
		changedBlocks++;
	}

	/** The block that already stands there is the wanted one, apart from how it joined up with its neighbours. */
	private static boolean sameButJoined(BlockState have, BlockState wanted) {
		if (have.getBlock() instanceof net.minecraft.world.level.block.StairBlock) {
			return have.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
				== wanted.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
				&& have.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)
				== wanted.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF);
		}
		return true;
	}
}
