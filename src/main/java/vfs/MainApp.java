package vfs;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.impl.Arguments;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Function;

public class MainApp {

    private record AppArgs(
        Path repo,
        Path mount
    ) {}

    private static AppArgs parse_args(String[] args) {

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

        return new AppArgs(
            ns.<File>get("repo").toPath(),
            ns.<File>get("mount").toPath()
        );
    }

    public static void main(String[] args) throws Exception {

        AppArgs ns = parse_args(args);

        GitLfsVfs vfs = new GitLfsVfs();

        vfs.mountPath = ns.mount();
        vfs.repo = new GitRepo( ns.repo() );

        vfs.mount();

    }

}
