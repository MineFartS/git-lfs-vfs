package vfs;

public class VfsNode {
    public boolean isDir;
    public long size;
    public ObjectId objectId;
    public boolean isLfs;
    public byte[] pointerBytes;
    public long mtimeSec = System.currentTimeMillis() / 1000L;
}
