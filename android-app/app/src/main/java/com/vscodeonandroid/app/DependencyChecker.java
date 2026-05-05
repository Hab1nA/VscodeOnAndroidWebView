package com.vscodeonandroid.app;

import android.util.Log;

import java.io.File;

/**
 * Checks installation status of required dependencies:
 * - Python (3.x)
 * - Node.js
 * - code-server
 * - pip packages (from requirements or individual check)
 */
public class DependencyChecker {

    private static final String TAG = "DependencyChecker";
    private final TermuxExecutor executor;

    public DependencyChecker(TermuxExecutor executor) {
        this.executor = executor;
    }

    /**
     * Run a comprehensive dependency check and return formatted results.
     */
    public String checkAll() {
        StringBuilder report = new StringBuilder();
        report.append("=== 依赖状态检查 ===\n\n");

        report.append("Python 3:\n");
        report.append(checkPython()).append("\n\n");

        report.append("Node.js:\n");
        report.append(checkNode()).append("\n\n");

        report.append("npm:\n");
        report.append(checkNpm()).append("\n\n");

        report.append("code-server:\n");
        report.append(checkCodeServer()).append("\n\n");

        report.append("pip:\n");
        report.append(checkPip()).append("\n\n");

        return report.toString();
    }

    private String checkPython() {
        String result = executor.execute("python3 --version 2>&1 || python --version 2>&1");
        if (result.toLowerCase().contains("python")) {
            return "✅ " + result.trim();
        }
        return "❌ Python 3 未安装";
    }

    private String checkNode() {
        String result = executor.execute("node --version 2>&1");
        if (result.toLowerCase().contains("v")) {
            return "✅ " + result.trim();
        }
        return "❌ Node.js 未安装";
    }

    private String checkNpm() {
        String result = executor.execute("npm --version 2>&1");
        if (result.matches("\\d+.*")) {
            return "✅ v" + result.trim();
        }
        return "❌ npm 未安装";
    }

    private String checkCodeServer() {
        String result = executor.execute("code-server --version 2>&1");
        if (result.toLowerCase().contains("code-server") || result.matches("\\d+.*")) {
            return "✅ " + result.trim();
        }
        return "❌ code-server 未安装";
    }

    private String checkPip() {
        String result = executor.execute("pip3 --version 2>&1 || pip --version 2>&1");
        if (result.toLowerCase().contains("pip")) {
            return "✅ " + result.trim();
        }
        return "❌ pip 未安装";
    }
}