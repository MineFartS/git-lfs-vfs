package org.apache.ftpserver.ftplet;

import vfs.GitRepo;

public class FileSystemFactory2 implements FileSystemFactory {

    private final GitRepo repo;

    public FileSystemFactory2(GitRepo repo) {
        this.repo = repo;
    }

    @Override
    public FileSystemView2 createFileSystemView(User user) {
        return new FileSystemView2(repo);
    }
    
}
