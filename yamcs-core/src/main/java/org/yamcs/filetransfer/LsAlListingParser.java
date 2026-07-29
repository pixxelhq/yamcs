package org.yamcs.filetransfer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.yamcs.Spec;
import org.yamcs.Spec.OptionType;
import org.yamcs.YConfiguration;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.protobuf.RemoteFile;

/**
 * Parses a directory listing formatted as the output of {@code ls -al} (or {@code ls -la}), e.g.:
 *
 * <pre>
 * total 4
 * drwx------  2 user user   80 Jul 29 12:42 .
 * drwxrwxrwt 29 root root 1020 Jul 29 12:42 ..
 * -rw-r--r--  1 user user   28 Jul 29 12:42 myfile.txt
 * </pre>
 *
 * The {@code total N} line and the {@code .}/{@code ..} entries are always ignored.
 */
public class LsAlListingParser extends FileListingParser {

    // <type><9 permission chars>[+] <links> <owner> <group> <size> <month> <day> <time/year> <name>
    private static final Pattern LS_AL_PATTERN = Pattern.compile(
            "^([bcdlps-])[-rwxstST]{9}\\+?\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+\\S+\\s+\\S+\\s+\\S+\\s+(.+)$");

    private boolean skipFirstLine;

    private EventProducer eventProducer;

    @Override
    public Spec getSpec() {
        Spec spec = new Spec();
        spec.addOption("skipFirstLine", OptionType.BOOLEAN).withDefault(false)
                .withDescription("Skip the first line of the listing file. Some CFDP entities prepend a "
                        + "non-standard header line (e.g. \"Contents of directory ... generated with ...\") "
                        + "before the actual 'ls -al' output.");
        return spec;
    }

    @Override
    public void init(String yamcsInstance, YConfiguration config) {
        super.init(yamcsInstance, config);
        if (!"".equals(yamcsInstance)) {
            eventProducer = EventProducerFactory.getEventProducer(yamcsInstance, "LsAlListingParser", 10000);
        }
        skipFirstLine = config.getBoolean("skipFirstLine");
    }

    @Override
    public List<RemoteFile> parse(String remotePath, byte[] data) {
        String textData = new String(data);
        String[] lines = textData.replace("\r", "").split("\n");

        List<RemoteFile> files = new ArrayList<>();
        for (int i = skipFirstLine ? 1 : 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }

            Matcher m = LS_AL_PATTERN.matcher(line);
            if (!m.matches()) {
                // e.g. the "total N" line, or any other non file entry line
                continue;
            }

            String name = m.group(3);
            int arrowIdx = name.indexOf(" -> "); // strip symlink target, e.g. "link -> target"
            if (arrowIdx >= 0) {
                name = name.substring(0, arrowIdx);
            }
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }

            try {
                files.add(RemoteFile.newBuilder()
                        .setName(name)
                        .setIsDirectory("d".equals(m.group(1)))
                        .setSize(Long.parseLong(m.group(2)))
                        .build());
            } catch (NumberFormatException e) {
                if (eventProducer != null) {
                    eventProducer.sendWarning("Error parsing size for entry '" + line + "' in directory listing");
                }
            }
        }

        return files.stream().sorted(fileDirComparator).collect(Collectors.toList());
    }
}
