package vfs;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;

import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import java.io.File;
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

        var f = new FtpServerFactory() {{
            bind("127.0.0.1", ns.port());
            addUser(u -> {
                u.setName("anonymous");
                u.setHomeDirectory("/");
                u.setAuthorities(new WritePermission());
            });
            setFileSystem(new GitFileSystemFactory(ns.repo()));
        }};

        FtpServer server = f.createServer();
        server.start();
        System.out.println("FTP server running at 127.0.0.1:" + ns.port());
    }

}
