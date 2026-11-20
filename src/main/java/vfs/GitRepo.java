package vfs;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lfs.LfsBlobFilter;
import org.eclipse.jgit.lfs.LfsPointer;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.TemporaryBuffer;

import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.struct.FuseFileInfo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class GitRepo extends FileRepository {

    public static final String branchRef = "HEAD";

    public GitRepo(Path repoPath) throws IOException {
        super( repoPath.resolve(".git").toFile() );
    }

    public final Path cacheDir = Path.of(
        System.getProperty("java.io.tmpdir"), 
        "git_lfs_java_vfs_" + UUID.randomUUID()
    );

    public final TreeHashMap tree = new TreeHashMap();

    public final ContextHashMap<Long, OpenHandle> openHandles = new ContextHashMap<>(ErrorCodes.EBADF());

    public Path tempfile(String name) throws IOException {
        return Files.createTempFile(cacheDir, name+"_", ".bin");
    }

    public void indexRepo() throws IOException {
        tree.clear();
        VfsNode root = new VfsNode();
        root.isDir = true;
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
                    tree.computeIfAbsent(fullPath.substring(0, idx), k -> {
                        VfsNode d = new VfsNode();
                        d.isDir = true;
                        return d;
                    });
                }

                VfsNode node = new VfsNode();
                node.objectId = blobId;
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

    public Path hydrateFile(VfsNode node) throws IOException {
        if (node.objectId == null)
            return tempfile("empty");
        
        Path cached = cacheDir.resolve(node.objectId.getName());
        if (Files.exists(cached)) return cached;

        synchronized (tree.lock) {
            if (Files.exists(cached)) return cached;
            Path tmp = cacheDir.resolve(node.objectId.getName() + ".tmp");

            try (ObjectReader reader = newObjectReader()) {
                ObjectLoader rawLoader = reader.open(node.objectId, Constants.OBJ_BLOB);
                ObjectLoader resolved = LfsBlobFilter.smudgeLfsBlob(this, rawLoader);
                try (InputStream in = resolved.openStream()) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
        }
        return cached;
    }

    public void commitFileToGit(String vfsPath, Path stagedFile) throws IOException {
        vfsPath = tree.norm(vfsPath);
        synchronized (tree.lock) {
            String relPath = vfsPath.substring(1);
            ObjectId parentId = resolve(branchRef);
            ObjectId blobId;
            ObjectId newCommitId;

            TemporaryBuffer pointerBuf = null;
            try (InputStream in = Files.newInputStream(stagedFile);
                 ObjectInserter inserter = newObjectInserter();
                 RevWalk revWalk = new RevWalk(this)) {

                pointerBuf = LfsBlobFilter.cleanLfsBlob(this, in);
                try (InputStream ptrIn = pointerBuf.openInputStream()) {
                    blobId = inserter.insert(Constants.OBJ_BLOB, pointerBuf.length(), ptrIn);
                }

                DirCache index = buildInCoreIndex(revWalk, parentId);
                DirCacheEditor editor = index.editor();
                editor.add(new DirCacheEditor.PathEdit(relPath) {
                    @Override
                    public void apply(DirCacheEntry ent) {
                        ent.setFileMode(FileMode.REGULAR_FILE);
                        ent.setObjectId(blobId);
                    }
                });
                editor.finish();

                newCommitId = insertCommit(inserter, index.writeTree(inserter), parentId, "VFS update: " + relPath);
                inserter.flush();
            } finally {
                if (pointerBuf != null) pointerBuf.destroy();
            }

            updateBranchRef(parentId, newCommitId);

            VfsNode node = tree.computeIfAbsent(vfsPath, k -> new VfsNode());
            node.isDir = false;
            node.objectId = blobId;
            node.size = Files.size(stagedFile);
            node.mtimeSec = System.currentTimeMillis() / 1000L;
        }
    }

    public DirCache buildInCoreIndex(RevWalk revWalk, ObjectId parentId) throws IOException {
        DirCache index = DirCache.newInCore();
        if (parentId != null) {
            DirCacheBuilder builder = index.builder();
            builder.addTree(new byte[0], DirCacheEntry.STAGE_0, newObjectReader(), revWalk.parseCommit(parentId).getTree());
            builder.finish();
        }
        return index;
    }

    public ObjectId insertCommit(ObjectInserter inserter, ObjectId treeId, ObjectId parentId, String msg) throws IOException {
        CommitBuilder cb = new CommitBuilder();
        cb.setTreeId(treeId);
        if (parentId != null) cb.setParentId(parentId);
        PersonIdent ident = new PersonIdent(this);
        cb.setAuthor(ident);
        cb.setCommitter(ident);
        cb.setMessage(msg);
        return inserter.insert(cb);
    }

    public void updateBranchRef(ObjectId expectedOldId, ObjectId newCommitId) throws IOException {
        RefUpdate refUpdate = updateRef(branchRef);
        refUpdate.setNewObjectId(newCommitId);
        if (expectedOldId != null) refUpdate.setExpectedOldObjectId(expectedOldId);
        RefUpdate.Result res = refUpdate.update();
        if (res != RefUpdate.Result.FAST_FORWARD && res != RefUpdate.Result.NEW && res != RefUpdate.Result.FORCED) {
            throw new IOException("Failed to update ref " + branchRef + ": " + res);
        }
    }

}
