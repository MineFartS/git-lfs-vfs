package vfs;

import static org.apache.ftpserver.util.PathUtils.norm;

import org.apache.ftpserver.impl.node.FileSystemFactory2;
import org.apache.ftpserver.impl.node.FtpNode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lfs.BuiltinLFS;
import org.eclipse.jgit.lfs.LfsPointer;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public class GitFileSystemFactory extends FileSystemFactory2 {

    public static final String branchRef = "HEAD";

    public final Repository repo;
    public final Git git;
    public final Object lock = new Object();

    public final Path cacheDir = Path.of(
        System.getProperty("java.io.tmpdir"),
        "git_lfs_java_vfs_" + UUID.randomUUID()
    );

    public GitFileSystemFactory(Path repoPath) throws IOException {
        super();
        BuiltinLFS.register();
        this.repo = new FileRepository(repoPath.resolve(".git").toFile());
        this.git = new Git(this.repo);
        Files.createDirectories(cacheDir);
        indexRepo();
    }

    @Override
    public FtpNode createNode(String path, boolean isDir) {
        GitNode node = new GitNode(this, path, isDir);
        tree.put(path, node);
        return node;
    }

    public Path tempfile(String name) throws IOException {
        return Files.createTempFile(cacheDir, name + "_", ".bin");
    }

    public void indexRepo() throws IOException {
        tree.clear();
        createNode("/", true);

        ObjectId headId = repo.resolve(branchRef);
        if (headId == null) return;

        try (RevWalk revWalk = new RevWalk(repo);
             TreeWalk treeWalk = new TreeWalk(repo);
             ObjectReader reader = repo.newObjectReader()) {

            treeWalk.addTree(revWalk.parseCommit(headId).getTree());
            treeWalk.setRecursive(true);

            while (treeWalk.next()) {
                String fullPath = "/" + treeWalk.getPathString().replace('\\', '/');
                ObjectId blobId = treeWalk.getObjectId(0);
                ObjectLoader loader = reader.open(blobId, Constants.OBJ_BLOB);

                for (int idx = 1; (idx = fullPath.indexOf('/', idx)) != -1; idx++) {
                    String dirPath = fullPath.substring(0, idx);
                    if (!tree.containsKey(dirPath)) {
                        createNode(dirPath, true);
                    }
                }

                GitNode node = (GitNode) createNode(fullPath, false);
                node.setObjectId(blobId);
                node.size = loader.getSize();

                if (node.size < 1024) {
                    try (InputStream in = loader.openStream()) {
                        LfsPointer ptr = LfsPointer.parseLfsPointer(in);
                        if (ptr != null) node.size = ptr.getSize();
                    }
                }
            }
        }
    }

    public ObjectId commitFileToGit(String vfsPath, Path stagedFile) throws IOException {
        vfsPath = norm(vfsPath);
        synchronized (lock) {
            String relPath = vfsPath.substring(1);
            Path workTreeFile = repo.getWorkTree().toPath().resolve(relPath);
            if (workTreeFile.getParent() != null) {
                Files.createDirectories(workTreeFile.getParent());
            }
            Files.copy(stagedFile, workTreeFile, StandardCopyOption.REPLACE_EXISTING);

            RevCommit commit;
            try {
                git.add().addFilepattern(relPath).call();
                commit = git.commit().setMessage("VFS update: " + relPath).call();
            } catch (GitAPIException e) {
                throw new IOException("GitAPIException", e);
            }

            try (TreeWalk tw = TreeWalk.forPath(repo, relPath, commit.getTree())) {
                return tw != null ? tw.getObjectId(0) : null;
            }
        }
    }

}
