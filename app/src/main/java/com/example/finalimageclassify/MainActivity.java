package com.example.finalimageclassify;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * MainActivity — Landing Page
 * ----------------------------
 * Two primary actions:
 *   1. Register Face  → RegisterActivity
 *   2. Identify Face  → IdentifyActivity
 *
 * Also shows how many faces are currently saved in the database.
 */
public class MainActivity extends AppCompatActivity {

    private TextView tvFaceCount;
    private Button   btnRegister;
    private Button   btnIdentify;
    private Button   btnManage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvFaceCount = findViewById(R.id.tvFaceCount);
        btnRegister = findViewById(R.id.btnRegister);
        btnIdentify = findViewById(R.id.btnIdentify);
        btnManage   = findViewById(R.id.btnManage);

        btnRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class))
        );

        btnIdentify.setOnClickListener(v ->
                startActivity(new Intent(this, IdentifyActivity.class))
        );

        btnManage.setOnClickListener(v ->
                startActivity(new Intent(this, ManageFacesActivity.class))
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh count every time we return from Register / Identify
        refreshFaceCount();
    }

    private void refreshFaceCount() {
        List<FaceStorageHelper.FaceRecord> records = FaceStorageHelper.loadAllFaces(this);
        int count = records.size();
        if (count == 0) {
            tvFaceCount.setText("No faces registered yet.\nPress \"Register Face\" to add someone.");
        } else {
            tvFaceCount.setText(count + " face" + (count == 1 ? "" : "s") + " registered.");
        }
    }
}