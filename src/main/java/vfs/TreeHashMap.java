package vfs;

import ru.serce.jnrfuse.ErrorCodes;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public class TreeHashMap extends ConcurrentHashMap<String, VfsNode> {

    public final Object lock = new Object();

    public static String norm(String path) {
        String p = path.replace('\\', '/');
        if (p.length() > 1 && p.endsWith("/"))
            p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "/" : p;
    }

    @FunctionalInterface
    public interface WithOp<K, V> {
        int run(K key, V val) throws IOException;
    }

    public int with(K key, WithOp<K, V> op) {
        synchronized (lock) {
            V val = get(key);

            if (val == null)
                return -ErrorCodes.ENOENT();

            return IoSafe.run(() -> op.run(key, val));
        }
    }

    @Override
    public VfsNode get(Object key) {
        return super.get(norm((String) key));
    }

    @Override
    public VfsNode put(String key, VfsNode val) {
        return super.put(norm(key), val);
    }

    @Override
    public VfsNode remove(Object key) {
        return super.remove(norm((String) key));
    }

    @Override
    public boolean containsKey(Object key) {
        return super.containsKey(norm((String) key));
    }

}
