package vfs;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.apache.ftpserver.impl.node.FtpNode;
import org.eclipse.jgit.lfs.LfsBlobFilter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

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

    public GitNode(GitFileSystemFactory fs, String vfsPath, boolean isDir) {
        super(isDir);
        this.fs = fs;
        this.vfsPath = vfsPath;
    }

    public void setObjectId(ObjectId objectId) {
        this.objectId = objectId;
        this.cachefile = fs.cacheDir.resolve(objectId.getName());
    }

    public synchronized void hydrate() throws IOException {
        if (objectId == null ||
            (cachefile != null && Files.exists(cachefile))
        ) return;

        try (ObjectReader reader = fs.repo.newObjectReader()) {
            ObjectLoader rawLoader = reader.open(objectId, Constants.OBJ_BLOB);
            ObjectLoader resolved = LfsBlobFilter.smudgeLfsBlob(fs.repo, rawLoader);
            try (InputStream in = resolved.openStream()) {
                Files.copy(in, cachefile, StandardCopyOption.REPLACE_EXISTING);
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
        hydrate();
        return new RandomAccessFile(cachefile.toFile(), "rw");
    }

    @Override
    public void close() throws IOException {
            
        this.size = Files.size(cachefile);
        this.mtimeSec = System.currentTimeMillis() / 1000L;

        String relPath = vfsPath.substring(1);

        Path workTreeFile = fs.repo.getWorkTree().toPath().resolve(vfsPath);
        Files.createDirectories(workTreeFile.getParent());
        Files.copy(
            cachefile,
            workTreeFile,
            StandardCopyOption.REPLACE_EXISTING
        );

        RevCommit commit;
        try {
            fs.git.add().addFilepattern(vfsPath.substring(1)).call();
            commit = fs.git.commit().setMessage("VFS update: " + relPath).call();
        } catch (GitAPIException e) {
            throw new IOException("GitAPIException", e);
        }

        try (TreeWalk tw = TreeWalk.forPath(fs.repo, vfsPath, commit.getTree())) {
            setObjectId( tw.getObjectId(0) );
        }

        Files.deleteIfExists(cachefile);
        
    }

}
