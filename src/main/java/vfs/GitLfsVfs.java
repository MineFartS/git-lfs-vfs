package vfs;

import jnr.ffi.Pointer;
import jnr.ffi.types.off_t;
import jnr.ffi.types.size_t;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lfs.LfsBlobFilter;
import org.eclipse.jgit.lfs.LfsPointer;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.TemporaryBuffer;

import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.FuseFillDir;
import ru.serce.jnrfuse.FuseStubFS;
import ru.serce.jnrfuse.struct.FileStat;
import ru.serce.jnrfuse.struct.FuseFileInfo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class GitLfsVfs extends FuseStubFS {

    public static String branchRef = "HEAD";

    public Path cacheDir;
    public Path mountPath;
    public Path repoPath;

    private Repository repo;

    private final Map<String, VfsNode> tree = new ConcurrentHashMap<>();
    private final Map<Long, OpenHandle> openHandles = new ConcurrentHashMap<>();
    private final AtomicLong handleSeq = new AtomicLong(1);
    private final Object gitLock = new Object();

    @FunctionalInterface
    private interface FsOp {
        int run() throws IOException;
    }

    // 1. Mount Repo & Build Virtual Tree
    public void mount() throws IOException {
        File gitDir = repoPath.resolve(".git").toFile();
        if (!gitDir.exists())
            gitDir = repoPath.toFile();

        this.repo = new FileRepositoryBuilder()
            .setGitDir(gitDir)
            .readEnvironment()
            .findGitDir()
            .build();

        indexRepo();
        try {
            super.mount(mountPath, true, false);
        } finally {
            super.umount();
            if (repo != null) repo.close();
        }
    }

    private Path tempfile(String name) throws IOException {
        return Files.createTempFile(cacheDir, name+"_", ".bin");
    }

    private void indexRepo() throws IOException {
        tree.clear();
        VfsNode root = new VfsNode();
        root.isDir = true;
        tree.put("/", root);

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

    private Path hydrateFile(VfsNode node) throws IOException {
        if (node.objectId == null)
            return tempfile("empty");
        
        Path cached = cacheDir.resolve(node.objectId.getName());
        if (Files.exists(cached)) return cached;

        synchronized (gitLock) {
            if (Files.exists(cached)) return cached;
            Path tmp = cacheDir.resolve(node.objectId.getName() + ".tmp");

            try (ObjectReader reader = repo.newObjectReader()) {
                ObjectLoader rawLoader = reader.open(node.objectId, Constants.OBJ_BLOB);
                ObjectLoader resolved = LfsBlobFilter.smudgeLfsBlob(repo, rawLoader);
                try (InputStream in = resolved.openStream()) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
        }
        return cached;
    }

    // 3. Commit Changes When Handle Closes
    private void commitFileToGit(String vfsPath, Path stagedFile) throws IOException {
        synchronized (gitLock) {
            String relPath = vfsPath.substring(1);
            ObjectId parentId = repo.resolve(branchRef);
            ObjectId blobId;
            ObjectId newCommitId;

            TemporaryBuffer pointerBuf = null;
            try (InputStream in = Files.newInputStream(stagedFile);
                 ObjectInserter inserter = repo.newObjectInserter();
                 RevWalk revWalk = new RevWalk(repo)) {

                pointerBuf = LfsBlobFilter.cleanLfsBlob(repo, in);
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

    private void deleteFromGit(String vfsPath) throws IOException {
        synchronized (gitLock) {
            ObjectId parentId = repo.resolve(branchRef);
            if (parentId == null) return;

            String relPath = vfsPath.substring(1);
            ObjectId newCommitId;

            try (ObjectInserter inserter = repo.newObjectInserter();
                 RevWalk revWalk = new RevWalk(repo)) {

                DirCache index = buildInCoreIndex(revWalk, parentId);
                DirCacheEditor editor = index.editor();
                editor.add(new DirCacheEditor.DeletePath(relPath));
                editor.finish();

                newCommitId = insertCommit(inserter, index.writeTree(inserter), parentId, "VFS delete: " + relPath);
                inserter.flush();
            }

            updateBranchRef(parentId, newCommitId);
            tree.remove(vfsPath);
        }
    }

    private DirCache buildInCoreIndex(RevWalk revWalk, ObjectId parentId) throws IOException {
        DirCache index = DirCache.newInCore();
        if (parentId != null) {
            DirCacheBuilder builder = index.builder();
            builder.addTree(new byte[0], DirCacheEntry.STAGE_0, repo.newObjectReader(), revWalk.parseCommit(parentId).getTree());
            builder.finish();
        }
        return index;
    }

    private ObjectId insertCommit(ObjectInserter inserter, ObjectId treeId, ObjectId parentId, String msg) throws IOException {
        CommitBuilder cb = new CommitBuilder();
        cb.setTreeId(treeId);
        if (parentId != null) cb.setParentId(parentId);
        PersonIdent ident = new PersonIdent(repo);
        cb.setAuthor(ident);
        cb.setCommitter(ident);
        cb.setMessage(msg);
        return inserter.insert(cb);
    }

    private void updateBranchRef(ObjectId expectedOldId, ObjectId newCommitId) throws IOException {
        RefUpdate refUpdate = repo.updateRef(branchRef);
        refUpdate.setNewObjectId(newCommitId);
        if (expectedOldId != null) refUpdate.setExpectedOldObjectId(expectedOldId);
        RefUpdate.Result res = refUpdate.update();
        if (res != RefUpdate.Result.FAST_FORWARD && res != RefUpdate.Result.NEW && res != RefUpdate.Result.FORCED) {
            throw new IOException("Failed to update ref " + branchRef + ": " + res);
        }
    }

    // --- FUSE Overrides ---

    private String norm(String path) {
        String p = path.replace('\\', '/');
        if (p.length() > 1 && p.endsWith("/"))
            p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "/" : p;
    }

    private int ioSafe(FsOp op) {
        try {
            return op.run();
        } catch (IOException e) {
            return -ErrorCodes.EIO();
        }
    }

    private int registerHandle(String filePath, Path targetFile, boolean dirty, FuseFileInfo fi) throws IOException {
        FileChannel ch = dirty
            ? FileChannel.open(targetFile, StandardOpenOption.READ, StandardOpenOption.WRITE)
            : FileChannel.open(targetFile, StandardOpenOption.READ);

        long fh = handleSeq.getAndIncrement();
        OpenHandle oh = new OpenHandle();
        oh.vfsPath = filePath;
        oh.localFile = targetFile;
        oh.channel = ch;
        oh.dirty = dirty;
        openHandles.put(fh, oh);
        fi.fh.set(fh);
        return 0;
    }

    @Override
    public int getattr(String path, FileStat stat) {
        VfsNode node = tree.get(norm(path));
        if (node == null) return -ErrorCodes.ENOENT();

        if (node.isDir) {
            stat.st_mode.set(FileStat.S_IFDIR | 0755);
            stat.st_nlink.set(2);
        } else {
            stat.st_mode.set(FileStat.S_IFREG | 0644);
            stat.st_nlink.set(1);
            stat.st_size.set(node.size);
        }
        stat.st_mtim.tv_sec.set(node.mtimeSec);
        return super.getattr(path, stat);
    }

    @Override
    public int readdir(String path, Pointer buf, FuseFillDir filter, @off_t long offset, FuseFileInfo fi) {
        String dirPath = norm(path);
        VfsNode node = tree.get(dirPath);
        if (node == null || !node.isDir) return -ErrorCodes.ENOENT();

        filter.apply(buf, ".", null, 0);
        filter.apply(buf, "..", null, 0);

        String prefix = dirPath.equals("/") ? "/" : dirPath + "/";
        for (String itemPath : tree.keySet()) {
            if (!itemPath.equals(dirPath) && itemPath.startsWith(prefix)) {
                String rest = itemPath.substring(prefix.length());
                if (!rest.contains("/")) filter.apply(buf, rest, null, 0);
            }
        }
        return super.readdir(path, buf, filter, offset, fi);
    }

    @Override
    public int mkdir(String path, long mode) {
        String dirPath = norm(path);
        if (tree.containsKey(dirPath)) return -ErrorCodes.EEXIST();
        VfsNode dir = new VfsNode();
        dir.isDir = true;
        tree.put(dirPath, dir);
        return super.mkdir(path, mode);
    }

    @Override
    public int create(String path, long mode, FuseFileInfo fi) {
        return ioSafe(() -> {
            String filePath = norm(path);
            Path staged = tempfile("stage");
            tree.put(filePath, new VfsNode());
            registerHandle(filePath, staged, true, fi);
            return super.create(path, mode, fi);
        });
    }

    @Override
    public int open(String path, FuseFileInfo fi) {
        String filePath = norm(path);
        VfsNode node = tree.get(filePath);
        if (node == null) return -ErrorCodes.ENOENT();

        return ioSafe(() -> {
            Path hydrated = hydrateFile(node);
            boolean isWrite = (fi.flags.get() & 0x3) != 0;
            Path targetFile = hydrated;
            if (isWrite) {
                targetFile = tempfile("stage");
                Files.copy(hydrated, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            registerHandle(filePath, targetFile, isWrite, fi);
            return super.open(path, fi);
        });
    }

    @Override
    public int read(String path, Pointer buf, @size_t long size, @off_t long offset, FuseFileInfo fi) {
        OpenHandle oh = openHandles.get(fi.fh.get());
        if (oh == null) return -ErrorCodes.EBADF();

        return ioSafe(() -> {
            ByteBuffer bb = ByteBuffer.allocate((int) size);
            int bytesRead = oh.channel.read(bb, offset);
            if (bytesRead <= 0) return super.read(path, buf, size, offset, fi);
            bb.flip();
            buf.put(0, bb.array(), 0, bytesRead);
            return bytesRead;
        });
    }

    @Override
    public int write(String path, Pointer buf, @size_t long size, @off_t long offset, FuseFileInfo fi) {
        OpenHandle oh = openHandles.get(fi.fh.get());
        if (oh == null) return -ErrorCodes.EBADF();

        return ioSafe(() -> {
            byte[] data = new byte[(int) size];
            buf.get(0, data, 0, (int) size);
            int written = oh.channel.write(ByteBuffer.wrap(data), offset);
            oh.dirty = true;
            VfsNode node = tree.get(oh.vfsPath);
            if (node != null) {
                node.size = oh.channel.size();
                node.mtimeSec = System.currentTimeMillis() / 1000L;
            }
            return written;
        });
    }

    @Override
    public int truncate(String path, @off_t long size) {
        String filePath = norm(path);
        VfsNode node = tree.get(filePath);
        if (node == null) return -ErrorCodes.ENOENT();

        return ioSafe(() -> {
            Path hydrated = hydrateFile(node);
            Path staged = tempfile("trunc");
            Files.copy(hydrated, staged, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel ch = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                ch.truncate(size);
            }
            commitFileToGit(filePath, staged);
            Files.deleteIfExists(staged);
            return super.truncate(path, size);
        });
    }

    @Override
    public int release(String path, FuseFileInfo fi) {
        OpenHandle oh = openHandles.remove(fi.fh.get());
        if (oh == null) return super.release(path, fi);

        return ioSafe(() -> {
            oh.channel.close();
            if (oh.dirty) {
                commitFileToGit(oh.vfsPath, oh.localFile);
                Files.deleteIfExists(oh.localFile);
            }
            return super.release(path, fi);
        });
    }

    @Override
    public int unlink(String path) {
        String filePath = norm(path);
        if (!tree.containsKey(filePath)) return -ErrorCodes.ENOENT();

        return ioSafe(() -> {
            deleteFromGit(filePath);
            return super.unlink(path);
        });
    }

    @Override
    public int rmdir(String path) {
        tree.remove(norm(path));
        return super.rmdir(path);
    }

    @Override
    public int rename(String oldpath, String newpath) {
        String src = norm(oldpath);
        String dst = norm(newpath);
        VfsNode node = tree.get(src);
        if (node == null) return -ErrorCodes.ENOENT();

        return ioSafe(() -> {
            Path hydrated = hydrateFile(node);
            commitFileToGit(dst, hydrated);
            deleteFromGit(src);
            return super.rename(oldpath, newpath);
        });
    }

}
