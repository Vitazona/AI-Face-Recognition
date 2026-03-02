package com.example.finalimageclassify;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;

/**
 * ManageFacesActivity
 * -------------------
 * Shows all registered faces in a list.
 * Each row shows the person's name and a Delete button.
 * A "Delete All" FAB is shown at the bottom.
 */
public class ManageFacesActivity extends AppCompatActivity {

    private RecyclerView     recyclerView;
    private TextView         tvEmpty;
    private FaceAdapter      adapter;
    private List<FaceStorageHelper.FaceRecord> faceList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_faces);

        recyclerView = findViewById(R.id.recyclerView);
        tvEmpty      = findViewById(R.id.tvEmpty);

        FloatingActionButton fabDeleteAll = findViewById(R.id.fabDeleteAll);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        loadFaces();

        fabDeleteAll.setOnClickListener(v -> confirmDeleteAll());
    }

    // -------------------------------------------------------------------------
    // Load / Refresh
    // -------------------------------------------------------------------------

    private void loadFaces() {
        faceList = FaceStorageHelper.loadAllFaces(this);
        adapter  = new FaceAdapter(faceList);
        recyclerView.setAdapter(adapter);
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (faceList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    // -------------------------------------------------------------------------
    // Delete single
    // -------------------------------------------------------------------------

    private void confirmDeleteSingle(int position) {
        String name = faceList.get(position).name;
        new AlertDialog.Builder(this)
                .setTitle("Delete Face")
                .setMessage("Remove \"" + name + "\" from the database?")
                .setPositiveButton("Delete", (d, w) -> deleteSingle(position))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteSingle(int position) {
        faceList.remove(position);
        FaceStorageHelper.saveAll(this, faceList);
        adapter.notifyItemRemoved(position);
        adapter.notifyItemRangeChanged(position, faceList.size());
        updateEmptyState();
        Toast.makeText(this, "Deleted.", Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // Delete all
    // -------------------------------------------------------------------------

    private void confirmDeleteAll() {
        if (faceList.isEmpty()) {
            Toast.makeText(this, "No faces to delete.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete All Faces")
                .setMessage("This will remove all " + faceList.size() + " registered face(s). Are you sure?")
                .setPositiveButton("Delete All", (d, w) -> deleteAll())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteAll() {
        FaceStorageHelper.clearAllFaces(this);
        faceList.clear();
        adapter.notifyDataSetChanged();
        updateEmptyState();
        Toast.makeText(this, "All faces deleted.", Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // RecyclerView Adapter
    // -------------------------------------------------------------------------

    private class FaceAdapter extends RecyclerView.Adapter<FaceAdapter.ViewHolder> {

        private final List<FaceStorageHelper.FaceRecord> data;

        FaceAdapter(List<FaceStorageHelper.FaceRecord> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_face, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            FaceStorageHelper.FaceRecord record = data.get(position);
            holder.tvName.setText(record.name);
            holder.tvIndex.setText("#" + (position + 1));
            holder.btnDelete.setOnClickListener(v ->
                    confirmDeleteSingle(holder.getAdapterPosition()));
        }

        @Override
        public int getItemCount() { return data.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvIndex, tvName;
            android.widget.ImageButton btnDelete;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvIndex   = itemView.findViewById(R.id.tvIndex);
                tvName    = itemView.findViewById(R.id.tvName);
                btnDelete = itemView.findViewById(R.id.btnDelete);
            }
        }
    }
}