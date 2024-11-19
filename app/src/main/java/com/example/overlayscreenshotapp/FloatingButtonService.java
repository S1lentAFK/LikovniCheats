package com.example.overlayscreenshotapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FloatingButtonService extends Service {

    private WindowManager windowManager;
    private View floatingButtonView;
    private WindowManager.LayoutParams params;
    private boolean isDraggable = false;
    private Handler handler = new Handler();
    private Runnable longPressRunnable;
    private ExecutorService executorService;

    private static final String SERVER_URL = "http://16.171.6.10:80/";

    @Override
    public void onCreate() {
        super.onCreate();
        executorService = Executors.newSingleThreadExecutor();
        setupFloatingButton();
    }

    private void setupFloatingButton() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        floatingButtonView = inflater.inflate(R.layout.overlay_button, null);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;

        windowManager.addView(floatingButtonView, params);

        ImageButton floatingButton = floatingButtonView.findViewById(R.id.floatingButton);

        floatingButton.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private long pressStartTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        pressStartTime = System.currentTimeMillis();
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();

                        longPressRunnable = new Runnable() {
                            @Override
                            public void run() {
                                isDraggable = true;
                                vibrate();
                            }
                        };
                        handler.postDelayed(longPressRunnable, 500);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        long pressDuration = System.currentTimeMillis() - pressStartTime;
                        if (pressDuration > 500) {
                            isDraggable = true;
                        }

                        if (isDraggable) {
                            params.x = initialX + (int) (event.getRawX() - initialTouchX);
                            params.y = initialY + (int) (event.getRawY() - initialTouchY);
                            windowManager.updateViewLayout(floatingButtonView, params);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(longPressRunnable);
                        long totalPressDuration = System.currentTimeMillis() - pressStartTime;

                        if (totalPressDuration < 500 && !isDraggable) {
                            // Short tap - open image picker
                            Intent openImagePicker = new Intent(FloatingButtonService.this, MainActivity.class);
                            openImagePicker.setAction("ACTION_PICK_IMAGE");
                            openImagePicker.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(openImagePicker);
                        }
                        isDraggable = false;
                        return true;
                }
                return false;
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if ("SELECTED_IMAGE".equals(intent.getAction())) {
                String imageUriString = intent.getStringExtra("imageUri");
                if (imageUriString != null) {
                    Uri imageUri = Uri.parse(imageUriString);
                    sendImageToServer(imageUri);
                }
            }
        }
        return START_STICKY;
    }

    private void sendImageToServer(Uri imageUri) {
        executorService.execute(() -> {
            try {
                // Convert image to Base64
                String base64Image = encodeImageToBase64(imageUri);

                // Create JSON payload
                JSONObject payload = new JSONObject();
                payload.put("base64_image", base64Image); // Updated key

                // Send POST request to the server
                URL url = new URL(SERVER_URL + "analyze");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                // Write the payload
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload.toString().getBytes("UTF-8"));
                    os.flush();
                }

                // Read response
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    StringBuilder response = new StringBuilder();
                    try (InputStream is = connection.getInputStream();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                    }

                    // Notify success
                    ShowInfoToast("Server Response", response.toString());
                } else {
                    // Notify error
                    String errorMsg = "Error Code: " + responseCode;
                    sendNotification("Server Error", errorMsg);
                }

            } catch (Exception e) {
                e.printStackTrace();
                sendNotification("Error", "Failed to send image: " + e.getMessage());
            }
        });
    }

    private void ShowInfoToast(String serverResponse, final String string) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(FloatingButtonService.this, string, Toast.LENGTH_LONG).show()
        );
    }

    private String encodeImageToBase64(Uri imageUri) throws Exception {
        try (InputStream inputStream = getContentResolver().openInputStream(imageUri);
             ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }
            byte[] imageBytes = byteArrayOutputStream.toByteArray();
            return Base64.encodeToString(imageBytes, Base64.NO_WRAP); // Updated encoding flag
        }
    }


    private void sendNotification(String title, String message) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = "overlay_channel_id";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "Overlay Button Channel", NotificationManager.IMPORTANCE_DEFAULT);
            notificationManager.createNotificationChannel(channel);
        }

        // Use BigTextStyle for longer content
        NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle()
                .bigText(message);  // Set the long message content

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(bigTextStyle)  // Apply BigTextStyle for extended content
                .setSmallIcon(R.drawable.ic_notification)
                .build();

        notificationManager.notify(1, notification);
    }

    private void vibrate() {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(50);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingButtonView != null) {
            windowManager.removeView(floatingButtonView);
        }
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
