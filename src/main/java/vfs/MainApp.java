package vfs;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;

import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.apache.ftpserver.ftplet.FileSystemFactory2;
import org.apache.ftpserver.FtpServerFactory2;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class MainApp {

    private record AppArgs(
        Path repo,
        int port
    ) {}

    private static AppArgs parse_args(String[] args) {

        ArgumentParser parser = ArgumentParsers.newFor("App").build()
            .defaultHelp(true)
            .description("Serve a Git LFS repository over FTP.");

        parser.addArgument("-r", "--repo")
            .type(Arguments.fileType())
            .required(true)
            .help("Path to the repository");

        parser.addArgument("-p", "--port")
            .type(Integer.class)
            .setDefault(2121)
            .help("FTP port to listen on");

        Namespace ns = parser.parseArgsOrFail(args);

        return new AppArgs(
            ns.<File>get("repo").toPath(),
            ns.getInt("port")
        );
    }

    public static void main(String[] args) throws Exception {

        AppArgs ns = parse_args(args);

        GitRepo repo = new GitRepo(ns.repo());
        Files.createDirectories(repo.cacheDir);
        repo.indexRepo();

        var server = new FtpServerFactory2("127.0.0.1", ns.port());
        server.addUser("anonymous", new WritePermission());
        server.setFileSystem(new FileSystemFactory2(repo));
        server.start();
        
    }

}
