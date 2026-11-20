package vfs;

import jnr.ffi.Pointer;
import jnr.ffi.types.off_t;
import jnr.ffi.types.size_t;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevWalk;

import ru.serce.jnrfuse.ErrorCodes;
import ru.serce.jnrfuse.FuseFillDir;
import ru.serce.jnrfuse.FuseStubFS;
import ru.serce.jnrfuse.struct.FileStat;
import ru.serce.jnrfuse.struct.FuseFileInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class GitLfsVfs extends FuseStubFS {

    public Path mountPath;
    public GitRepo repo;

    public void mount() throws IOException {

        repo.indexRepo();
        try {
            super.mount(mountPath, true, false);
        } finally {
            super.umount();
            if (repo != null) repo.close();
        }
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
            return super.getattr(path, stat);
        });
    }

    @Override
    public int readdir(String path, Pointer buf, FuseFillDir filter, @off_t long offset, FuseFileInfo fi) {
        return repo.tree.with(path, (dirPath, node) -> {
            if (!node.isDir) return -ErrorCodes.ENOTDIR();

            filter.apply(buf, ".", null, 0);
            filter.apply(buf, "..", null, 0);

            String prefix = dirPath.equals("/") ? "/" : dirPath + "/";
            for (String itemPath : repo.tree.keySet()) {
                if (!itemPath.equals(dirPath) && itemPath.startsWith(prefix)) {
                    String rest = itemPath.substring(prefix.length());
                    if (!rest.contains("/")) filter.apply(buf, rest, null, 0);
                }
            }
            return super.readdir(path, buf, filter, offset, fi);
        });
    }

    @Override
    public int mkdir(String path, long mode) {
        String dirPath = repo.norm(path);
        if (repo.tree.containsKey(dirPath)) return -ErrorCodes.EEXIST();
        VfsNode dir = new VfsNode();
        dir.isDir = true;
        repo.tree.put(dirPath, dir);
        return super.mkdir(path, mode);
    }

    @Override
    public int create(String path, long mode, FuseFileInfo fi) {
        return IoSafe.run(() -> {
            String filePath = repo.norm(path);
            Path staged = repo.tempfile("stage");
            repo.tree.put(filePath, new VfsNode());
            repo.registerHandle(filePath, staged, true, fi);
            return super.create(path, mode, fi);
        });
    }

    @Override
    public int open(String path, FuseFileInfo fi) {
        return repo.tree.with(path, (filePath, node) -> {
            Path hydrated = repo.hydrateFile(node);
            boolean isWrite = (fi.flags.get() & 0x3) != 0;
            Path targetFile = hydrated;
            if (isWrite) {
                targetFile = repo.tempfile("stage");
                Files.copy(hydrated, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            repo.registerHandle(filePath, targetFile, isWrite, fi);
            return super.open(path, fi);
        });
    }

    @Override
    public int read(String path, Pointer buf, @size_t long size, @off_t long offset, FuseFileInfo fi) {
        return repo.openHandles.with(fi.fh.get(), (fh, oh) -> {
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
        return repo.openHandles.with(fi.fh.get(), (fh, oh) -> {
            byte[] data = new byte[(int) size];
            buf.get(0, data, 0, (int) size);
            int written = oh.channel.write(ByteBuffer.wrap(data), offset);
            oh.dirty = true;
            VfsNode node = repo.tree.get(oh.vfsPath);
            if (node != null) {
                node.size = oh.channel.size();
                node.mtimeSec = System.currentTimeMillis() / 1000L;
            }
            return written;
        });
    }

    @Override
    public int truncate(String path, @off_t long size) {
        return repo.tree.with(path, (filePath, node) -> {
            Path hydrated = repo.hydrateFile(node);
            Path staged = repo.tempfile("trunc");
            Files.copy(hydrated, staged, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel ch = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                ch.truncate(size);
            }
            repo.commitFileToGit(filePath, staged);
            Files.deleteIfExists(staged);
            return super.truncate(path, size);
        });
    }

    @Override
    public int release(String path, FuseFileInfo fi) {
        OpenHandle oh = repo.openHandles.remove(fi.fh.get());
        if (oh == null) return super.release(path, fi);

        return IoSafe.run(() -> {
            oh.channel.close();
            if (oh.dirty) {
                repo.commitFileToGit(oh.vfsPath, oh.localFile);
                Files.deleteIfExists(oh.localFile);
            }
            return super.release(path, fi);
        });
    }

    @Override
    public int unlink(String path) {
        return repo.tree.with(path, (filePath, node) -> {
            ObjectId parentId = repo.resolve(repo.branchRef);
            if (parentId != null) {
                String relPath = filePath.substring(1);
                ObjectId newCommitId;

                try (ObjectInserter inserter = repo.newObjectInserter();
                    RevWalk revWalk = new RevWalk(repo)) {

                    DirCache index = repo.buildInCoreIndex(revWalk, parentId);
                    DirCacheEditor editor = index.editor();
                    editor.add(new DirCacheEditor.DeletePath(relPath));
                    editor.finish();

                    newCommitId = repo.insertCommit(inserter, index.writeTree(inserter), parentId, "VFS delete: " + relPath);
                    inserter.flush();
                }

                repo.updateBranchRef(parentId, newCommitId);
            }
            repo.tree.remove(filePath);
            return super.unlink(path);
        });
    }

    @Override
    public int rmdir(String path) {
        repo.tree.remove(repo.norm(path));
        return super.rmdir(path);
    }

    @Override
    public int rename(String oldpath, String newpath) {
        return repo.tree.with(oldpath, (src, node) -> {
            Path hydrated = repo.hydrateFile(node);
            repo.commitFileToGit(repo.norm(newpath), hydrated);
            int res = unlink(oldpath);
            return res != 0 ? res : super.rename(oldpath, newpath);
        });
    }

}
