package org.apache.ftpserver.ftplet;

import vfs.VfsNode;
import vfs.TreeHashMap;
import vfs.GitRepo;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class FtpFile2 implements FtpFile {

    private final GitRepo repo;
    private final String path;

    public FtpFile2(GitRepo repo, String path) {
        this.repo = repo;
        this.path = TreeHashMap.norm(path);
    }

    private VfsNode node() {
        return repo.tree.get(path);
    }

    @Override
    public String getAbsolutePath() {
        return path;
    }

    @Override
    public String getName() {
        if ("/".equals(path)) return "/";
        int idx = path.lastIndexOf('/');
        return path.substring(idx + 1);
    }

    @Override
    public boolean isHidden() {
        return false;
    }

    @Override
    public boolean isDirectory() {
        VfsNode n = node();
        return n != null && n.isDir;
    }

    @Override
    public boolean isFile() {
        VfsNode n = node();
        return n != null && !n.isDir;
    }

    @Override
    public boolean doesExist() {
        return node() != null;
    }

    @Override
    public boolean isReadable() {
        return doesExist();
    }

    @Override
    public boolean isWritable() {
        return !"/".equals(path);
    }

    @Override
    public boolean isRemovable() {
        if ("/".equals(path) || !doesExist()) return false;
        if (isDirectory()) {
            return listFiles().isEmpty();
        }
        return true;
    }

    @Override
    public String getOwnerName() {
        return "git";
    }

    @Override
    public String getGroupName() {
        return "git";
    }

    @Override
    public int getLinkCount() {
        return isDirectory() ? 2 : 1;
    }

    @Override
    public long getLastModified() {
        VfsNode n = node();
        return n != null ? n.mtimeSec * 1000L : 0L;
    }

    @Override
    public boolean setLastModified(long time) {
        VfsNode n = node();
        if (n == null) return false;
        n.mtimeSec = time / 1000L;
        return true;
    }

    @Override
    public long getSize() {
        VfsNode n = node();
        return n != null ? n.size : 0L;
    }

    @Override
    public Object getPhysicalFile() {
        return path;
    }

    @Override
    public boolean mkdir() {
        synchronized (repo.tree.lock) {
            if (doesExist()) return false;
            repo.tree.put(path, new VfsNode(repo, true));
            return true;
        }
    }

    @Override
    public boolean delete() {
        synchronized (repo.tree.lock) {
            if (!isRemovable()) return false;
            repo.tree.remove(path);
            return true;
        }
    }

    @Override
    public boolean move(FtpFile destination) {
        String normOld = this.path;
        String normNew = TreeHashMap.norm(destination.getAbsolutePath());

        synchronized (repo.tree.lock) {
            VfsNode n = repo.tree.remove(normOld);
            if (n == null) return false;

            repo.tree.put(normNew, n);

            if (n.isDir) {
                String oldPrefix = normOld + "/";
                String newPrefix = normNew + "/";
                List<String> children = new ArrayList<>();
                for (String key : repo.tree.keySet()) {
                    if (key.startsWith(oldPrefix)) children.add(key);
                }
                for (String childKey : children) {
                    String newChildPath = newPrefix + childKey.substring(oldPrefix.length());
                    VfsNode child = repo.tree.remove(childKey);
                    repo.tree.put(newChildPath, child);
                }
            }
            return true;
        }
    }

    @Override
    public List<FtpFile> listFiles() {
        synchronized (repo.tree.lock) {
            List<FtpFile> results = new ArrayList<>();
            VfsNode n = node();
            if (n == null || !n.isDir) return results;

            String prefix = path.equals("/") ? "/" : path + "/";
            for (String key : repo.tree.keySet()) {
                if (!key.startsWith(prefix) || key.equals(path)) continue;
                String rest = key.substring(prefix.length());
                if (!rest.isEmpty() && !rest.contains("/")) {
                    results.add(new FtpFile2(repo, key));
                }
            }
            return results;
        }
    }

    @Override
    public InputStream createInputStream(long offset) throws IOException {
        VfsNode n = node();
        if (n == null || n.isDir) {
            throw new FileNotFoundException(path);
        }
        n.hydrate();
        InputStream in = Files.newInputStream(n.cachefile, StandardOpenOption.READ);
        if (offset > 0) {
            long skipped = in.skip(offset);
            if (skipped < offset) {
                in.close();
                throw new IOException("Unable to seek to offset " + offset);
            }
        }
        return in;
    }

    @Override
    public OutputStream createOutputStream(long offset) throws IOException {
        synchronized (repo.tree.lock) {
            VfsNode n = node();
            if (n != null && n.isDir) {
                throw new IOException("Cannot open directory for writing: " + path);
            }

            Path staged = repo.tempfile("stage");
            if (n != null && offset > 0) {
                n.hydrate();
                Files.copy(n.cachefile, staged, StandardCopyOption.REPLACE_EXISTING);
            } else if (n == null) {
                n = new VfsNode(repo, false);
                repo.tree.put(path, n);
            }

            RandomAccessFile raf = new RandomAccessFile(staged.toFile(), "rw");
            raf.seek(offset);

            // Commit to Git automatically when the FTP client finishes uploading and closes the stream
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    raf.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    raf.write(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    try {
                        raf.close();
                        repo.commitFileToGit(path, staged);
                    } finally {
                        Files.deleteIfExists(staged);
                    }
                }
            };
        }
    }
}
