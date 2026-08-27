package org.yamcs.activities;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.yamcs.YamcsServer;
import org.yamcs.http.HttpServer;

import com.google.common.base.CharMatcher;

public class LocalScriptRun implements ScriptRun {

    private List<String> command;
    private List<String> scriptArgs;

    private Process process;

    /**
     * @param command
     *            the program to execute, already split into individual argv tokens (e.g. an interpreter and its options
     *            followed by the script path). Taken as-is; not re-parsed.
     * @param scriptArgs
     *            user-supplied arguments, each element passed to the process as exactly one argv token.
     */
    public LocalScriptRun(List<String> command, List<String> scriptArgs) {
        this.command = command;
        this.scriptArgs = scriptArgs;
    }

    List<String> getCommand() {
        return command;
    }

    List<String> getScriptArgs() {
        return scriptArgs;
    }

    @Override
    public void run(ScriptExecution scriptExecution) throws Exception {
        var cmdline = new ArrayList<String>(command);
        cmdline.addAll(scriptArgs);

        var yamcs = YamcsServer.getServer();
        var securityStore = yamcs.getSecurityStore();
        var httpServer = yamcs.getGlobalService(HttpServer.class);

        String apiKey = null;
        if (securityStore.isEnabled()) {
            var user = scriptExecution.getUser();
            apiKey = securityStore.generateApiKey(user.getName());
        }

        try {
            var pb = new ProcessBuilder(cmdline);
            pb.environment().put("YAMCS", "1");

            var yamcsInstance = scriptExecution.getYamcsInstance();
            pb.environment().put("YAMCS_INSTANCE", yamcsInstance);

            var processor = scriptExecution.getProcessor();
            if (processor != null) {
                pb.environment().put("YAMCS_PROCESSOR", processor);
            }

            var url = httpServer.getBindings().iterator().next() + httpServer.getContextPath();
            pb.environment().put("YAMCS_URL", url);

            if (apiKey != null) {
                pb.environment().put("YAMCS_API_KEY", apiKey);
            }

            process = pb.start();
            scriptExecution.logServiceInfo("Started process, pid=" + process.pid());

            new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    reader.lines().forEach(line -> {
                        line = CharMatcher.whitespace().trimTrailingFrom(line);
                        scriptExecution.logActivityInfo(line);
                    });
                } catch (IOException e) {
                    scriptExecution.log.error("Exception while gobbling process output", e);
                }
            }, getClass().getSimpleName() + " Gobbler").start();

            new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    reader.lines().forEach(line -> {
                        line = CharMatcher.whitespace().trimTrailingFrom(line);
                        scriptExecution.logActivityError(line);
                    });
                } catch (IOException e) {
                    scriptExecution.log.error("Exception while gobbling process error output", e);
                }
            }, getClass().getSimpleName() + " Gobbler").start();

            process.waitFor();
            var exitValue = process.exitValue();
            if (exitValue == 0) {
                scriptExecution.logServiceInfo("Process has terminated");
            } else {
                var errorMessage = "Process returned with exit value " + exitValue;
                scriptExecution.logServiceError(errorMessage);
                throw new RuntimeException(errorMessage);
            }
            return;
        } finally {
            if (apiKey != null) {
                securityStore.removeApiKey(apiKey);
            }
        }
    }

    @Override
    public void stop(ScriptExecution scriptExecution) throws Exception {
        if (process != null && process.isAlive()) {
            scriptExecution.log.debug("Destroying process {}", process.pid());
            process.destroy();
            process.onExit().get(2000, TimeUnit.MILLISECONDS);
            if (process.isAlive()) {
                scriptExecution.log.debug("Forcing destroy of process {}", process.pid());
                process.destroyForcibly();
                process.onExit().get();
            }
        }
    }
}
