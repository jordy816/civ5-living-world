package nl.jordythomassen.livingworld.unciv;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.files.FileHandle;
import java.io.File;
import java.nio.file.Path;

/**
 * The file system Unciv sees when it runs without its window: one folder, the engine's own in
 * the world's save. Unciv looks there for things it would play or show (a sound, a mod), finds
 * none and carries on; without this it would stop at the first such look.
 */
final class HeadlessFiles implements Files {
	private final File root;

	HeadlessFiles(Path root) {
		this.root = root.toAbsolutePath().normalize().toFile();
	}

	@Override
	public FileHandle getFileHandle(String path, FileType type) {
		return type == FileType.Absolute ? absolute(path) : new FileHandle(new File(root, path));
	}

	@Override
	public FileHandle classpath(String path) {
		return new FileHandle(new File(root, path));
	}

	@Override
	public FileHandle internal(String path) {
		return new FileHandle(new File(root, path));
	}

	@Override
	public FileHandle external(String path) {
		return new FileHandle(new File(root, path));
	}

	@Override
	public FileHandle absolute(String path) {
		return new FileHandle(new File(path));
	}

	@Override
	public FileHandle local(String path) {
		return new FileHandle(new File(root, path));
	}

	@Override
	public String getExternalStoragePath() {
		return root.getPath() + File.separator;
	}

	@Override
	public boolean isExternalStorageAvailable() {
		return true;
	}

	@Override
	public String getLocalStoragePath() {
		return root.getPath() + File.separator;
	}

	@Override
	public boolean isLocalStorageAvailable() {
		return true;
	}
}
