package com.tools.cnc;

import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.tools.cnc.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private NavController navController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Android 15 (targetSdk 35) always draws edge to edge, and the status bar colour
        // is controlled by the app from API 35 on. Handle the system bar areas ourselves
        // so the status bar always matches the app bar and its icons stay readable.
        setUpSystemBars();

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        applyWindowInsets();

        setSupportActionBar(binding.toolbar);

        NavHostFragment navHostFragment = (NavHostFragment)
                getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        navController = navHostFragment.getNavController();
        NavigationUI.setupWithNavController(binding.toolbar, navController);
    }

    /** Draw edge to edge and pick a system bar icon colour that is visible on our surface. */
    private void setUpSystemBars() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Clear the platform default colours so the areas behind the bars show our own
            // background instead of an opaque system colour. From API 35 these calls are
            // ignored by the platform (the window is always edge to edge).
            window.setStatusBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                window.setNavigationBarColor(Color.TRANSPARENT);
            }
        }

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        // The strip behind the status bar is the blue app bar -> light (white) icons.
        controller.setAppearanceLightStatusBars(false);
        // The strip behind the navigation bar is the page background -> follow the theme.
        controller.setAppearanceLightNavigationBars(!isNightMode());
    }

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Insets the app bar and the navigation host manually, now that the window itself is
     * no longer inset by the system. Without this the page title would sit under the status
     * bar / notch and the bottom of every page would sit under the navigation bar.
     */
    private void applyWindowInsets() {
        final int appBarPaddingStart = binding.appBar.getPaddingStart();
        final int appBarPaddingEnd = binding.appBar.getPaddingEnd();
        final int appBarPaddingBottom = binding.appBar.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPaddingRelative(appBarPaddingStart + bars.left, bars.top,
                    appBarPaddingEnd + bars.right, appBarPaddingBottom);
            return windowInsets;
        });

        ViewCompat.setOnApplyWindowInsetsListener(binding.navHostFragment, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(cutout.left, 0, cutout.right,
                    Math.max(Math.max(bars.bottom, cutout.bottom), ime.bottom));
            return windowInsets;
        });

        ViewCompat.requestApplyInsets(binding.getRoot());
    }

    @Override
    public boolean onSupportNavigateUp() {
        return navController.navigateUp() || super.onSupportNavigateUp();
    }
}
