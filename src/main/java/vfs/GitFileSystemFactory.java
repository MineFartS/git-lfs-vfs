package vfs;

import static org.apache.ftpserver.util.PathUtils.norm;

import org.apache.ftpserver.impl.node.FileSystemFactory2;
import org.apache.ftpserver.impl.node.FtpNode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.IndexItem;
import org.eclipse.jgit.lfs.BuiltinLFS;
import org.eclipse.jgit.lfs.LfsPointer;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
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

    public final FileRepository repo;
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
        GitNode node = new GitNode(this, norm(path), isDir);
        tree.put(path, node);
        return node;
    }

    public Path tempfile(String name) throws IOException {
        return Files.createTempFile(cacheDir, name + "_", ".bin");
    }

    public void indexRepo() throws IOException {
        tree.clear();
        createNode("/", true);

        for (IndexItem i : repo.getIndex()) {

            for (int idx = 1; (idx = i.path.indexOf('/', idx)) != -1; idx++) {
                String dirPath = i.path.substring(0, idx);
                if (!tree.containsKey(dirPath)) {
                    createNode(dirPath, true);
                }
            }

            GitNode node = (GitNode) createNode(i.path, false);
            node.setObjectId(i.objectId);

            LfsPointer ptr = LfsPointer.parseLfsPointer(repo, i.objectId);
            node.size = (ptr != null) ? ptr.getSize() : i.size;

        }
    }

}
