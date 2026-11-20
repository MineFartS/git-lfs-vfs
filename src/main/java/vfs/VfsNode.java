package vfs;

import org.eclipse.jgit.lfs.LfsBlobFilter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public class VfsNode {

    public final GitRepo repo;
    public final boolean isDir;

    public VfsNode(GitRepo repo, boolean isDir) {
        this.repo = repo;
        this.isDir = isDir;
    }

    public long size;
    public ObjectId objectId;
    public long mtimeSec = System.currentTimeMillis() / 1000L;

    public int openCount = 0;
    public String vfsPath;
    public Path localFile;
    public FileChannel channel;
    public boolean dirty;

    public Path cachefile;

    public void setObjectId(ObjectId objectId) {
        this.objectId = objectId;
        this.cachefile = repo.cacheDir.resolve(objectId.getName());
    }

    public void setDirty(boolean dirty) throws IOException {
        this.dirty = dirty;
        this.channel = dirty
            ? FileChannel.open(localFile, StandardOpenOption.READ, StandardOpenOption.WRITE)
            : FileChannel.open(localFile, StandardOpenOption.READ);    
    }

    public void hydrate() throws IOException {
        if (Files.exists(cachefile)) return;
        synchronized (repo.tree.lock) {
            try (ObjectReader reader = repo.newObjectReader()) {
                ObjectLoader rawLoader = reader.open(objectId, Constants.OBJ_BLOB);
                ObjectLoader resolved = LfsBlobFilter.smudgeLfsBlob(repo, rawLoader);
                try (InputStream in = resolved.openStream()) {
                    Files.copy(in, cachefile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

}
