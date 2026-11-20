package vfs;

import ru.serce.jnrfuse.ErrorCodes;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public class ContextHashMap<K, V> extends ConcurrentHashMap<K, V> {

    public final Object lock = new Object();

    private final int missingErr;

    public ContextHashMap(int missingErr) {
        this.missingErr = missingErr;
    }

    @FunctionalInterface
    public interface WithOp<K, V> {
        int run(K key, V val) throws IOException;
    }

    public int with(K key, WithOp<K, V> op) {
        synchronized (lock) {
            V val = get(key);

            if (val == null)
                return -missingErr;

            return IoSafe.run(() -> op.run(key, val));
        }
    }

}
