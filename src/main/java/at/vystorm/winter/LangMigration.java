package at.vystorm.winter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.Pattern;

/**
 * Up to 1.2.2 the bundled language files used MiniMessage-style placeholders such as {@code <date>}, which Core's
 * Lang never fills ({@code {date}} is its format). Copies on disk keep the old text, so rewrite only the known
 * placeholder names once and keep a backup of every changed file.
 */
final class LangMigration {
    static final Pattern OLD=Pattern.compile("<(date|phase|strength|holiday|mode|blocks|seconds|pending|reason|input|days|percent)>");
    private LangMigration() {}
    static String migrate(String text) { return OLD.matcher(text).replaceAll("{$1}"); }
    /** Returns the number of rewritten files. */
    static int migrateFolder(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface atomic, Path folder) throws IOException {
        if(!Files.isDirectory(folder))return 0;
        int changed=0;
        try(var files=Files.list(folder)) {
            for(Path file:files.filter(p->p.getFileName().toString().endsWith(".yml")).toList()) {
                String text=Files.readString(file,StandardCharsets.UTF_8);
                String next=migrate(text);
                if(next.equals(text))continue;
                Files.copy(file,file.resolveSibling(file.getFileName()+".bak-1.2"),StandardCopyOption.REPLACE_EXISTING);
                atomic.write(file,next.getBytes(StandardCharsets.UTF_8));
                changed++;
            }
        }
        return changed;
    }
}
