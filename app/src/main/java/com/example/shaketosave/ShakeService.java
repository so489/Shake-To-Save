package com.example.shaketosave;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
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
    private static final String SOS_CHANNEL_ID = "SOSAlertChannel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int SOS_NOTIFICATION_ID = 1002;
    private static final String PREFS_NAME = "SafeShakePrefs";
    private static final int SHAKE_THRESHOLD = 2;
    private static final int COUNTDOWN_SECONDS = 3;

    public static final String ACTION_SEND_NOW = "com.example.shaketosave.SEND_NOW";
    public static final String ACTION_CANCEL_SOS = "com.example.shaketosave.CANCEL_SOS";

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private ShakeDetector shakeDetector;
    private Vibrator vibrator;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private NotificationManager notificationManager;
    private CountDownTimer countDownTimer;
    private Handler handler;

    private double currentLatitude = 0.0;
    private double currentLongitude = 0.0;
    private boolean hasLocation = false;
    private boolean isSendingSOS = false;
    private boolean isCountingDown = false;

    private BroadcastReceiver sosActionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_SEND_NOW.equals(action)) {
                cancelCountdown();
                sendSOSEmail();
            } else if (ACTION_CANCEL_SOS.equals(action)) {
                cancelSOS();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannels();
        initSensors();
        initLocation();
        registerSOSReceiver();
    }

    private void registerSOSReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SEND_NOW);
        filter.addAction(ACTION_CANCEL_SOS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sosActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(sosActionReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification());
        registerShakeListener();
        startLocationUpdates();
        return START_STICKY;
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Regular channel for background service
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SafeShake Protection",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps shake detection running in background");
            
            // High priority channel for SOS alerts
            NotificationChannel sosChannel = new NotificationChannel(
                    SOS_CHANNEL_ID,
                    "SOS Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            sosChannel.setDescription("Emergency SOS countdown and alerts");
            sosChannel.enableVibration(true);
            
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                notificationManager.createNotificationChannel(sosChannel);
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
        if (isSendingSOS || isCountingDown) return;
        if (count < SHAKE_THRESHOLD) return;

        // Vibrate to provide feedback
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 300, 200, 300, 200, 300}, -1));
        }

        // Start countdown
        startSOSCountdown();
    }

    private void startSOSCountdown() {
        isCountingDown = true;
        
        countDownTimer = new CountDownTimer(COUNTDOWN_SECONDS * 1000L, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) (millisUntilFinished / 1000) + 1;
                showCountdownNotification(secondsLeft);
                
                // Vibrate each second
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            }

            @Override
            public void onFinish() {
                isCountingDown = false;
                sendSOSEmail();
            }
        };
        
        countDownTimer.start();
    }

    private void showCountdownNotification(int secondsLeft) {
        // Send Now action
        Intent sendIntent = new Intent(ACTION_SEND_NOW);
        sendIntent.setPackage(getPackageName());
        PendingIntent sendPendingIntent = PendingIntent.getBroadcast(
                this, 1, sendIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Cancel action
        Intent cancelIntent = new Intent(ACTION_CANCEL_SOS);
        cancelIntent.setPackage(getPackageName());
        PendingIntent cancelPendingIntent = PendingIntent.getBroadcast(
                this, 2, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, SOS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sos)
                .setContentTitle("🚨 SOS ALERT - " + secondsLeft + " seconds")
                .setContentText("Emergency email will be sent automatically")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(0, "🚀 SEND NOW", sendPendingIntent)
                .addAction(0, "❌ CANCEL", cancelPendingIntent);

        if (notificationManager != null) {
            notificationManager.notify(SOS_NOTIFICATION_ID, builder.build());
        }
    }

    private void cancelCountdown() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        isCountingDown = false;
        dismissSOSNotification();
    }

    private void cancelSOS() {
        cancelCountdown();
        isSendingSOS = false;
        showResultNotification("SOS Cancelled", "Emergency alert was cancelled");
    }

    private void dismissSOSNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(SOS_NOTIFICATION_ID);
        }
    }

    private void sendSOSEmail() {
        isSendingSOS = true;
        dismissSOSNotification();
        showResultNotification("Sending SOS...", "Please wait");

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String recipientEmail = prefs.getString("emergency_email", "");
        String name = prefs.getString("user_name", "");
        String senderEmail = prefs.getString("sender_email", "");
        String appPassword = prefs.getString("app_password", "");

        if (recipientEmail.isEmpty() || name.isEmpty() || senderEmail.isEmpty() || appPassword.isEmpty()) {
            showResultNotification("SOS Failed", "Please configure email settings in app");
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
                        handler.post(() -> {
                            showResultNotification("✅ SOS Sent!", "Emergency email sent successfully");
                            // Success vibration
                            if (vibrator != null && vibrator.hasVibrator()) {
                                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 100, 100, 100, 100, 100}, -1));
                            }
                        });
                        isSendingSOS = false;
                    }

                    @Override
                    public void onFailure(String error) {
                        handler.post(() -> showResultNotification("❌ SOS Failed", "Error: " + error));
                        isSendingSOS = false;
                    }
                }).execute();
    }

    private void showResultNotification(String title, String message) {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, SOS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sos)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        if (notificationManager != null) {
            notificationManager.notify(SOS_NOTIFICATION_ID, builder.build());
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
        cancelCountdown();
        unregisterShakeListener();
        try {
            unregisterReceiver(sosActionReceiver);
        } catch (Exception ignored) {}
        if (fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }
}
