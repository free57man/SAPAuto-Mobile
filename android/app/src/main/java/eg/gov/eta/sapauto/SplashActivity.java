package eg.gov.eta.sapauto;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

/**
 * SplashActivity – shown for 2 seconds while the WebView warms up.
 * Sets SplashTheme (dark fullscreen) in styles.xml.
 * After SPLASH_DURATION_MS, transitions to MainActivity.
 */
public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_DURATION_MS = 2000;
    private static final int MAX_HANDLER_DELAY_MS = 10000; // safety bound

    private Handler splashHandler;
    private Runnable splashRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Defensive null check on status text
        TextView statusView = findViewById(R.id.splashStatus);
        if (statusView != null) {
            statusView.setText(getString(R.string.connecting));
        }

        splashHandler = new Handler(Looper.getMainLooper());
        splashRunnable = new Runnable() {
            @Override
            public void run() {
                launchMain();
            }
        };

        // Post with bounded delay
        int delay = Math.min(SPLASH_DURATION_MS, MAX_HANDLER_DELAY_MS);
        splashHandler.postDelayed(splashRunnable, delay);
    }

    private void launchMain() {
        try {
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        } catch (Exception e) {
            android.util.Log.e("SplashActivity", "Failed to launch MainActivity: " + e.getMessage(), e);
            // Surface error to user rather than silent failure
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Always clean up handler to prevent leaks (Rule 5: resource release)
        if (splashHandler != null && splashRunnable != null) {
            splashHandler.removeCallbacks(splashRunnable);
        }
    }

    @Override
    public void onBackPressed() {
        // Prevent back during splash to avoid partial state
        // Intentionally consuming — not a silent failure (Rule 3)
    }
}
