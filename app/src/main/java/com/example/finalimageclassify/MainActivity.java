package com.example.finalimageclassify;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    Button selectBtn, predictBtn, captureBtn;
    TextView result;
    Bitmap bitmap;
    ImageView imageView;

    private Interpreter interpreter;
    private boolean modelLoaded = false;

    // Store known faces (name + embedding)
    private List<FaceData> knownFaces = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        getPermission();

        // Add known faces (ADD YOUR IMAGES' EMBEDDINGS HERE)
        // For now, we'll add a sample. You need to generate embeddings for your known images
        addKnownFaces();

        selectBtn = findViewById(R.id.selectBtn);
        predictBtn = findViewById(R.id.predictBtn);
        captureBtn = findViewById(R.id.captureBtn);
        result = findViewById(R.id.result);
        imageView = findViewById(R.id.imageView);

        // Load model on startup
        loadModel();

        selectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent();
                intent.setAction(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                startActivityForResult(intent, 10);
            }
        });

        captureBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                startActivityForResult(intent, 12);
            }
        });

        predictBtn.setOnClickListener(new View.OnClickListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onClick(View view) {
                if (bitmap == null) {
                    Toast.makeText(MainActivity.this, "Select an image first", Toast.LENGTH_SHORT).show();
                    return;
                }

                try {
                    // Get embedding from the captured/selected image
                    float[] inputEmbedding = getFaceEmbedding(bitmap);

                    if (inputEmbedding == null) {
                        result.setText("Error processing image");
                        return;
                    }

                    // Compare with known faces
                    String recognizedName = recognizeFace(inputEmbedding);
                    result.setText(recognizedName);

                } catch (Exception e) {
                    result.setText("Error: " + e.getMessage());
                }
            }
        });
    }

    private void addKnownFaces() {
        // TODO: Add your known faces here
        // You need to generate embeddings for your reference images first

        // Example: Adding a sample face (placeholder - replace with actual embeddings)
        // To get embeddings: use the same getFaceEmbedding() function on your reference images
        // then copy the output array here

        // knownFaces.add(new FaceData("John", new float[]{0.1f, 0.2f, ...})); // 128 values

        // For testing, we'll leave it empty and you can add faces programmatically
    }

    private float[] getFaceEmbedding(Bitmap inputBitmap) {
        try {
            // Resize to 224x224
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(inputBitmap, 224, 224, true);

            // Create input array (1, 224, 224, 3)
            float[][][][] input = new float[1][224][224][3];

            // Normalize to 0-1
            for (int x = 0; x < 224; x++) {
                for (int y = 0; y < 224; y++) {
                    int pixel = scaledBitmap.getPixel(x, y);
                    input[0][x][y][0] = ((pixel >> 16) & 0xFF) / 255.0f;
                    input[0][x][y][1] = ((pixel >> 8) & 0xFF) / 255.0f;
                    input[0][x][y][2] = (pixel & 0xFF) / 255.0f;
                }
            }

            // Output: 128 (matching your model)
            float[][] output = new float[1][128];

            // Run the model
            interpreter.run(input, output);

            // Return the embedding (128 values)
            return output[0];

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String recognizeFace(float[] inputEmbedding) {
        if (knownFaces.isEmpty()) {
            return "No known faces in database.\nAdd faces first!";
        }

        double lowestDistance = Double.MAX_VALUE;
        String matchedName = "Not recognized";

        // Threshold for recognition (lower = stricter)
        // Typical values: 0.6 - 1.0 for cosine similarity
        // For Euclidean distance: lower is better, try < 0.8
        double threshold = 0.8;

        for (FaceData face : knownFaces) {
            // Calculate cosine similarity
            double similarity = cosineSimilarity(inputEmbedding, face.embedding);

            // Also calculate Euclidean distance
            double distance = euclideanDistance(inputEmbedding, face.embedding);

            // Use Euclidean distance (lower = more similar)
            // Distance < threshold means it's a match
            if (distance < threshold && distance < lowestDistance) {
                lowestDistance = distance;
                matchedName = face.name;
            }
        }

        return matchedName;
    }

    // Calculate cosine similarity between two vectors
    private double cosineSimilarity(float[] a, float[] b) {
        double dotProduct = 0;
        double normA = 0;
        double normB = 0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // Calculate Euclidean distance between two vectors
    private double euclideanDistance(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.pow(a[i] - b[i], 2);
        }
        return Math.sqrt(sum);
    }

    private void loadModel() {
        try {
            AssetFileDescriptor fileDescriptor = getAssets().openFd("mobile_face_embedding_model.tflite");
            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();

            interpreter = new Interpreter(fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength));
            modelLoaded = true;
            result.setText("Model loaded! Select an image.");

        } catch (IOException e) {
            modelLoaded = false;
            result.setText("Error loading model: " + e.getMessage());
        }
    }

    void getPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA}, 11);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults, int deviceId) {
        if (requestCode == 11) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                this.getPermission();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == 10 && data != null) {
            Uri uri = data.getData();
            try {
                bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), uri);
                imageView.setImageBitmap(bitmap);
            } catch (IOException e) {
                result.setText("Error loading image: " + e.getMessage());
            }
        } else if (requestCode == 12 && data != null) {
            bitmap = (Bitmap) data.getExtras().get("data");
            imageView.setImageBitmap(bitmap);
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (interpreter != null) {
            interpreter.close();
        }
    }

    // Helper class to store face data
    private class FaceData {
        String name;
        float[] embedding;

        FaceData(String name, float[] embedding) {
            this.name = name;
            this.embedding = embedding;
        }
    }
}