package vfs;

import java.nio.channels.FileChannel;
import java.nio.file.Path;

public class OpenHandle {
    public String vfsPath;
    public Path localFile;
    public FileChannel channel;
    public boolean dirty;
}
