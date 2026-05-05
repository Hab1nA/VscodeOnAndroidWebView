package com.vscodeonandroid.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.tabs.TabLayout;

public class MainActivity extends AppCompatActivity {

    private TabLayout tabLayout;
    private DashboardFragment dashboardFragment;
    private WebViewFragment webViewFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tabLayout = findViewById(R.id.tabLayout);

        // Create fragments
        dashboardFragment = new DashboardFragment();
        webViewFragment = new WebViewFragment();

        // Set up tabs
        tabLayout.addTab(tabLayout.newTab().setText("📋 仪表盘"));
        tabLayout.addTab(tabLayout.newTab().setText("💻 VSCode"));

        // Show dashboard by default
        getSupportFragmentManager().beginTransaction()
                .add(R.id.contentFrame, dashboardFragment, "DASHBOARD")
                .commit();

        // Tab selection listener
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                switchFragment(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                // No-op
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                // Reload WebView if VSCode tab is re-selected
                if (tab.getPosition() == 1 && webViewFragment != null) {
                    webViewFragment.reload();
                }
            }
        });
    }

    private void switchFragment(int position) {
        FragmentManager fm = getSupportFragmentManager();
        Fragment target;
        String tag;

        if (position == 0) {
            target = fm.findFragmentByTag("DASHBOARD");
            if (target == null) target = dashboardFragment;
            tag = "DASHBOARD";
        } else {
            target = fm.findFragmentByTag("WEBVIEW");
            if (target == null) target = webViewFragment;
            tag = "WEBVIEW";
        }

        Fragment current = fm.findFragmentById(R.id.contentFrame);
        if (current != null && current.getTag() != null && current.getTag().equals(tag)) {
            return; // Already showing this fragment
        }

        // Use a single atomic transaction to avoid IllegalStateException
        androidx.fragment.app.FragmentTransaction transaction = fm.beginTransaction();

        if (target.isAdded()) {
            transaction.show(target);
        } else {
            transaction.add(R.id.contentFrame, target, tag);
        }

        // Hide other fragments
        for (Fragment f : new Fragment[]{
                fm.findFragmentByTag("DASHBOARD"),
                fm.findFragmentByTag("WEBVIEW")
        }) {
            if (f != null && f != target && f.isAdded()) {
                transaction.hide(f);
            }
        }

        transaction.commit();
    }

    @Override
    public void onBackPressed() {
        // If on WebView tab and WebView can go back, navigate back in WebView
        if (tabLayout.getSelectedTabPosition() == 1 && webViewFragment != null && webViewFragment.canGoBack()) {
            webViewFragment.goBack();
        } else {
            super.onBackPressed();
        }
    }
}