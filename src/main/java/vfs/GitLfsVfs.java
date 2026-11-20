package vfs;

import jnr.ffi.Pointer;
import jnr.ffi.types.off_t;
import jnr.ffi.types.size_t;

import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.FuseFillDir;
import ru.serce.jnrfuse.FuseStubFS;
import ru.serce.jnrfuse.struct.FileStat;
import ru.serce.jnrfuse.struct.FuseFileInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class GitLfsVfs extends FuseStubFS {

    public Path mountPath;
    public GitRepo repo;

    public void mount() throws IOException {
        Files.createDirectories(repo.cacheDir);
        repo.indexRepo();
        try {
            super.mount(mountPath, true, false);
        } finally {
            super.umount();
            if (repo != null) repo.close();
        }
    }

    private OpenHandle registerHandle(
        String filePath,
        VfsNode node,
        Path targetFile,
        boolean dirty
    ) throws IOException {
        filePath = TreeHashMap.norm(filePath);

        FileChannel ch = dirty
            ? FileChannel.open(targetFile, StandardOpenOption.READ, StandardOpenOption.WRITE)
            : FileChannel.open(targetFile, StandardOpenOption.READ);

        OpenHandle oh = new OpenHandle();
        oh.vfsPath = filePath;
        oh.localFile = targetFile;
        oh.channel = ch;
        oh.dirty = dirty;
        node.handle = oh;
        return oh;
    }

    @Override
    public int getattr(String path, FileStat stat) {
        return repo.tree.with(path, (p, node) -> {
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
        });
    }

    @Override
    public int readdir(String path, Pointer buf, FuseFillDir filter, @off_t long offset, FuseFileInfo fi) {
        String normPath = TreeHashMap.norm(path);
        return repo.tree.with(normPath, (p, node) -> {
            if (!node.isDir) {
                return -ErrorCodes.ENOTDIR();
            }

            filter.apply(buf, ".", null, 0);
            filter.apply(buf, "..", null, 0);

            String prefix = normPath.equals("/") ? "/" : normPath + "/";
            for (String key : repo.tree.keySet()) {
                if (!key.startsWith(prefix) || key.equals(normPath)) {
                    continue;
                }
                String rest = key.substring(prefix.length());
                if (!rest.isEmpty() && !rest.contains("/")) {
                    filter.apply(buf, rest, null, 0);
                }
            }
            return 0;
        });
    }

    @Override
    public int mkdir(String path, long mode) {
        String normPath = TreeHashMap.norm(path);
        synchronized (repo.tree.lock) {
            if (repo.tree.containsKey(normPath)) {
                return -ErrorCodes.EEXIST();
            }
            VfsNode dir = new VfsNode();
            dir.isDir = true;
            repo.tree.put(normPath, dir);
            return 0;
        }
    }

    @Override
    public int create(String path, long mode, FuseFileInfo fi) {
        return IoSafe.run(repo.tree.lock, () -> {
            VfsNode node = new VfsNode();
            registerHandle(path, node, repo.tempfile("stage"), true);
            node.openCount = 1;
            repo.tree.put(path, node);
            return 0;
        });
    }

    @Override
    public int open(String path, FuseFileInfo fi) {
        return repo.tree.with(path, (p, node) -> {
            if (node.isDir)
                return -ErrorCodes.EISDIR();

            boolean isWrite = (fi.flags.get() & 0x3) != 0;

            if (node.handle == null) {
                Path hydrated = repo.hydrateFile(node);
                Path targetFile = hydrated;
                if (isWrite) {
                    targetFile = repo.tempfile("stage");
                    Files.copy(hydrated, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
                registerHandle(path, node, targetFile, isWrite);
            } else if (isWrite && !node.handle.dirty) {
                Path staged = repo.tempfile("stage");
                Files.copy(node.handle.localFile, staged, StandardCopyOption.REPLACE_EXISTING);
                node.handle.channel.close();
                registerHandle(path, node, staged, true);
            }

            node.openCount++;
            return 0;
        });
    }

    @Override
    public int read(
        String path,
        Pointer buf,
        @size_t long size,
        @off_t long offset,
        FuseFileInfo fi
    ) {
        return repo.tree.with(path, (p, node) -> {
            if (node.handle == null)
                return -ErrorCodes.EBADF();

            ByteBuffer bb = ByteBuffer.allocate((int) size);
            int bytesRead = node.handle.channel.read(bb, offset);
            if (bytesRead > 0) {
                bb.flip();
                buf.put(0, bb.array(), 0, bytesRead);
                return bytesRead;
            }
            return 0;
        });
    }

    @Override
    public int write(
        String path,
        Pointer buf,
        @size_t long size,
        @off_t long offset,
        FuseFileInfo fi
    ) {
        return repo.tree.with(path, (p, node) -> {
            if (node.handle == null)
                return -ErrorCodes.EBADF();

            byte[] data = new byte[(int) size];
            buf.get(0, data, 0, (int) size);

            node.handle.channel.write(ByteBuffer.wrap(data), offset);
            node.handle.dirty = true;

            node.size = node.handle.channel.size();
            node.mtimeSec = System.currentTimeMillis() / 1000L;

            return data.length;
        });
    }

    @Override
    public int truncate(String path, @off_t long size) {
        return repo.tree.with(path, (p, node) -> {
            if (node.isDir) {
                return -ErrorCodes.EISDIR();
            }
            if (node.handle != null) {
                node.handle.channel.truncate(size);
                node.handle.dirty = true;
                node.size = size;
                node.mtimeSec = System.currentTimeMillis() / 1000L;
                return 0;
            }
            Path hydrated = repo.hydrateFile(node);
            Path staged = repo.tempfile("stage");
            Files.copy(hydrated, staged, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel ch = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                ch.truncate(size);
            }
            repo.commitFileToGit(path, staged);
            Files.deleteIfExists(staged);
            return 0;
        });
    }

    @Override
    public int release(String path, FuseFileInfo fi) {
        return repo.tree.with(path, (p, node) -> {
            if (node.handle == null)
                return 0;

            node.openCount--;
            if (node.openCount <= 0) {
                OpenHandle oh = node.handle;
                node.handle = null;
                node.openCount = 0;

                oh.channel.close();
                if (oh.dirty) {
                    repo.commitFileToGit(oh.vfsPath, oh.localFile);
                    Files.deleteIfExists(oh.localFile);
                }
            }
            return 0;
        });
    }

    @Override
    public int unlink(String path) {
        String normPath = TreeHashMap.norm(path);
        return repo.tree.with(normPath, (p, node) -> {
            if (node.isDir)
                return -ErrorCodes.EISDIR();
            repo.tree.remove(normPath);
            return 0;
        });
    }

    @Override
    public int rmdir(String path) {
        String normPath = TreeHashMap.norm(path);
        if ("/".equals(normPath))
            return -ErrorCodes.EBUSY();

        return repo.tree.with(normPath, (p, node) -> {
            if (!node.isDir)
                return -ErrorCodes.ENOTDIR();
            String prefix = normPath + "/";
            for (String key : repo.tree.keySet()) {
                if (key.startsWith(prefix))
                    return -ErrorCodes.ENOTEMPTY();
            }
            repo.tree.remove(normPath);
            return 0;
        });
    }

    @Override
    public int rename(String oldpath, String newpath) {
        String normOld = TreeHashMap.norm(oldpath);
        String normNew = TreeHashMap.norm(newpath);

        synchronized (repo.tree.lock) {
            VfsNode node = repo.tree.remove(normOld);
            if (node == null)
                return -ErrorCodes.ENOENT();

            if (node.handle != null)
                node.handle.vfsPath = normNew;
            repo.tree.put(normNew, node);

            if (node.isDir) {
                String oldPrefix = normOld + "/";
                String newPrefix = normNew + "/";
                List<String> children = new ArrayList<>();
                for (String key : repo.tree.keySet()) {
                    if (key.startsWith(oldPrefix))
                        children.add(key);
                }
                for (String childKey : children) {
                    String newChildPath = newPrefix + childKey.substring(oldPrefix.length());
                    VfsNode child = repo.tree.remove(childKey);
                    if (child != null && child.handle != null)
                        child.handle.vfsPath = newChildPath;
                    repo.tree.put(newChildPath, child);
                }
            }

            return 0;
        }
    }

}
