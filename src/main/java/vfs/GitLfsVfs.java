package vfs;

import jnr.ffi.Pointer;
import jnr.ffi.types.off_t;
import jnr.ffi.types.size_t;
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

    private static class VfsNode {
        boolean isDir;
        long size;
        String sha;
        boolean isLfs;
        byte[] pointerBytes;
        long mtimeSec = System.currentTimeMillis() / 1000L;
    }

    private static class OpenHandle {
        String vfsPath;
        Path localFile;
        FileChannel channel;
        boolean dirty;
    }

    private final Path repoPath;
    private final String branchRef;
    private final Path cacheDir;
    private final Map < String,
        VfsNode > tree = new ConcurrentHashMap < > ();
    private final Map < Long,
        OpenHandle > openHandles = new ConcurrentHashMap < > ();
    private final AtomicLong handleSeq = new AtomicLong(1);
    private final Object gitLock = new Object();

    public GitLfsVfs(Path repoPath, String branchRef) throws IOException {
        this.repoPath = repoPath.toAbsolutePath();
        this.branchRef = branchRef;
        this.cacheDir = Files.createTempDirectory("git_lfs_java_vfs_");
        indexRepo();
    }

    // --- Git & LFS Subprocess Helpers ---
    private byte[] runGit(byte[] stdinData, String...args) throws IOException {
        List < String > cmd = new ArrayList < > ();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repoPath.toString());
        cmd.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();

        if (stdinData != null) {
            try (OutputStream os = p.getOutputStream()) {
                os.write(stdinData);
            }
        } else {
            p.getOutputStream().close();
        }

        byte[] stdout = p.getInputStream().readAllBytes();
        try {
            int exit = p.waitFor();
            if (exit != 0) {
                String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("Git failed (" + String.join(" ", args) + "): " + err);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        return stdout;
    }

    private String runGitString(String...args) throws IOException {
        return new String(runGit(null, args), StandardCharsets.UTF_8).trim();
    }

    private void indexRepo() throws IOException {
        tree.clear();
        VfsNode root = new VfsNode();
        root.isDir = true;
        tree.put("/", root);

        String raw = runGitString("ls-tree", "-r", "-l", branchRef);
        if (raw.isEmpty()) return;

        for (String line: raw.split("\\R")) {
            String[] parts = line.split("\t", 2);
            if (parts.length < 2) continue;

            String[] meta = parts[0].trim().split("\\s+");
            String sha = meta[2];
            long size = Long.parseLong(meta[3]);
            String fullPath = "/" + parts[1].replace('\\', '/');

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
            node.sha = sha;
            node.size = size;

            // Detect Git LFS pointer files (< 1024 bytes)
            if (size < 1024) {
                byte[] content = runGit(null, "cat-file", "-p", sha);
                String text = new String(content, StandardCharsets.UTF_8);
                if (text.startsWith("version https://github.com")) {
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

    private Path hydrateFile(VfsNode node) throws IOException {
        if (node.sha == null || node.sha.isEmpty()) {
            return Files.createTempFile(cacheDir, "empty_", ".bin");
        }
        Path cached = cacheDir.resolve(node.sha);
        if (Files.exists(cached)) {
            return cached;
        }

        synchronized(gitLock) {
            if (Files.exists(cached)) return cached;
            Path tmp = cacheDir.resolve(node.sha + ".tmp");
            if (node.isLfs) {
                byte[] binary = runGit(node.pointerBytes, "lfs", "smudge");
                Files.write(tmp, binary);
            } else {
                byte[] binary = runGit(null, "cat-file", "-p", node.sha);
                Files.write(tmp, binary);
            }
            Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
        }
        return cached;
    }

    private void commitFileToGit(String vfsPath, Path stagedFile) throws IOException {
        synchronized(gitLock) {
            String relPath = vfsPath.substring(1);

            // 1. Clean file through git-lfs to store payload in .git/lfs/objects
            List < String > cleanCmd = List.of("git", "-C", repoPath.toString(), "lfs", "clean");
            ProcessBuilder pb = new ProcessBuilder(cleanCmd);
            pb.redirectInput(stagedFile.toFile());
            Process p = pb.start();
            byte[] pointerBytes = p.getInputStream().readAllBytes();
            try {
                if (p.waitFor() != 0) throw new IOException("git lfs clean failed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }

            // 2. Store LFS pointer as a Git blob
            String blobSha = new String(runGit(pointerBytes, "hash-object", "-w", "--stdin"), StandardCharsets.UTF_8).trim();

            // 3. Update index, build tree, and advance branch ref
            String parentCommit = runGitString("rev-parse", branchRef);
            runGit(null, "read-tree", parentCommit);
            runGit(null, "update-index", "--add", "--cacheinfo", "100644," + blobSha + "," + relPath);
            String treeSha = runGitString("write-tree");

            String commitMsg = "VFS update: " + relPath;
            String commitSha = new String(
                runGit(commitMsg.getBytes(StandardCharsets.UTF_8), "commit-tree", treeSha, "-p", parentCommit), StandardCharsets.UTF_8).trim();

            runGit(null, "update-ref", branchRef, commitSha);

            // 4. Update in-memory node
            VfsNode node = tree.computeIfAbsent(vfsPath, k -> new VfsNode());
            node.isDir = false;
            node.isLfs = true;
            node.sha = blobSha;
            node.pointerBytes = pointerBytes;
            node.size = Files.size(stagedFile);
            node.mtimeSec = System.currentTimeMillis() / 1000L;
        }
    }

    private void deleteFromGit(String vfsPath) throws IOException {
        synchronized(gitLock) {
            String relPath = vfsPath.substring(1);
            String parentCommit = runGitString("rev-parse", branchRef);
            runGit(null, "read-tree", parentCommit);
            runGit(null, "update-index", "--force-remove", relPath);
            String treeSha = runGitString("write-tree");

            String commitMsg = "VFS delete: " + relPath;
            String commitSha = new String(
                runGit(commitMsg.getBytes(StandardCharsets.UTF_8), "commit-tree", treeSha, "-p", parentCommit), StandardCharsets.UTF_8).trim();

            runGit(null, "update-ref", branchRef, commitSha);
            tree.remove(vfsPath);
        }
    }

    // --- FUSE Filesystem Callbacks ---
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
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java GitLfsVfs <repo_path> <mount_drive_e.g._Z:> [branch_ref]");
            System.exit(1);
        }
        Path repo = Paths.get(args[0]);
        Path mountPoint = Paths.get(args[1]);
        String branch = args.length >= 3 ? args[2] : "refs/heads/main";
        GitLfsVfs vfs = new GitLfsVfs(repo, branch);
        System.out.println("Mounting Git LFS VFS at " + mountPoint + " (Press Ctrl+C to unmount)...");
        try {
            vfs.mount(mountPoint, true, false);
        } finally {
            vfs.umount();
        }
    }
}