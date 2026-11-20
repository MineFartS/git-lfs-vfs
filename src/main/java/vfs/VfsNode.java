package vfs;

import org.eclipse.jgit.lib.ObjectId;

public class VfsNode {
    public boolean isDir;
    public long size;
    public ObjectId objectId;
    public long mtimeSec = System.currentTimeMillis() / 1000L;
    public OpenHandle handle;
    public int openCount = 0;
}
