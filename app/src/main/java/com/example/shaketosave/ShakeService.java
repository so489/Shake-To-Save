package com.example.shaketosave;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ShakeService extends Service implements ShakeDetector.OnShakeListener {

    private static final String CHANNEL_ID = "SafeShakeChannel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREFS_NAME = "SafeShakePrefs";
    private static final int SHAKE_THRESHOLD = 2;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private ShakeDetector shakeDetector;
    private Vibrator vibrator;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private double currentLatitude = 0.0;
    private double currentLongitude = 0.0;
    private boolean hasLocation = false;
    private boolean isSendingSOS = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initSensors();
        initLocation();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification());
        registerShakeListener();
        startLocationUpdates();
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SafeShake Protection",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps shake detection running in background");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SafeShake Active")
                .setContentText("Protection is running. Shake to send SOS.")
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
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
            public void onLocationResult(LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    currentLatitude = location.getLatitude();
                    currentLongitude = location.getLongitude();
                    hasLocation = true;
                }
            }
        };
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

    private void registerShakeListener() {
        if (accelerometer != null) {
            sensorManager.registerListener(shakeDetector, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    private void unregisterShakeListener() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(shakeDetector);
        }
    }

    @Override
    public void onShake(int count) {
        if (isSendingSOS) return;
        if (count < SHAKE_THRESHOLD) return;

        isSendingSOS = true;

        // Vibrate to provide feedback
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 300, 200, 300, 200, 300}, -1));
        }

        // Send SOS directly from background
        sendSOSEmail();
    }

    private void sendSOSEmail() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String recipientEmail = prefs.getString("emergency_email", "");
        String name = prefs.getString("user_name", "");
        String senderEmail = prefs.getString("sender_email", "");
        String appPassword = prefs.getString("app_password", "");

        if (recipientEmail.isEmpty() || name.isEmpty() || senderEmail.isEmpty() || appPassword.isEmpty()) {
            isSendingSOS = false;
            return;
        }

        String timestamp = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(new Date());

        String locationStr;
        String mapsLink;
        if (hasLocation) {
            locationStr = String.format(Locale.US, "%.6f, %.6f", currentLatitude, currentLongitude);
            mapsLink = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", currentLatitude, currentLongitude);
        } else {
            locationStr = "Location unavailable";
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
                "3. Share my location with authorities\n\n" +
                "━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "This is an automated SOS from SafeShake app.";

        new EmailSender(senderEmail, appPassword, recipientEmail, subject, message,
                new EmailSender.EmailCallback() {
                    @Override
                    public void onSuccess() {
                        showSOSNotification("SOS Sent!", "Emergency email sent successfully");
                        isSendingSOS = false;
                    }

                    @Override
                    public void onFailure(String error) {
                        showSOSNotification("SOS Failed", "Could not send email: " + error);
                        isSendingSOS = false;
                    }
                }).execute();
    }

    private void showSOSNotification(String title, String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sos)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID + 1, builder.build());
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterShakeListener();
        if (fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }
}
