package vfs;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lfs.BuiltinLFS;
import org.eclipse.jgit.lfs.LfsPointer;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.UUID;

public class GitRepo extends FileRepository {

    public static final String branchRef = "HEAD";

    public final Git git = new Git(this);

    public GitRepo(Path repoPath) throws IOException {
        super( repoPath.resolve(".git").toFile() );
    }

    public final Path cacheDir = Path.of(
        System.getProperty("java.io.tmpdir"), 
        "git_lfs_java_vfs_" + UUID.randomUUID()
    );

    public final TreeHashMap tree = new TreeHashMap();

    public Path tempfile(String name) throws IOException {
        return Files.createTempFile(cacheDir, name+"_", ".bin");
    }

    public void indexRepo() throws IOException {
        tree.clear();
        VfsNode root = new VfsNode(this, true);
        tree.put("/", root);

        ObjectId headId = resolve(branchRef);
        if (headId == null) return;

        try (RevWalk revWalk = new RevWalk(this);
             TreeWalk treeWalk = new TreeWalk(this);
             ObjectReader reader = newObjectReader()) {

            treeWalk.addTree(revWalk.parseCommit(headId).getTree());
            treeWalk.setRecursive(true);

            while (treeWalk.next()) {
                String fullPath = "/" + treeWalk.getPathString().replace('\\', '/');
                ObjectId blobId = treeWalk.getObjectId(0);
                ObjectLoader loader = reader.open(blobId, Constants.OBJ_BLOB);

                for (int idx = 1; (idx = fullPath.indexOf('/', idx)) != -1; idx++) {
                    tree.computeIfAbsent(fullPath.substring(0, idx), k -> new VfsNode(this, true));
                }

                VfsNode node = new VfsNode(this, false);
                node.setObjectId(blobId);
                node.size = loader.getSize();

                if (node.size < 1024) {
                    try (InputStream in = loader.openStream()) {
                        LfsPointer ptr = LfsPointer.parseLfsPointer(in);
                        if (ptr != null) node.size = ptr.getSize();
                    }
                }
                tree.put(fullPath, node);
            }
        }
    }

    public void commitFileToGit(String vfsPath, Path stagedFile) throws IOException {
        vfsPath = tree.norm(vfsPath);
        synchronized (tree.lock) {
            String relPath = vfsPath.substring(1);
            Path workTreeFile = getWorkTree().toPath().resolve(relPath);
            Files.createDirectories(workTreeFile.getParent());
            Files.copy(stagedFile, workTreeFile, StandardCopyOption.REPLACE_EXISTING);

            RevCommit commit;
            try {
                git.add()
                    .addFilepattern(relPath)
                    .call();
                commit = git.commit()
                    .setMessage("VFS update: " + relPath)
                    .call();
            } catch (GitAPIException e) {
                throw new IOException("GitAPIException", e);
            }

            try (TreeWalk tw = TreeWalk.forPath(this, relPath, commit.getTree())) {
                VfsNode node = tree.computeIfAbsent(vfsPath, k -> new VfsNode(this, false));
                if (tw != null)
                    node.setObjectId(tw.getObjectId(0));
                node.size = Files.size(stagedFile);
                node.mtimeSec = System.currentTimeMillis() / 1000L;
            }

        }
    }

}
