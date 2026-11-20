package org.cryptomator.frontend.fuse;

import org.cryptomator.frontend.fuse.mount.EnvironmentVariables;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;

public final class EnvironmentVariables2 {

    private static final FileNameTranscoder transcoder = new FileNameTranscoder(
        StandardCharsets.UTF_8,
        StandardCharsets.UTF_8,
        Normalizer.Form.NFC,
        Normalizer.Form.NFC
    );

    public static EnvironmentVariables build(Path mnt, String volname) {
        return EnvironmentVariables.create()
            .withMountPoint(mnt)
            .withFileNameTranscoder(transcoder)
            .withFlags(new String[] {
                "-o", "volname=" + volname,
                "-o", "uid=-1",
                "-o", "gid=-1"
            })
            .build();
    }

}
