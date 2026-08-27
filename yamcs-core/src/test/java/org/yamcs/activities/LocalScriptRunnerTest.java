package org.yamcs.activities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yamcs.YConfiguration;

public class LocalScriptRunnerTest {

    private LocalScriptRunner runnerFor(Path searchDir) {
        Map<String, Object> options = new HashMap<>();
        options.put("searchPath", List.of(searchDir.toString()));
        options.put("fileAssociations", new HashMap<>());
        return new LocalScriptRunner(YConfiguration.wrap(options));
    }

    @Test
    void listArgsAreNotResplitOnWhitespace(@TempDir Path dir) throws IOException {
        var script = dir.resolve("myscript.py");
        Files.writeString(script, "print('hi')\n");

        var runner = runnerFor(dir);
        var run = runner.createRun("myscript.py", List.of("-c badflag", "normal"));

        assertEquals(List.of("python", "-u", script.toString()), run.getCommand());
        assertEquals(List.of("-c badflag", "normal"), run.getScriptArgs());
    }

    @Test
    void executableScriptWithoutAssociation(@TempDir Path dir) throws IOException {
        var script = dir.resolve("run.sh");
        Files.writeString(script, "#!/bin/sh\necho hi\n");
        script.toFile().setExecutable(true);

        var runner = runnerFor(dir);
        var run = runner.createRun("run.sh", List.of("a b"));

        assertEquals(List.of(script.toString()), run.getCommand());
        assertEquals(List.of("a b"), run.getScriptArgs());
    }

    @Test
    void directoryTraversalIsBlocked(@TempDir Path dir) {
        var runner = runnerFor(dir);
        assertThrows(RuntimeException.class, () -> runner.createRun("../etc/passwd", List.of()));
    }

    @Test
    void parseScriptArgs_scalarIsTokenized() {
        assertEquals(List.of("--duration", "5"), ScriptExecutor.parseScriptArgs("--duration 5"));
        assertEquals(List.of("a", "b", "c"), ScriptExecutor.parseScriptArgs("  a   b c "));
    }

    @Test
    void parseScriptArgs_listElementsArePassedThroughVerbatim() {
        assertEquals(List.of("--message", "hello world", "-x"),
                ScriptExecutor.parseScriptArgs(List.of("--message", "hello world", "-x")));
    }

    @Test
    void parseScriptArgs_emptyOrMissing() {
        assertEquals(List.of(), ScriptExecutor.parseScriptArgs(null));
        assertEquals(List.of(), ScriptExecutor.parseScriptArgs(""));
        assertEquals(List.of(), ScriptExecutor.parseScriptArgs("   "));
        assertEquals(List.of(), ScriptExecutor.parseScriptArgs(List.of()));
    }
}
