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
import android.telephony.SmsManager;
import android.text.TextUtils;
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

import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ShakeDetector.OnShakeListener {

    private static final String PREFS_NAME = "SafeShakePrefs";
    private static final String KEY_PHONE = "emergency_phone";
    private static final String KEY_NAME = "user_name";
    private static final String KEY_SERVICE_ENABLED = "service_enabled";
    private static final int SMS_PERMISSION_REQUEST = 1002;
    private static final int RECORD_AUDIO_PERMISSION_REQUEST = 1004;
    private static final int SHAKE_THRESHOLD = 2;
    private static final int COUNTDOWN_SECONDS = 5;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private ShakeDetector shakeDetector;
    private Vibrator vibrator;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private TextInputEditText editPhone, editName;
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
        // Request all permissions together for better UX
        java.util.List<String> permissionsNeeded = new java.util.ArrayList<>();

        // SMS permission (required)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.SEND_SMS);
        }

        // Location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        // Record audio permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    SMS_PERMISSION_REQUEST);
        } else {
            startLocationUpdates();
        }
    }

    private void initViews() {
        editPhone = findViewById(R.id.editPhone);
        editName = findViewById(R.id.editName);
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

    private void startVoiceRecognitionService() {
        Intent serviceIntent = new Intent(this, VoiceRecognitionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopVoiceRecognitionService() {
        Intent serviceIntent = new Intent(this, VoiceRecognitionService.class);
        stopService(serviceIntent);
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
        editPhone.setText(prefs.getString(KEY_PHONE, ""));
        editName.setText(prefs.getString(KEY_NAME, ""));

        boolean serviceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, false);
        switchShake.setChecked(serviceEnabled);
        isShakeEnabled = serviceEnabled;

        updateSOSPreview();
    }

    private void saveData() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(KEY_PHONE, getTextValue(editPhone));
        editor.putString(KEY_NAME, getTextValue(editName));
        editor.apply();
    }

    private void setupListeners() {
        switchShake.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isShakeEnabled = isChecked;
            updateStatusUI();

            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            editor.putBoolean(KEY_SERVICE_ENABLED, isChecked);
            editor.apply();

            if (isChecked) {
                registerShakeListener();
                startShakeService();
                startVoiceRecognitionService();
            } else {
                unregisterShakeListener();
                stopShakeService();
                stopVoiceRecognitionService();
            }
        });

        btnTestSOS.setOnClickListener(v -> showSOSCountdownDialog());

        editName.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) updateSOSPreview();
        });
    }

    private void startShakeService() {
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

        String mapsLink;
        if (hasLocation) {
            mapsLink = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", currentLatitude, currentLongitude);
        } else {
            mapsLink = "[Google Maps link]";
        }

        String preview = "SOS ALERT! I'm " + name + ", I need HELP! " + mapsLink;

        sosPreview.setText(preview);
    }

    @Override
    public void onShake(int count) {
        if (!isShakeEnabled || isSOSDialogShowing) return;
        if (count < SHAKE_THRESHOLD) return;

        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 200, 100, 200}, -1));
            } else {
                vibrator.vibrate(new long[]{0, 200, 100, 200}, -1);
            }
        }

        Animation shake = AnimationUtils.loadAnimation(this, R.anim.shake);
        shakeIcon.startAnimation(shake);

        runOnUiThread(this::showSOSCountdownDialog);
    }

    private void showSOSCountdownDialog() {
        if (isSOSDialogShowing) return;
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
            if (countDownTimer != null) countDownTimer.cancel();
            sendSOS();
            dismissSOSDialog();
        });

        countDownTimer = new CountDownTimer(COUNTDOWN_SECONDS * 1000L, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) (millisUntilFinished / 1000) + 1;
                countdownText.setText(String.valueOf(secondsLeft));
                if (vibrator != null && vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vibrator.vibrate(100);
                    }
                }
            }

            @Override
            public void onFinish() {
                countdownText.setText("0");
                sendSOS();
                dismissSOSDialog();
            }
        };

        sosDialog.show();
        countDownTimer.start();
    }

    private void cancelSOS() {
        if (countDownTimer != null) countDownTimer.cancel();
        dismissSOSDialog();
        showToast(getString(R.string.sos_cancelled));
        updateStatusUI();
    }

    private void dismissSOSDialog() {
        if (sosDialog != null && sosDialog.isShowing()) sosDialog.dismiss();
        isSOSDialogShowing = false;
    }

    private boolean validateInputs() {
        String phone = getTextValue(editPhone);
        String name = getTextValue(editName);

        if (TextUtils.isEmpty(name)) {
            showToast(getString(R.string.error_empty_name));
            editName.requestFocus();
            return false;
        }

        if (TextUtils.isEmpty(phone)) {
            showToast(getString(R.string.error_empty_phone));
            editPhone.requestFocus();
            return false;
        }

        if (phone.length() < 10) {
            showToast(getString(R.string.error_invalid_phone));
            editPhone.requestFocus();
            return false;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            showToast(getString(R.string.sms_permission_needed));
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.SEND_SMS}, SMS_PERMISSION_REQUEST);
            return false;
        }

        return true;
    }

    private void sendSOS() {
        String phone = getTextValue(editPhone);
        String name = getTextValue(editName);

        saveData();

        statusText.setText(R.string.shake_status_sending);
        statusText.setTextColor(ContextCompat.getColor(this, R.color.warning));

        String mapsLink;
        if (hasLocation) {
            mapsLink = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", currentLatitude, currentLongitude);
        } else {
            mapsLink = "Location unavailable";
        }

        // Keep message short for SMS (160 char limit)
        String message = "SOS ALERT! I'm " + name + ", I need HELP! " + mapsLink;

        try {
            SmsManager smsManager = SmsManager.getDefault();

            // Split message if too long
            java.util.ArrayList<String> parts = smsManager.divideMessage(message);

            if (parts.size() > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null);
            } else {
                smsManager.sendTextMessage(phone, null, message, null, null);
            }

            statusText.setText(R.string.shake_status_sent);
            statusText.setTextColor(ContextCompat.getColor(this, R.color.success));
            showToast("SOS SMS sent to " + phone);

            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 100, 100, 100, 100, 100}, -1));
                } else {
                    vibrator.vibrate(new long[]{0, 100, 100, 100, 100, 100}, -1);
                }
            }
        } catch (Exception e) {
            statusText.setText(R.string.shake_status_failed);
            statusText.setTextColor(ContextCompat.getColor(this, R.color.sos_red));
            showToast("Failed to send SMS: " + e.getMessage());
        }
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

        boolean smsGranted = false;
        boolean locationGranted = false;
        boolean audioGranted = false;

        for (int i = 0; i < permissions.length; i++) {
            if (permissions[i].equals(Manifest.permission.SEND_SMS)) {
                smsGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            } else if (permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)) {
                locationGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            } else if (permissions[i].equals(Manifest.permission.RECORD_AUDIO)) {
                audioGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            }
        }

        if (!smsGranted && ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            // Show dialog explaining why SMS permission is needed
            new AlertDialog.Builder(this)
                    .setTitle("SMS Permission Required")
                    .setMessage("This app needs SMS permission to send emergency SOS messages. Please grant the permission in Settings.")
                    .setPositiveButton("Open Settings", (dialog, which) -> {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }

        if (locationGranted || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isShakeEnabled) registerShakeListener();
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
        if (isShakeEnabled) startShakeService();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) countDownTimer.cancel();
        fusedLocationClient.removeLocationUpdates(locationCallback);
    }
}
