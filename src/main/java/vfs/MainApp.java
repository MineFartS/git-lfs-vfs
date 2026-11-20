package vfs;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.impl.Arguments;

import org.cryptomator.frontend.fuse.mount.EnvironmentVariables;
import org.cryptomator.frontend.fuse.mount.FuseMountFactory;
import org.cryptomator.frontend.fuse.mount.Mount;

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

        Namespace ns = parser.parseArgsOrFail(args);

        Path _mount = ns.<File>get("mount").toPath();
        Path _repo = ns.<File>get("repo").toPath();

        var mountBuilder = FuseMountFactory.getMounter();
        
        var env = EnvironmentVariables.create()
            .withMountPoint(_mount)
            .build();

        try (Mount mount = mountBuilder.mount(_repo, env)) {
            System.out.println("Press Enter to unmount...");
            System.in.read();
        }

    }

}
