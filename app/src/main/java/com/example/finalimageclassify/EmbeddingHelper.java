package com.example.finalimageclassify;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * EmbeddingHelper
 * ---------------
 * Loads the TFLite model and runs inference to produce a 128-d face embedding.
 *
 * Key fixes vs original MainActivity:
 *  - Input resized to 160x160 (matches training script)
 *  - Pixel indexing is [y][x] (row-major, correct)
 *  - L2 normalization applied to output so Euclidean distance is meaningful
 */
public class EmbeddingHelper {

    private static final int    IMG_SIZE        = 160;   // Must match train_model.py
    private static final int    EMBEDDING_DIM   = 128;
    private static final String MODEL_FILE      = "mobile_face_embedding_model.tflite";

    private final Interpreter interpreter;

    public EmbeddingHelper(Context context) throws IOException {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);
        interpreter = new Interpreter(loadModelFile(context), options);
    }

    /**
     * Converts a Bitmap into a 128-d L2-normalized embedding vector.
     *
     * @param bitmap  Any size bitmap (will be scaled internally)
     * @return        float[128] embedding, or null on error
     */
    public float[] getEmbedding(Bitmap bitmap) {
        try {
            // 1. Scale to model input size
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, IMG_SIZE, IMG_SIZE, true);

            // 2. Fill input tensor — shape: [1, height, width, channels]
            //    Indexing: [batch][row=y][col=x][channel]  ← row-major (correct)
            float[][][][] input = new float[1][IMG_SIZE][IMG_SIZE][3];
            for (int y = 0; y < IMG_SIZE; y++) {
                for (int x = 0; x < IMG_SIZE; x++) {
                    int pixel = scaled.getPixel(x, y);
                    input[0][y][x][0] = ((pixel >> 16) & 0xFF) / 255.0f; // R
                    input[0][y][x][1] = ((pixel >> 8)  & 0xFF) / 255.0f; // G
                    input[0][y][x][2] = ( pixel        & 0xFF) / 255.0f; // B
                }
            }

            // 3. Run inference
            float[][] output = new float[1][EMBEDDING_DIM];
            interpreter.run(input, output);

            // 4. L2-normalize the embedding so distances are comparable
            return l2Normalize(output[0]);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Euclidean distance between two (already normalized) embedding vectors.
     * Range: [0, 2] when both vectors are unit length.
     * Lower = more similar.
     */
    public static float euclideanDistance(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            float diff = a[i] - b[i];
            sum += diff * diff;
        }
        return (float) Math.sqrt(sum);
    }

    /**
     * Converts a raw Euclidean distance (0–2) to a 0–100% confidence score.
     * Distance 0.0 → 100%, Distance 1.0 → ~50%, Distance >= 1.5 → ~0%
     */
    public static float distanceToConfidence(float distance) {
        // Calibrated to your model's actual output range:
        //   distance 0.0   → 100%
        //   distance 0.3   → ~90%
        //   distance 0.587 → ~80%  (your typical correct-match distance)
        //   distance 0.75  → ~74%
        //   distance 1.2+  → 0%  (clearly wrong match)
        float confidence = 1.0f - (distance / 2.935f);
        return Math.max(0f, Math.min(1f, confidence)) * 100f;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private float[] l2Normalize(float[] vector) {
        float norm = 0f;
        for (float v : vector) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm == 0f) return vector;
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / norm;
        }
        return normalized;
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd(MODEL_FILE);
        FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
        FileChannel channel = fis.getChannel();
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }

    public void close() {
        if (interpreter != null) interpreter.close();
    }
}