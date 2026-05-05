package com.vscodeonandroid.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DashboardFragment extends Fragment {

    private TermuxExecutor termuxExecutor;
    private DependencyChecker dependencyChecker;
    private ServiceManager serviceManager;

    private TextView tvDepStatus;
    private TextView tvShellOutput;
    private TextView tvServiceStatus;

    private Button btnCheckDeps;
    private Button btnInstall;
    private Button btnUninstall;
    private Button btnStartAll;
    private Button btnStopAll;
    private Button btnRestartAll;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dashboard, container, false);

        // Initialize components
        File scriptsDir = new File("/data/data/com.termux/files/home/VscodeOnAndroidWebView");
        if (!scriptsDir.exists() || !scriptsDir.isDirectory()) {
            // Scripts directory not available — show helpful message instead of crashing
            tvShellOutput = view.findViewById(R.id.tvShellOutput);
            if (tvShellOutput != null) {
                tvShellOutput.setText("❌ 未检测到 Termux 环境\n"
                        + "请确保已安装 Termux 并在其中运行过 install.sh。\n"
                        + "预期路径: " + scriptsDir.getAbsolutePath());
            }
        }
        termuxExecutor = new TermuxExecutor(scriptsDir);
        dependencyChecker = new DependencyChecker(termuxExecutor);
        serviceManager = new ServiceManager(termuxExecutor);

        // Bind views
        tvDepStatus = view.findViewById(R.id.tvDepStatus);
        tvShellOutput = view.findViewById(R.id.tvShellOutput);
        tvServiceStatus = view.findViewById(R.id.tvServiceStatus);
        btnCheckDeps = view.findViewById(R.id.btnCheckDeps);
        btnInstall = view.findViewById(R.id.btnInstall);
        btnUninstall = view.findViewById(R.id.btnUninstall);
        btnStartAll = view.findViewById(R.id.btnStartAll);
        btnStopAll = view.findViewById(R.id.btnStopAll);
        btnRestartAll = view.findViewById(R.id.btnRestartAll);

        // Set up click listeners
        btnCheckDeps.setOnClickListener(v -> checkDependencies());
        btnInstall.setOnClickListener(v -> runInstall());
        btnUninstall.setOnClickListener(v -> runUninstall());
        btnStartAll.setOnClickListener(v -> startServices());
        btnStopAll.setOnClickListener(v -> stopServices());
        btnRestartAll.setOnClickListener(v -> restartServices());

        // Auto-check service status on load
        checkServiceStatus();

        return view;
    }

    private void checkDependencies() {
        setLoading(tvDepStatus, "⏳ 正在检查依赖...");
        executorService.execute(() -> {
            String result = dependencyChecker.checkAll();
            mainHandler.post(() -> tvDepStatus.setText(result));
        });
    }

    private void runInstall() {
        setLoading(tvShellOutput, "⏳ 正在执行 install.sh ...");
        executorService.execute(() -> {
            String result = termuxExecutor.install();
            mainHandler.post(() -> tvShellOutput.setText(result));
        });
    }

    private void runUninstall() {
        setLoading(tvShellOutput, "⏳ 正在执行 uninstall.sh ...");
        executorService.execute(() -> {
            String result = termuxExecutor.uninstall();
            mainHandler.post(() -> tvShellOutput.setText(result));
        });
    }

    private void startServices() {
        setLoading(tvServiceStatus, "⏳ 正在启动服务...");
        executorService.execute(() -> {
            String result = serviceManager.startAll();
            mainHandler.post(() -> {
                tvServiceStatus.setText(result);
                // Refresh status after a delay
                mainHandler.postDelayed(this::checkServiceStatus, 3000);
            });
        });
    }

    private void stopServices() {
        setLoading(tvServiceStatus, "⏳ 正在停止服务...");
        executorService.execute(() -> {
            String result = serviceManager.stopAll();
            mainHandler.post(() -> {
                tvServiceStatus.setText(result);
                mainHandler.postDelayed(this::checkServiceStatus, 1000);
            });
        });
    }

    private void restartServices() {
        setLoading(tvServiceStatus, "⏳ 正在重启服务...");
        executorService.execute(() -> {
            String result = serviceManager.restartAll();
            mainHandler.post(() -> {
                tvServiceStatus.setText(result);
                mainHandler.postDelayed(this::checkServiceStatus, 3000);
            });
        });
    }

    private void checkServiceStatus() {
        executorService.execute(() -> {
            String result = serviceManager.checkStatus();
            mainHandler.post(() -> tvServiceStatus.setText(result));
        });
    }

    private void setLoading(TextView textView, String message) {
        mainHandler.post(() -> textView.setText(message));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}