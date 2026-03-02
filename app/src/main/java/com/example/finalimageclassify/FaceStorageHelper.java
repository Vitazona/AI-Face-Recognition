package com.example.finalimageclassify;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * FaceStorageHelper
 * -----------------
 * Reads and writes face embeddings to/from a JSON file in internal app storage.
 *
 * File location: /data/data/<package>/files/faces.json
 *
 * JSON format:
 * [
 *   {
 *     "name": "Juan",
 *     "embedding": [0.123, -0.456, ...]   // 128 floats
 *   },
 *   ...
 * ]
 */
public class FaceStorageHelper {

    private static final String FILE_NAME = "faces.json";

    // -------------------------------------------------------------------------
    // Public data model
    // -------------------------------------------------------------------------

    public static class FaceRecord {
        public final String  name;
        public final float[] embedding;

        public FaceRecord(String name, float[] embedding) {
            this.name      = name;
            this.embedding = embedding;
        }
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    /**
     * Appends a new face record to faces.json.
     * If the file does not exist yet, it is created.
     */
    public static void saveFace(Context context, String name, float[] embedding) throws Exception {
        // Load existing records
        List<FaceRecord> records = loadAllFaces(context);

        // Append new record
        records.add(new FaceRecord(name, embedding));

        // Serialise and write
        writeToFile(context, serialise(records));
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    /**
     * Returns all saved face records, or an empty list if none exist yet.
     */
    public static List<FaceRecord> loadAllFaces(Context context) {
        List<FaceRecord> records = new ArrayList<>();
        try {
            String json = readFromFile(context);
            if (json == null || json.isEmpty()) return records;

            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj       = array.getJSONObject(i);
                String     name      = obj.getString("name");
                JSONArray  embJson   = obj.getJSONArray("embedding");
                float[]    embedding = new float[embJson.length()];
                for (int j = 0; j < embJson.length(); j++) {
                    embedding[j] = (float) embJson.getDouble(j);
                }
                records.add(new FaceRecord(name, embedding));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return records;
    }

    // -------------------------------------------------------------------------
    // Save entire list (used when deleting a single record)
    // -------------------------------------------------------------------------

    public static void saveAll(Context context, List<FaceRecord> records) {
        try {
            writeToFile(context, serialise(records));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // -------------------------------------------------------------------------
    // Delete all (for reset / testing)
    // -------------------------------------------------------------------------

    public static void clearAllFaces(Context context) {
        writeToFile(context, "[]");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String serialise(List<FaceRecord> records) throws JSONException {
        JSONArray array = new JSONArray();
        for (FaceRecord r : records) {
            JSONObject obj     = new JSONObject();
            JSONArray  embJson = new JSONArray();
            for (float v : r.embedding) embJson.put(v);
            obj.put("name",      r.name);
            obj.put("embedding", embJson);
            array.put(obj);
        }
        return array.toString();
    }

    private static void writeToFile(Context context, String json) {
        try (FileOutputStream fos = context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE)) {
            fos.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String readFromFile(Context context) {
        try (FileInputStream fis = context.openFileInput(FILE_NAME);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            // File may not exist yet — that's fine
            return null;
        }
    }
}