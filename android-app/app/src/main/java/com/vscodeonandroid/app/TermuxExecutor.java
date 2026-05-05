package com.vscodeonandroid.app;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Executes shell scripts (install.sh / uninstall.sh) and shell commands.
 * Uses ProcessBuilder to run commands with su (root) or standard shell.
 */
public class TermuxExecutor {

    private static final String TAG = "TermuxExecutor";
    private final File scriptsDir;

    public TermuxExecutor(File scriptsDir) {
        this.scriptsDir = scriptsDir;
    }

    public File getScriptsDir() {
        return scriptsDir;
    }

    /**
     * Run a shell command and return its output.
     */
    public String execute(String command) {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(scriptsDir);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            reader.close();
        } catch (Exception e) {
            Log.e(TAG, "Command failed: " + command, e);
            output.append("[ERROR] ").append(e.getMessage());
        }
        return output.toString().trim();
    }

    /**
     * Run a command with superuser (root) privileges if available,
     * otherwise fall back to normal shell.
     */
    public String executeWithSu(String command) {
        // Try su first, fall back to sh
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", command);
            pb.directory(scriptsDir);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            int exitCode = process.waitFor();
            reader.close();
            if (exitCode != 0 && output.toString().trim().isEmpty()) {
                // su not available, fall back to sh
                return execute(command);
            }
        } catch (Exception e) {
            Log.w(TAG, "su not available, using normal shell", e);
            return execute(command);
        }
        return output.toString().trim();
    }

    /**
     * Run a script file (e.g. install.sh) from the scripts directory.
     */
    public String runScript(String scriptName) {
        File scriptFile = new File(scriptsDir, scriptName);
        if (!scriptFile.exists()) {
            return "[ERROR] Script not found: " + scriptFile.getAbsolutePath();
        }
        if (!scriptFile.canExecute()) {
            scriptFile.setExecutable(true);
        }
        return executeWithSu("cd \"" + scriptsDir.getAbsolutePath() + "\" && sh \"" + scriptFile.getAbsolutePath() + "\"");
    }

    /**
     * Execute install.sh
     */
    public String install() {
        return runScript("install.sh");
    }

    /**
     * Execute uninstall.sh
     */
    public String uninstall() {
        return runScript("uninstall.sh");
    }

    /**
     * Execute start_web_ui.sh
     */
    public String startWebUi() {
        return runScript("start_web_ui.sh");
    }
}