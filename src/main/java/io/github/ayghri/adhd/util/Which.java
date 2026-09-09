package io.github.ayghri.adhd.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Port of {@code shutil.which}. */
public final class Which {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private Which() {}

    /** Returns the resolved executable path, or {@code null} when the command is not on PATH. */
    public static String which(String command) {
        if (command.contains(File.separator) || command.contains("/")) {
            Path direct = Path.of(command);
            return isExecutable(direct) ? direct.toString() : null;
        }

        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) {
            return null;
        }

        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            for (String candidateName : withExtensions(command)) {
                Path candidate = Path.of(entry).resolve(candidateName);
                if (isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    /** On Windows a bare name has to be probed against every PATHEXT suffix. */
    private static List<String> withExtensions(String command) {
        List<String> names = new ArrayList<>();
        if (!WINDOWS) {
            names.add(command);
            return names;
        }
        String pathext = System.getenv("PATHEXT");
        if (pathext == null || pathext.isBlank()) {
            pathext = ".COM;.EXE;.BAT;.CMD";
        }
        String lower = command.toLowerCase(Locale.ROOT);
        for (String ext : pathext.split(";")) {
            if (!ext.isBlank() && lower.endsWith(ext.toLowerCase(Locale.ROOT))) {
                names.add(command);
                return names;
            }
        }
        for (String ext : pathext.split(";")) {
            if (!ext.isBlank()) {
                names.add(command + ext);
            }
        }
        return names;
    }

    private static boolean isExecutable(Path candidate) {
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }
}
