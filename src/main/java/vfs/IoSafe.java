package vfs;

import ru.serce.jnrfuse.ErrorCodes;

import java.io.IOException;

public final class IoSafe {

    @FunctionalInterface
    public interface FsOp {
        int run() throws IOException;
    }

    public static int run(FsOp op) {
        try {
            return op.run();
        } catch (IOException e) {
            return -ErrorCodes.EIO();
        }
    }

}
