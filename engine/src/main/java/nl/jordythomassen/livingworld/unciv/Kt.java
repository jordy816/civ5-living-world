package nl.jordythomassen.livingworld.unciv;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kotlin.sequences.Sequence;

/** Small helpers for calling Unciv's Kotlin code from Java. */
public final class Kt {
	private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

	private Kt() {
	}

	public static <T> List<T> list(Sequence<T> sequence) {
		List<T> out = new ArrayList<>();
		for (Iterator<T> it = sequence.iterator(); it.hasNext(); ) {
			out.add(it.next());
		}
		return out;
	}

	/**
	 * Calls a method without arguments, picked by what it returns. Kotlin lets a class have a
	 * property and a function of the same name that differ only in their type; Java source cannot
	 * say which of the two it means.
	 */
	@SuppressWarnings("unchecked")
	public static <T> T get(Object target, String name, Class<T> returns) {
		String key = target.getClass().getName() + "#" + name + "#" + returns.getName();
		Method method = METHODS.computeIfAbsent(key, k -> {
			for (Method candidate : target.getClass().getMethods()) {
				if (candidate.getName().equals(name) && candidate.getParameterCount() == 0 && returns.isAssignableFrom(candidate.getReturnType())) {
					return candidate;
				}
			}
			throw new IllegalStateException("Unciv has no " + name + "() returning " + returns.getSimpleName() + " on " + target.getClass().getSimpleName());
		});
		try {
			return (T) method.invoke(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("calling " + name + " on " + target.getClass().getSimpleName(), e);
		}
	}
}
