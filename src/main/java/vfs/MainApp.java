package vfs;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.impl.Arguments;

import org.cryptomator.frontend.fuse.EnvironmentVariables2;
import org.cryptomator.frontend.fuse.mount.FuseMountFactory;
import org.cryptomator.frontend.fuse.mount.Mount;

import java.text.Normalizer;
import java.io.File;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

public class MainApp {

    @FunctionalInterface
    interface NS {
        Path get(String key);
    }

    private static NS parse_args(String[] args) {

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

        return key -> ns.<File>get(key).toPath();
    }

    public static void main(String[] args) throws Exception {

        NS ns = parse_args(args);

        var mountBuilder = FuseMountFactory.getMounter();
        
        var env = EnvironmentVariables2.build( ns.get("mount") );

        try (Mount mount = mountBuilder.mount(ns.get("repo"), env)) {
            System.out.println("Press Enter to unmount...");
            System.in.read();
        }

    }

}
