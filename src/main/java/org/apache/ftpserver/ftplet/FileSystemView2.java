package org.apache.ftpserver.ftplet;

import vfs.GitRepo;
import vfs.VfsNode;

public class FileSystemView2 implements FileSystemView {

    private final GitRepo repo;
    private String cwd = "/";

    public FileSystemView2(GitRepo repo) {
        this.repo = repo;
    }

    @Override
    public FtpFile2 getHomeDirectory() {
        return new FtpFile2(repo, "/");
    }

    @Override
    public FtpFile2 getWorkingDirectory() {
        return new FtpFile2(repo, cwd);
    }

    @Override
    public boolean changeWorkingDirectory(String dir) {
        String target = resolvePath(dir);
        VfsNode n = repo.tree.get(target);
        if (n != null && n.isDir) {
            cwd = target;
            return true;
        }
        return false;
    }

    @Override
    public FtpFile2 getFile(String file) {
        return new FtpFile2(repo, resolvePath(file));
    }

    @Override
    public boolean isRandomAccessible() {
        return true;
    }

    @Override
    public void dispose() {
        // NOP
    }

    private String resolvePath(String input) {
        String p = input.replace('\\', '/');
        String combined = p.startsWith("/") ? p : (cwd.equals("/") ? "/" + p : cwd + "/" + p);
        // Resolve "." and ".." segments
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String part : combined.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!stack.isEmpty()) stack.pop();
            } else {
                stack.push(part);
            }
        }
        if (stack.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = stack.descendingIterator();
        while (it.hasNext()) {
            sb.append('/').append(it.next());
        }
        return sb.toString();
    }
    
}
