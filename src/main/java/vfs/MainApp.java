package vfs;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.impl.Arguments;

import java.io.File;
import java.nio.file.Files;
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

        Namespace ns = parser.parseArgsOrFail(args);

        GitLfsVfs vfs = new GitLfsVfs();

        vfs.cacheDir  = Files.createTempDirectory("git_lfs_java_vfs_");
        vfs.mountPath = ns.<File>get("mount").toPath();
        vfs.repoPath  = ns.<File>get("repo").toPath();

        vfs.mount();

    }

}
