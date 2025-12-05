package com.example.shaketosave;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ShakeDetector.OnShakeListener {

    private static final String PREFS_NAME = "SafeShakePrefs";
    private static final String KEY_EMAIL = "emergency_email";
    private static final String KEY_NAME = "user_name";
    private static final String KEY_SENDER_EMAIL = "sender_email";
    private static final String KEY_APP_PASSWORD = "app_password";
    private static final String KEY_SERVICE_ENABLED = "service_enabled";
    private static final int LOCATION_PERMISSION_REQUEST = 1001;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1002;
    private static final int BACKGROUND_LOCATION_REQUEST = 1003;
    private static final int SHAKE_THRESHOLD = 2;
    private static final int COUNTDOWN_SECONDS = 3;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private ShakeDetector shakeDetector;
    private Vibrator vibrator;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private TextInputEditText editEmail, editName, editSenderEmail, editAppPassword;
    private SwitchMaterial switchShake;
    private MaterialButton btnTestSOS;
    private TextView statusText, locationText, sosPreview;
    private View statusIndicator;
    private ImageView shakeIcon;

    private boolean isShakeEnabled = true;
    private double currentLatitude = 0.0;
    private double currentLongitude = 0.0;
    private boolean hasLocation = false;
    private boolean isSOSDialogShowing = false;
    private AlertDialog sosDialog;
    private CountDownTimer countDownTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        initSensors();
        initLocation();
        loadSavedData();
        setupListeners();
        checkPermissions();
    }

    private void checkPermissions() {
        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQUEST);
            }
        }
        checkLocationPermission();
    }

    private void initViews() {
        editEmail = findViewById(R.id.editEmail);
        editName = findViewById(R.id.editName);
        editSenderEmail = findViewById(R.id.editSenderEmail);
        editAppPassword = findViewById(R.id.editAppPassword);
        switchShake = findViewById(R.id.switchShake);
        btnTestSOS = findViewById(R.id.btnTestSOS);
        statusText = findViewById(R.id.statusText);
        statusIndicator = findViewById(R.id.statusIndicator);
        shakeIcon = findViewById(R.id.shakeIcon);
        locationText = findViewById(R.id.locationText);
        sosPreview = findViewById(R.id.sosPreview);
    }

    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        shakeDetector = new ShakeDetector();
        shakeDetector.setOnShakeListener(this);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    }

    private void initLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    currentLatitude = location.getLatitude();
                    currentLongitude = location.getLongitude();
                    hasLocation = true;
                    updateLocationUI();
                    updateSOSPreview();
                }
            }
        };
    }

    private void checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST);
        } else {
            startLocationUpdates();
        }
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .setMinUpdateIntervalMillis(5000)
                .build();
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    private void updateLocationUI() {
        if (hasLocation) {
            locationText.setText(String.format(Locale.US, "📍 Location: %.6f, %.6f", currentLatitude, currentLongitude));
        } else {
            locationText.setText("📍 Location: Waiting for GPS...");
        }
    }

    private void loadSavedData() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        editEmail.setText(prefs.getString(KEY_EMAIL, ""));
        editName.setText(prefs.getString(KEY_NAME, ""));
        editSenderEmail.setText(prefs.getString(KEY_SENDER_EMAIL, ""));
        editAppPassword.setText(prefs.getString(KEY_APP_PASSWORD, ""));
        
        // Load service state
        boolean serviceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, false);
        switchShake.setChecked(serviceEnabled);
        isShakeEnabled = serviceEnabled;
        
        updateSOSPreview();
    }

    private void saveData() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(KEY_EMAIL, getTextValue(editEmail));
        editor.putString(KEY_NAME, getTextValue(editName));
        editor.putString(KEY_SENDER_EMAIL, getTextValue(editSenderEmail));
        editor.putString(KEY_APP_PASSWORD, getTextValue(editAppPassword));
        editor.apply();
    }

    private void setupListeners() {
        switchShake.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isShakeEnabled = isChecked;
            updateStatusUI();
            
            // Save service state
            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            editor.putBoolean(KEY_SERVICE_ENABLED, isChecked);
            editor.apply();
            
            if (isChecked) {
                registerShakeListener();
                startShakeService();
            } else {
                unregisterShakeListener();
                stopShakeService();
            }
        });

        btnTestSOS.setOnClickListener(v -> showSOSCountdownDialog());

        editName.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) updateSOSPreview();
        });
    }

    private void startShakeService() {
        // Save data first so service can access it
        saveData();
        
        Intent serviceIntent = new Intent(this, ShakeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopShakeService() {
        Intent serviceIntent = new Intent(this, ShakeService.class);
        stopService(serviceIntent);
    }

    private void updateStatusUI() {
        if (isShakeEnabled) {
            statusText.setText(R.string.shake_status_ready);
            statusText.setTextColor(ContextCompat.getColor(this, R.color.success));
            statusIndicator.setBackgroundResource(R.drawable.status_indicator);
        } else {
            statusText.setText(R.string.shake_status_disabled);
            statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_inactive);
        }
    }

    private void updateSOSPreview() {
        String name = getTextValue(editName);
        if (TextUtils.isEmpty(name)) name = "[Your Name]";

        String locationStr;
        String mapsLink;
        if (hasLocation) {
            locationStr = String.format(Locale.US, "%.6f, %.6f", currentLatitude, currentLongitude);
            mapsLink = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", currentLatitude, currentLongitude);
        } else {
            locationStr = "[GPS coordinates]";
            mapsLink = "[Google Maps link]";
        }

        String preview = "🚨 EMERGENCY SOS ALERT 🚨\n\n" +
                "This is " + name + ".\n" +
                "I am in DANGER and need IMMEDIATE HELP!\n\n" +
                "📍 My Location:\n" +
                "Coordinates: " + locationStr + "\n" +
                "Google Maps: " + mapsLink + "\n\n" +
                "⚠️ Please contact emergency services immediately!";

        sosPreview.setText(preview);
    }

    @Override
    public void onShake(int count) {
        if (!isShakeEnabled || isSOSDialogShowing) return;
        if (count < SHAKE_THRESHOLD) return;

        // Vibrate to provide feedback
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 200, 100, 200}, -1));
        }

        // Animate the icon
        Animation shake = AnimationUtils.loadAnimation(this, R.anim.shake);
        shakeIcon.startAnimation(shake);

        runOnUiThread(this::showSOSCountdownDialog);
    }

    private void showSOSCountdownDialog() {
        if (isSOSDialogShowing) return;

        // Validate inputs first
        if (!validateInputs()) return;

        isSOSDialogShowing = true;

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_sos_countdown, null);
        TextView countdownText = dialogView.findViewById(R.id.countdownText);
        MaterialButton btnCancel = dialogView.findViewById(R.id.btnCancel);
        MaterialButton btnSendNow = dialogView.findViewById(R.id.btnSendNow);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);
        builder.setCancelable(false);
        sosDialog = builder.create();

        btnCancel.setOnClickListener(v -> cancelSOS());

        btnSendNow.setOnClickListener(v -> {
            if (countDownTimer != null) {
                countDownTimer.cancel();
            }
            sendSOSEmail();
            dismissSOSDialog();
        });

        countDownTimer = new CountDownTimer(COUNTDOWN_SECONDS * 1000L, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) (millisUntilFinished / 1000) + 1;
                countdownText.setText(String.valueOf(secondsLeft));
                
                // Vibrate each second
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            }

            @Override
            public void onFinish() {
                countdownText.setText("0");
                sendSOSEmail();
                dismissSOSDialog();
            }
        };

        sosDialog.show();
        countDownTimer.start();
    }

    private void cancelSOS() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        dismissSOSDialog();
        showToast(getString(R.string.sos_cancelled));
        updateStatusUI();
    }

    private void dismissSOSDialog() {
        if (sosDialog != null && sosDialog.isShowing()) {
            sosDialog.dismiss();
        }
        isSOSDialogShowing = false;
    }

    private boolean validateInputs() {
        String email = getTextValue(editEmail);
        String name = getTextValue(editName);
        String senderEmail = getTextValue(editSenderEmail);
        String appPassword = getTextValue(editAppPassword);

        if (TextUtils.isEmpty(name)) {
            showToast(getString(R.string.error_empty_name));
            editName.requestFocus();
            return false;
        }

        if (TextUtils.isEmpty(email)) {
            showToast(getString(R.string.error_empty_email));
            editEmail.requestFocus();
            return false;
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showToast(getString(R.string.error_invalid_email));
            editEmail.requestFocus();
            return false;
        }

        if (TextUtils.isEmpty(senderEmail)) {
            showToast(getString(R.string.error_empty_sender));
            editSenderEmail.requestFocus();
            return false;
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(senderEmail).matches()) {
            showToast(getString(R.string.error_invalid_email));
            editSenderEmail.requestFocus();
            return false;
        }

        if (TextUtils.isEmpty(appPassword)) {
            showToast(getString(R.string.error_empty_password));
            editAppPassword.requestFocus();
            return false;
        }

        return true;
    }

    private void sendSOSEmail() {
        String recipientEmail = getTextValue(editEmail);
        String name = getTextValue(editName);
        String senderEmail = getTextValue(editSenderEmail);
        String appPassword = getTextValue(editAppPassword);

        // Save data
        saveData();

        // Update status
        statusText.setText(R.string.shake_status_sending);
        statusText.setTextColor(ContextCompat.getColor(this, R.color.warning));

        // Build SOS message
        String timestamp = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(new Date());

        String locationStr;
        String mapsLink;
        if (hasLocation) {
            locationStr = String.format(Locale.US, "%.6f, %.6f", currentLatitude, currentLongitude);
            mapsLink = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", currentLatitude, currentLongitude);
        } else {
            locationStr = "Location unavailable - GPS not enabled";
            mapsLink = "N/A";
        }

        String subject = "🚨 EMERGENCY SOS from " + name + " - URGENT HELP NEEDED!";

        String message = "🚨🚨🚨 EMERGENCY SOS ALERT 🚨🚨🚨\n\n" +
                "═══════════════════════════════\n" +
                "This is " + name + ".\n" +
                "I am in DANGER and need IMMEDIATE HELP!\n" +
                "═══════════════════════════════\n\n" +
                "📍 MY CURRENT LOCATION:\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "Coordinates: " + locationStr + "\n" +
                "Google Maps: " + mapsLink + "\n\n" +
                "🕐 Time of Alert: " + timestamp + "\n\n" +
                "⚠️ WHAT TO DO:\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "1. Call emergency services: 100 (Police) / 112 (Emergency)\n" +
                "2. Try to contact me immediately\n" +
                "3. Share my location with authorities\n" +
                "4. Come to my location if safe to do so\n\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "This is an automated SOS message sent via SafeShake app.\n" +
                "Please take this alert seriously!";

        // Send email using EmailSender
        new EmailSender(senderEmail, appPassword, recipientEmail, subject, message,
                new EmailSender.EmailCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUiThread(() -> {
                            statusText.setText(R.string.shake_status_sent);
                            statusText.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.success));
                            showToast("✅ SOS email sent successfully!");
                            
                            // Vibrate success pattern
                            if (vibrator != null && vibrator.hasVibrator()) {
                                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 100, 100, 100, 100, 100}, -1));
                            }
                        });
                    }

                    @Override
                    public void onFailure(String error) {
                        runOnUiThread(() -> {
                            statusText.setText(R.string.shake_status_failed);
                            statusText.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.sos_red));
                            showToast("❌ Failed to send: " + error);
                        });
                    }
                }).execute();
    }

    private String getTextValue(TextInputEditText editText) {
        return editText.getText() != null ? editText.getText().toString().trim() : "";
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void registerShakeListener() {
        if (accelerometer != null) {
            sensorManager.registerListener(shakeDetector, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    private void unregisterShakeListener() {
        sensorManager.unregisterListener(shakeDetector);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                showToast(getString(R.string.location_permission_needed));
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isShakeEnabled) {
            registerShakeListener();
        }
        updateStatusUI();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterShakeListener();
        saveData();
        
        // Start service when app goes to background if enabled
        if (isShakeEnabled) {
            startShakeService();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        fusedLocationClient.removeLocationUpdates(locationCallback);
        // Service keeps running in background
    }
}
