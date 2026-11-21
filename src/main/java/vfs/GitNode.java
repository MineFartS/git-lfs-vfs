package vfs;

import org.apache.ftpserver.impl.node.FtpNode;
import org.eclipse.jgit.lfs.LfsBlobFilter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class GitNode extends FtpNode {

    public final GitFileSystemFactory fs;
    public final String vfsPath;

    public ObjectId objectId;
    public Path cachefile;
    private Path stagedFile;

    public GitNode(GitFileSystemFactory fs, String vfsPath, boolean isDir) {
        super(isDir);
        this.fs = fs;
        this.vfsPath = vfsPath;
    }

    public void setObjectId(ObjectId objectId) {
        this.objectId = objectId;
        this.cachefile = fs.cacheDir.resolve(objectId.getName());
    }

    public void hydrate() throws IOException {
        if (cachefile != null && Files.exists(cachefile)) return;
        synchronized (fs.lock) {
            if (cachefile != null && Files.exists(cachefile)) return;
            try (ObjectReader reader = fs.repo.newObjectReader()) {
                ObjectLoader rawLoader = reader.open(objectId, Constants.OBJ_BLOB);
                ObjectLoader resolved = LfsBlobFilter.smudgeLfsBlob(fs.repo, rawLoader);
                try (InputStream in = resolved.openStream()) {
                    Files.copy(in, cachefile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    @Override
    public InputStream openR() throws IOException {
        hydrate();
        return Files.newInputStream(cachefile);
    }

    @Override
    public RandomAccessFile openW() throws IOException {
        stagedFile = fs.tempfile("stage");
        if (objectId != null) {
            hydrate();
            Files.copy(cachefile, stagedFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return new RandomAccessFile(stagedFile.toFile(), "rw");
    }

    @Override
    public void close() throws IOException {
        if (stagedFile != null) {
            try {
                this.size = Files.size(stagedFile);
                this.mtimeSec = System.currentTimeMillis() / 1000L;
                ObjectId newId = fs.commitFileToGit(vfsPath, stagedFile);
                if (newId != null) {
                    setObjectId(newId);
                }
            } finally {
                Files.deleteIfExists(stagedFile);
                stagedFile = null;
            }
        }
    }

}
