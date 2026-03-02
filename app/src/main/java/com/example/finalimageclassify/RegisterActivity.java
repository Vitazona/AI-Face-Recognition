package com.example.finalimageclassify;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RegisterActivity
 * ----------------
 * Flow:
 *   1. Live camera preview shown
 *   2. User presses "Capture" → 3 photos taken automatically with 500ms delay
 *   3. Each photo → TFLite embedding
 *   4. Name dialog appears
 *   5. Averaged embedding + name saved to faces.json
 *   6. Returns to MainActivity
 */
public class RegisterActivity extends AppCompatActivity {

    private static final int    PHOTO_COUNT     = 5;      // Number of rapid captures
    private static final long   CAPTURE_DELAY   = 500L;  // ms between captures
    private static final int    CAMERA_PERM_REQ = 100;

    private PreviewView         previewView;
    private Button              btnCapture;
    private TextView            tvStatus;

    private ImageCapture        imageCapture;
    private ExecutorService     cameraExecutor;
    private EmbeddingHelper     embeddingHelper;

    private final List<float[]> capturedEmbeddings = new ArrayList<>();
    private int                 photosTaken        = 0;
    private boolean             isCapturing        = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        previewView = findViewById(R.id.previewView);
        btnCapture  = findViewById(R.id.btnCapture);
        tvStatus    = findViewById(R.id.tvStatus);

        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            embeddingHelper = new EmbeddingHelper(this);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to load model: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (hasCameraPermission()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERM_REQ);
        }

        btnCapture.setOnClickListener(v -> startRapidCapture());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (embeddingHelper != null) embeddingHelper.close();
    }

    // -------------------------------------------------------------------------
    // Camera setup (CameraX)
    // -------------------------------------------------------------------------

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setTargetRotation(Surface.ROTATION_0)
                        .build();

                provider.unbindAll();
                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        imageCapture
                );

            } catch (Exception e) {
                Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // -------------------------------------------------------------------------
    // Rapid capture sequence
    // -------------------------------------------------------------------------

    private void startRapidCapture() {
        if (isCapturing) return;

        isCapturing         = true;
        photosTaken         = 0;
        capturedEmbeddings.clear();

        btnCapture.setEnabled(false);
        tvStatus.setText("Capturing photo 1 of " + PHOTO_COUNT + "...");

        scheduleNextCapture();
    }

    private void scheduleNextCapture() {
        mainHandler.postDelayed(this::captureOnce, photosTaken == 0 ? 0 : CAPTURE_DELAY);
    }

    private void captureOnce() {
        if (imageCapture == null) return;

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                Bitmap bitmap = imageProxyToBitmap(imageProxy);
                imageProxy.close();

                if (bitmap != null) {
                    float[] embedding = embeddingHelper.getEmbedding(bitmap);
                    if (embedding != null) {
                        capturedEmbeddings.add(embedding);
                    }
                }

                photosTaken++;

                mainHandler.post(() -> {
                    if (photosTaken < PHOTO_COUNT) {
                        tvStatus.setText("Capturing photo " + (photosTaken + 1) + " of " + PHOTO_COUNT + "...");
                        scheduleNextCapture();
                    } else {
                        onAllPhotosCaptured();
                    }
                });
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                photosTaken++;
                mainHandler.post(() -> {
                    if (photosTaken < PHOTO_COUNT) {
                        scheduleNextCapture();
                    } else {
                        onAllPhotosCaptured();
                    }
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // After all captures done
    // -------------------------------------------------------------------------

    private void onAllPhotosCaptured() {
        isCapturing = false;

        if (capturedEmbeddings.isEmpty()) {
            tvStatus.setText("No valid embeddings captured. Try again.");
            btnCapture.setEnabled(true);
            return;
        }

        tvStatus.setText(capturedEmbeddings.size() + "/" + PHOTO_COUNT +
                " photos captured. Enter name...");

        showNameDialog();
    }

    private void showNameDialog() {
        android.widget.EditText editText = new android.widget.EditText(this);
        editText.setHint("Enter person's name");

        new AlertDialog.Builder(this)
                .setTitle("Register Face")
                .setMessage("Who is this person?")
                .setView(editText)
                .setCancelable(false)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = editText.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Name cannot be empty.", Toast.LENGTH_SHORT).show();
                        showNameDialog(); // re-show
                        return;
                    }
                    saveFace(name);
                })
                .setNegativeButton("Discard", (dialog, which) -> {
                    capturedEmbeddings.clear();
                    tvStatus.setText("Discarded. Press Capture to try again.");
                    btnCapture.setEnabled(true);
                })
                .show();
    }

    private void saveFace(String name) {
        // Average all captured embeddings into one reference vector
        float[] averaged = averageEmbeddings(capturedEmbeddings);

        try {
            FaceStorageHelper.saveFace(this, name, averaged);
            Toast.makeText(this, name + " registered!", Toast.LENGTH_SHORT).show();
            finish(); // Return to MainActivity
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            btnCapture.setEnabled(true);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private float[] averageEmbeddings(List<float[]> embeddings) {
        int dim = embeddings.get(0).length;
        float[] avg = new float[dim];
        for (float[] emb : embeddings) {
            for (int i = 0; i < dim; i++) avg[i] += emb[i];
        }
        for (int i = 0; i < dim; i++) avg[i] /= embeddings.size();
        return avg;
    }

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            // CameraX returns YUV_420_888 — convert via JPEG encoding
            ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
            java.nio.ByteBuffer buffer = planes[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            // Use BitmapFactory if format is JPEG (depends on device)
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);

            if (bitmap != null) return bitmap;

            // Fallback: convert YUV manually using RenderScript-free method
            return yuvImageProxyToBitmap(imageProxy);
        } catch (Exception e) {
            return yuvImageProxyToBitmap(imageProxy);
        }
    }

    private Bitmap yuvImageProxyToBitmap(ImageProxy imageProxy) {
        try {
            // Convert YUV_420_888 to NV21, then to JPEG, then to Bitmap
            ImageProxy.PlaneProxy yPlane  = imageProxy.getPlanes()[0];
            ImageProxy.PlaneProxy uPlane  = imageProxy.getPlanes()[1];
            ImageProxy.PlaneProxy vPlane  = imageProxy.getPlanes()[2];

            java.nio.ByteBuffer yBuffer = yPlane.getBuffer();
            java.nio.ByteBuffer uBuffer = uPlane.getBuffer();
            java.nio.ByteBuffer vBuffer = vPlane.getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(
                    nv21, android.graphics.ImageFormat.NV21,
                    imageProxy.getWidth(), imageProxy.getHeight(), null);

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            yuvImage.compressToJpeg(
                    new android.graphics.Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()),
                    90, out);

            byte[] jpegBytes = out.toByteArray();
            return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERM_REQ &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show();
            finish();
        }
    }
}