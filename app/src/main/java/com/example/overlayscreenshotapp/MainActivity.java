package com.example.overlayscreenshotapp;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private ActivityResultLauncher<Intent> overlayPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (Settings.canDrawOverlays(this)) {
                    startFloatingButtonService();
                } else {
                    Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show();
                }
            });

    private ActivityResultLauncher<Intent> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    sendImageUriToService(imageUri);
                    finish(); // Close the activity after picking the image
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check the action to determine what to do
        String action = getIntent().getAction();
        if ("ACTION_PICK_IMAGE".equals(action)) {
            openImagePicker();
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    startFloatingButtonService();
                } else {
                    requestOverlayPermission();
                }
            } else {
                startFloatingButtonService();
            }
        }
    }

    private void startFloatingButtonService() {
        Intent intent = new Intent(this, FloatingButtonService.class);
        startService(intent);
        finish(); // Close the MainActivity once the service is started
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        overlayPermissionLauncher.launch(intent);
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        imagePickerLauncher.launch(intent);
    }

    private void sendImageUriToService(Uri imageUri) {
        Intent intent = new Intent(this, FloatingButtonService.class);
        intent.setAction("SELECTED_IMAGE");
        intent.putExtra("imageUri", imageUri.toString());
        startService(intent);
    }
}