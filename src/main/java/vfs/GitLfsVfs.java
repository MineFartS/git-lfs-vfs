package vfs;

import jnr.ffi.Pointer;
import jnr.ffi.types.off_t;
import jnr.ffi.types.size_t;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.attributes.FilterCommand;
import org.eclipse.jgit.lfs.CleanFilter;
import org.eclipse.jgit.lfs.SmudgeFilter;
import org.eclipse.jgit.lib.Repository;

import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.FuseFillDir;
import ru.serce.jnrfuse.FuseStubFS;
import ru.serce.jnrfuse.struct.FileStat;
import ru.serce.jnrfuse.struct.FuseFileInfo;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class GitLfsVfs extends FuseStubFS {

    public static String branchRef = "HEAD";

    public Path cacheDir;
    public Path mountPath;
    public Path repoPath;

    private Repository repo;

    public void mount() throws IOException {
        File gitDir = repoPath.resolve(".git").toFile();
        if (!gitDir.exists()) {
            gitDir = repoPath.toFile(); // Support bare repositories
        }
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
            if (this.repo != null) {
                this.repo.close();
            }
        }
    }

    private final Map < String, VfsNode > tree = new ConcurrentHashMap < > ();
    private final Map < Long, OpenHandle > openHandles = new ConcurrentHashMap < > ();
    private final AtomicLong handleSeq = new AtomicLong(1);
    private final Object gitLock = new Object();

    private byte[] runGitLfsFilter(
        InputStream in,
        FilterCommand factory
    ) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FilterCommand filter = factory.create(repo, in, out);
        while (filter.run() != -1) {
            // run() processes chunks until EOF (-1)
        }
        return out.toByteArray();
    }

    // --- JGit Repository Indexing & Operations ---
    private void indexRepo() throws IOException {
        tree.clear();
        VfsNode root = new VfsNode();
        root.isDir = true;
        tree.put("/", root);

        ObjectId headId = repo.resolve(branchRef);
        if (headId == null) return;

        try (RevWalk revWalk = new RevWalk(repo); TreeWalk treeWalk = new TreeWalk(repo); ObjectReader reader = repo.newObjectReader()) {

            RevCommit commit = revWalk.parseCommit(headId);
            RevTree revTree = commit.getTree();
            treeWalk.addTree(revTree);
            treeWalk.setRecursive(true);

            while (treeWalk.next()) {
                String relPath = treeWalk.getPathString().replace('\\', '/');
                String fullPath = "/" + relPath;
                ObjectId blobId = treeWalk.getObjectId(0);
                long size = reader.getObjectSize(blobId, Constants.OBJ_BLOB);

                // Ensure parent directories exist
                int idx = 1;
                while ((idx = fullPath.indexOf('/', idx)) != -1) {
                    String dirPath = fullPath.substring(0, idx);
                    tree.computeIfAbsent(dirPath, k -> {
                        VfsNode d = new VfsNode();
                        d.isDir = true;
                        return d;
                    });
                    idx++;
                }

                VfsNode node = new VfsNode();
                node.isDir = false;
                node.objectId = blobId;
                node.size = size;

                // Detect Git LFS pointer files (< 1024 bytes)
                if (size < 1024) {
                    byte[] content = reader.open(blobId, Constants.OBJ_BLOB).getBytes();
                    String text = new String(content, StandardCharsets.UTF_8);
                    if (text.startsWith("version https://github.com") || text.startsWith("version https://github.com")) {
                        node.isLfs = true;
                        node.pointerBytes = content;
                        for (String pLine: text.split("\\R")) {
                            if (pLine.startsWith("size ")) {
                                node.size = Long.parseLong(pLine.substring(5).trim());
                            }
                        }
                    }
                }
                tree.put(fullPath, node);
            }
        }
    }

    private Path hydrateFile(VfsNode node) throws IOException {
        if (node.objectId == null) {
            return Files.createTempFile(cacheDir, "empty_", ".bin");
        }
        String shaHex = node.objectId.getName();
        Path cached = cacheDir.resolve(shaHex);
        if (Files.exists(cached)) {
            return cached;
        }

        synchronized(gitLock) {
            if (Files.exists(cached)) return cached;
            Path tmp = cacheDir.resolve(shaHex + ".tmp");
            if (node.isLfs) {

                byte[] binary = runGitLfsFilter(
                    new ByteArrayInputStream(node.pointerBytes != null ? node.pointerBytes : new byte[0]),
                    SmudgeFilter::new
                );

                Files.write(tmp, binary);
            } else {
                try (ObjectReader reader = repo.newObjectReader()) {
                    byte[] binary = reader.open(node.objectId, Constants.OBJ_BLOB).getBytes();
                    Files.write(tmp, binary);
                }
            }
            Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
        }
        return cached;
    }

    private void commitFileToGit(String vfsPath, Path stagedFile) throws IOException {
        synchronized(gitLock) {
            String relPath = vfsPath.substring(1);

            // 1. Clean file through git-lfs to store payload in .git/lfs/objects
            byte[] pointerBytes = runGitLfsFilter(
                Files.newInputStream(stagedFile),
                CleanFilter::new
            );

            ObjectId parentCommitId = repo.resolve(branchRef);
            ObjectId blobId;
            ObjectId treeId;
            ObjectId newCommitId;

            try (ObjectInserter inserter = repo.newObjectInserter(); RevWalk revWalk = new RevWalk(repo)) {

                // 2. Store LFS pointer as a Git blob directly in JGit ObjectDatabase
                blobId = inserter.insert(Constants.OBJ_BLOB, pointerBytes);

                // 3. Build an in-memory DirCache from parent tree, update entry, and write tree
                DirCache inCoreIndex = DirCache.newInCore();
                if (parentCommitId != null) {
                    RevCommit parentCommit = revWalk.parseCommit(parentCommitId);
                    DirCacheBuilder builder = inCoreIndex.builder();
                    builder.addTree(new byte[0], DirCacheEntry.STAGE_0, repo.newObjectReader(), parentCommit.getTree());
                    builder.finish();
                }

                DirCacheEditor editor = inCoreIndex.editor();
                editor.add(new DirCacheEditor.PathEdit(relPath) {
                    @Override
                    public void apply(DirCacheEntry ent) {
                        ent.setFileMode(FileMode.REGULAR_FILE);
                        ent.setObjectId(blobId);
                    }
                });
                editor.finish();

                treeId = inCoreIndex.writeTree(inserter);

                // 4. Create and insert commit object
                CommitBuilder commitBuilder = new CommitBuilder();
                commitBuilder.setTreeId(treeId);
                if (parentCommitId != null) {
                    commitBuilder.setParentId(parentCommitId);
                }
                PersonIdent ident = new PersonIdent(repo);
                commitBuilder.setAuthor(ident);
                commitBuilder.setCommitter(ident);
                commitBuilder.setMessage("VFS update: " + relPath);

                newCommitId = inserter.insert(commitBuilder);
                inserter.flush();
            }

            // 5. Advance branch reference
            updateBranchRef(parentCommitId, newCommitId);

            // 6. Update in-memory node
            VfsNode node = tree.computeIfAbsent(vfsPath, k -> new VfsNode());
            node.isDir = false;
            node.isLfs = true;
            node.objectId = blobId;
            node.pointerBytes = pointerBytes;
            node.size = Files.size(stagedFile);
            node.mtimeSec = System.currentTimeMillis() / 1000L;
        }
    }

    private void deleteFromGit(String vfsPath) throws IOException {
        synchronized(gitLock) {
            String relPath = vfsPath.substring(1);
            ObjectId parentCommitId = repo.resolve(branchRef);
            if (parentCommitId == null) return;
            ObjectId treeId;
            ObjectId newCommitId;
            try (ObjectInserter inserter = repo.newObjectInserter(); RevWalk revWalk = new RevWalk(repo)) {
                RevCommit parentCommit = revWalk.parseCommit(parentCommitId);
                DirCache inCoreIndex = DirCache.newInCore();
                DirCacheBuilder builder = inCoreIndex.builder();
                builder.addTree(new byte[0], DirCacheEntry.STAGE_0, repo.newObjectReader(), parentCommit.getTree());
                builder.finish();
                DirCacheEditor editor = inCoreIndex.editor();
                editor.add(new DirCacheEditor.DeletePath(relPath));
                editor.finish();
                treeId = inCoreIndex.writeTree(inserter);
                CommitBuilder commitBuilder = new CommitBuilder();
                commitBuilder.setTreeId(treeId);
                commitBuilder.setParentId(parentCommitId);
                PersonIdent ident = new PersonIdent(repo);
                commitBuilder.setAuthor(ident);
                commitBuilder.setCommitter(ident);
                commitBuilder.setMessage("VFS delete: " + relPath);
                newCommitId = inserter.insert(commitBuilder);
                inserter.flush();
            }
            updateBranchRef(parentCommitId, newCommitId);
            tree.remove(vfsPath);
        }
    }

    private void updateBranchRef(ObjectId expectedOldId, ObjectId newCommitId) throws IOException {
        RefUpdate refUpdate = repo.updateRef(branchRef);
        refUpdate.setNewObjectId(newCommitId);
        if (expectedOldId != null) {
            refUpdate.setExpectedOldObjectId(expectedOldId);
        }
        RefUpdate.Result result = refUpdate.update();
        if (result != RefUpdate.Result.FAST_FORWARD && result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED) {
            throw new IOException("Failed to update ref " + branchRef + ": " + result);
        }
    }

    private String norm(String path) {
        String p = path.replace('\\', '/');
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "/" : p;
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
        return 0;
    }

    @Override
    public int readdir(String path, Pointer buf, FuseFillDir filter, @off_t long offset, FuseFileInfo fi) {
        String dirPath = norm(path);
        VfsNode node = tree.get(dirPath);
        if (node == null || !node.isDir) return -ErrorCodes.ENOENT();

        filter.apply(buf, ".", null, 0);
        filter.apply(buf, "..", null, 0);

        String prefix = dirPath.equals("/") ? "/" : dirPath + "/";
        for (String itemPath: tree.keySet()) {
            if (!itemPath.equals(dirPath) && itemPath.startsWith(prefix)) {
                String rest = itemPath.substring(prefix.length());
                if (!rest.contains("/")) {
                    filter.apply(buf, rest, null, 0);
                }
            }
        }
        return 0;
    }
    @Override
    public int mkdir(String path, long mode) {
        String dirPath = norm(path);
        if (tree.containsKey(dirPath)) return -ErrorCodes.EEXIST();
        VfsNode dir = new VfsNode();
        dir.isDir = true;
        tree.put(dirPath, dir);
        return 0;
    }
    @Override
    public int create(String path, long mode, FuseFileInfo fi) {
        String filePath = norm(path);
        try {
            Path staged = Files.createTempFile(cacheDir, "stage_", ".bin");
            FileChannel ch = FileChannel.open(staged, StandardOpenOption.READ, StandardOpenOption.WRITE);
            VfsNode node = new VfsNode();
            node.isDir = false;
            node.size = 0;
            tree.put(filePath, node);
            long fh = handleSeq.getAndIncrement();
            OpenHandle oh = new OpenHandle();
            oh.vfsPath = filePath;
            oh.localFile = staged;
            oh.channel = ch;
            oh.dirty = true;
            openHandles.put(fh, oh);
            fi.fh.set(fh);
            return 0;
        } catch (IOException e) {
            return -ErrorCodes.EIO();
        }
    }
    @Override
    public int open(String path, FuseFileInfo fi) {
        String filePath = norm(path);
        VfsNode node = tree.get(filePath);
        if (node == null) return -ErrorCodes.ENOENT();
        try {
            Path hydrated = hydrateFile(node);
            int flags = fi.flags.get();
            boolean isWrite = (flags & 0x3) != 0; // O_WRONLY (1) or O_RDWR (2)
            Path targetFile;
            FileChannel ch;
            if (isWrite) {
                targetFile = Files.createTempFile(cacheDir, "stage_", ".bin");
                Files.copy(hydrated, targetFile, StandardCopyOption.REPLACE_EXISTING);
                ch = FileChannel.open(targetFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
            } else {
                targetFile = hydrated;
                ch = FileChannel.open(targetFile, StandardOpenOption.READ);
            }
            long fh = handleSeq.getAndIncrement();
            OpenHandle oh = new OpenHandle();
            oh.vfsPath = filePath;
            oh.localFile = targetFile;
            oh.channel = ch;
            oh.dirty = isWrite;
            openHandles.put(fh, oh);
            fi.fh.set(fh);
            return 0;
        } catch (IOException e) {
            return -ErrorCodes.EIO();
        }
    }
    @Override
    public int read(String path, Pointer buf, @size_t long size, @off_t long offset, FuseFileInfo fi) {
        OpenHandle oh = openHandles.get(fi.fh.get());
        if (oh == null) return -ErrorCodes.EBADF();
        try {
            ByteBuffer bb = ByteBuffer.allocate((int) size);
            int bytesRead = oh.channel.read(bb, offset);
            if (bytesRead <= 0) return 0;
            bb.flip();
            buf.put(0, bb.array(), 0, bytesRead);
            return bytesRead;
        } catch (IOException e) {
            return -ErrorCodes.EIO();
        }
    }
    @Override
    public int write(String path, Pointer buf, @size_t long size, @off_t long offset, FuseFileInfo fi) {
        OpenHandle oh = openHandles.get(fi.fh.get());
        if (oh == null) return -ErrorCodes.EBADF();
        try {
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
        } catch (IOException e) {
            return -ErrorCodes.EIO();
        }
    }
    @Override
    public int truncate(String path, @off_t long size) {
        String filePath = norm(path);
        VfsNode node = tree.get(filePath);
        if (node == null) return -ErrorCodes.ENOENT();
        try {
            Path hydrated = hydrateFile(node);
            Path staged = Files.createTempFile(cacheDir, "trunc_", ".bin");
            Files.copy(hydrated, staged, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel ch = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                ch.truncate(size);
            }
            commitFileToGit(filePath, staged);
            Files.deleteIfExists(staged);
            return 0;
        } catch (IOException e) {
            return -ErrorCodes.EIO();
        }
    }
    @Override
    public int release(String path, FuseFileInfo fi) {
        OpenHandle oh = openHandles.remove(fi.fh.get());
        if (oh == null) return 0;
        try {
            oh.channel.close();
            if (oh.dirty) {
                commitFileToGit(oh.vfsPath, oh.localFile);
                Files.deleteIfExists(oh.localFile);
            }
            return 0;
        } catch (IOException e) {
            e.printStackTrace();
            return -ErrorCodes.EIO();
        }
    }
    @Override
    public int unlink(String path) {
        String filePath = norm(path);
        if (!tree.containsKey(filePath)) return -ErrorCodes.ENOENT();
        try {
            deleteFromGit(filePath);
            return 0;
        } catch (IOException e) {
            return -ErrorCodes.EIO();
        }
    }
    @Override
    public int rmdir(String path) {
        tree.remove(norm(path));
        return 0;
    }
    @Override
    public int rename(String oldpath, String newpath) {
        String src = norm(oldpath);
        String dst = norm(newpath);
        VfsNode node = tree.get(src);
        if (node == null) return -ErrorCodes.ENOENT();
        try {
            Path hydrated = hydrateFile(node);
            commitFileToGit(dst, hydrated);
            deleteFromGit(src);
            return 0;
        } catch (IOException e) {
            return -ErrorCodes.EIO();
        }
    }

}