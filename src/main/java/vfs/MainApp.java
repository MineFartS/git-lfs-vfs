package vfs;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.impl.Arguments;

import java.io.File;
import java.nio.file.Path;

public class MainApp {

    public static void main(String[] args) throws Exception {

        ArgumentParser parser = ArgumentParsers.newFor("App").build()
            .defaultHelp(true)
            .description("Mount a repository branch.");

        parser.addArgument("-r", "--repo")
            .type(Arguments.fileType())
            .required(true)
            .help("Path to the repository");

        parser.addArgument("-m", "--mount")
            .type(Arguments.fileType())
            .required(true)
            .help("Target mount point");

        parser.addArgument("-b", "--branch")
            .setDefault("main")
            .help("Branch name to checkout");

        Namespace ns = parser.parseArgsOrFail(args);

        Path repo = ns.<File>get("repo").toPath();
        Path mount = ns.<File>get("mount").toPath();
        String branch = ns.getString("branch");

        GitLfsVfs vfs = new GitLfsVfs(repo, branch);

        System.out.println("Mounting Git LFS VFS at " + mount + " (Press Ctrl+C to unmount)...");
        try {
            vfs.mount(mount, true, false);
        } finally {
            vfs.umount();
        }

    }

}
