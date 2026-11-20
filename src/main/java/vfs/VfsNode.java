package vfs;

import org.eclipse.jgit.lib.ObjectId;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class VfsNode {

    public boolean isDir;
    public long size;
    public ObjectId objectId;
    public long mtimeSec = System.currentTimeMillis() / 1000L;

    public int openCount = 0;
    public String vfsPath;
    public Path localFile;
    public FileChannel channel;
    public boolean dirty;

    public void setDirty(boolean dirty) throws IOException {
        this.dirty = dirty;
        this.channel = dirty
            ? FileChannel.open(localFile, StandardOpenOption.READ, StandardOpenOption.WRITE)
            : FileChannel.open(localFile, StandardOpenOption.READ);    
    }

}
