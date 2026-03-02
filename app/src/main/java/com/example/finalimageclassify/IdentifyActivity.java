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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IdentifyActivity
 * ----------------
 * Flow:
 *   1. Live camera preview
 *   2. User presses "Identify" → single photo captured
 *   3. Photo → TFLite embedding
 *   4. Compare against all saved embeddings (Euclidean distance)
 *   5. Show popup: best match name + confidence %
 *      - If distance > threshold → "Unknown"
 */
public class IdentifyActivity extends AppCompatActivity {

    /**
     * Recognition threshold on L2-normalized embeddings.
     * Range: [0, 2]. Embeddings with distance > threshold → "Unknown".
     *
     * Guidance:
     *   < 0.6  very strict  (same person, same lighting)
     *   0.7    recommended starting point
     *   > 1.0  too lenient
     */
    private static final float RECOGNITION_THRESHOLD = 0.75f;
    private static final int   CAMERA_PERM_REQ       = 101;

    private PreviewView     previewView;
    private Button          btnIdentify;
    private TextView        tvStatus;

    private ImageCapture    imageCapture;
    private ExecutorService cameraExecutor;
    private EmbeddingHelper embeddingHelper;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_identify);

        previewView  = findViewById(R.id.previewView);
        btnIdentify  = findViewById(R.id.btnIdentify);
        tvStatus     = findViewById(R.id.tvStatus);

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

        btnIdentify.setOnClickListener(v -> captureAndIdentify());
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
    // Capture and identify
    // -------------------------------------------------------------------------

    private void captureAndIdentify() {
        if (imageCapture == null) return;

        btnIdentify.setEnabled(false);
        tvStatus.setText("Capturing...");

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                Bitmap bitmap = imageProxyToBitmap(imageProxy);
                imageProxy.close();

                if (bitmap == null) {
                    mainHandler.post(() -> {
                        tvStatus.setText("Could not read image. Try again.");
                        btnIdentify.setEnabled(true);
                    });
                    return;
                }

                // Run embedding on background thread (already on cameraExecutor)
                float[] embedding = embeddingHelper.getEmbedding(bitmap);

                if (embedding == null) {
                    mainHandler.post(() -> {
                        tvStatus.setText("Embedding failed. Try again.");
                        btnIdentify.setEnabled(true);
                    });
                    return;
                }

                // Match against saved faces
                MatchResult result = findBestMatch(embedding);

                mainHandler.post(() -> {
                    showResultDialog(result);
                    btnIdentify.setEnabled(true);
                    tvStatus.setText("Ready");
                });
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                mainHandler.post(() -> {
                    tvStatus.setText("Capture error: " + exception.getMessage());
                    btnIdentify.setEnabled(true);
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // Matching logic
    // -------------------------------------------------------------------------

    private MatchResult findBestMatch(float[] queryEmbedding) {
        List<FaceStorageHelper.FaceRecord> records = FaceStorageHelper.loadAllFaces(this);

        if (records.isEmpty()) {
            return new MatchResult("No faces registered", 0f, Float.MAX_VALUE);
        }

        String bestName     = "Unknown";
        float  bestDistance = Float.MAX_VALUE;

        android.util.Log.d("FaceID", "======= MATCHING DISTANCES =======");
        for (FaceStorageHelper.FaceRecord record : records) {
            float distance = EmbeddingHelper.euclideanDistance(queryEmbedding, record.embedding);
            android.util.Log.d("FaceID", "  vs '" + record.name + "' distance: " + String.format("%.4f", distance));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestName     = record.name;
            }
        }
        android.util.Log.d("FaceID", "  Best: '" + bestName + "' @ " + String.format("%.4f", bestDistance));
        android.util.Log.d("FaceID", "  Threshold: " + RECOGNITION_THRESHOLD);
        android.util.Log.d("FaceID", "  Confidence: " + String.format("%.1f", EmbeddingHelper.distanceToConfidence(bestDistance)) + "%");
        android.util.Log.d("FaceID", "==================================");

        // Apply threshold
        if (bestDistance > RECOGNITION_THRESHOLD) {
            bestName = "Unknown";
        }

        float confidence = EmbeddingHelper.distanceToConfidence(bestDistance);
        return new MatchResult(bestName, confidence, bestDistance);
    }

    // -------------------------------------------------------------------------
    // Result popup
    // -------------------------------------------------------------------------

    private void showResultDialog(MatchResult result) {
        String title;
        String message;

        if (result.name.equals("Unknown") || result.name.equals("No faces registered")) {
            title   = "❓ Not Recognized";
            message = result.name.equals("No faces registered")
                    ? "No faces have been registered yet.\nGo back and register a face first."
                    : "This person is not in the database.\n\nClosest distance: " +
                    String.format("%.4f", result.distance) +
                    " (threshold: " + RECOGNITION_THRESHOLD + ")";
        } else {
            title   = "✅ Match Found!";
            message = "Name:       " + result.name + "\n" +
                    "Confidence: " + String.format("%.1f", result.confidence) + "%\n" +
                    "Distance:   " + String.format("%.4f", result.distance);
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    // -------------------------------------------------------------------------
    // Data class
    // -------------------------------------------------------------------------

    private static class MatchResult {
        final String name;
        final float  confidence;
        final float  distance;

        MatchResult(String name, float confidence, float distance) {
            this.name       = name;
            this.confidence = confidence;
            this.distance   = distance;
        }
    }

    // -------------------------------------------------------------------------
    // Image conversion helpers (same as RegisterActivity)
    // -------------------------------------------------------------------------

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
            java.nio.ByteBuffer buffer = planes[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap != null) return bitmap;
            return yuvImageProxyToBitmap(imageProxy);
        } catch (Exception e) {
            return yuvImageProxyToBitmap(imageProxy);
        }
    }

    private Bitmap yuvImageProxyToBitmap(ImageProxy imageProxy) {
        try {
            ImageProxy.PlaneProxy yPlane = imageProxy.getPlanes()[0];
            ImageProxy.PlaneProxy uPlane = imageProxy.getPlanes()[1];
            ImageProxy.PlaneProxy vPlane = imageProxy.getPlanes()[2];

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