package vfs;

import ru.serce.jnrfuse.ErrorCodes;

public class TreeHashMap extends ContextHashMap<String, VfsNode> {

    public TreeHashMap() {
        super(ErrorCodes.ENOENT());
    }

    public static String norm(String path) {
        String p = path.replace('\\', '/');
        if (p.length() > 1 && p.endsWith("/"))
            p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "/" : p;
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
