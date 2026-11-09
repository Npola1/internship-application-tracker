package com.interntrack;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public final class CvLibraryActivity extends Activity {
    private static final int PICK_CV = 411;
    private static final int EXPORT_CV = 412;
    private AuthManager auth;
    private ApiClient api;
    private LinearLayout container;
    private ProgressBar loading;
    private String exportCvId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_cvs);
        auth = new AuthManager(this); api = new ApiClient(this, auth);
        if (state != null) exportCvId = state.getString("exportCvId");
        if (BuildConfig.DEMO_MODE) {
            findViewById(R.id.demoBanner).setVisibility(View.VISIBLE);
            ((Button) findViewById(R.id.uploadButton)).setText("Import CV");
            ((TextView) findViewById(R.id.cvLibraryDescription)).setText("Keep your versions ready. Tap a saved CV to export a copy.");
        }
        container = findViewById(R.id.cvsContainer); loading = findViewById(R.id.loading);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        findViewById(R.id.uploadButton).setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.setType("*/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain"});
            startActivityForResult(pick, PICK_CV);
        });
        refresh();
    }
    private void refresh() {
        loading.setVisibility(View.VISIBLE);
        api.get("/cvs", new ApiClient.Callback() {
            @Override public void success(JSONObject data) {
                loading.setVisibility(View.GONE); container.removeAllViews();
                JSONArray items = data.optJSONArray("cvs");
                int count = items == null ? 0 : items.length();
                ((TextView) findViewById(R.id.cvCount)).setText(count + (count == 1 ? " document" : " documents"));
                if (items == null || items.length() == 0) {
                    container.addView(Ui.emptyState(CvLibraryActivity.this, "A home for your CVs", BuildConfig.DEMO_MODE ?
                            "Import your first CV, then attach it to an application when you're ready." : "Upload your first CV to start preparing for your next opportunity.", R.drawable.ic_document));
                    return;
                }
                for (int i = 0; i < items.length(); i++) {
                    JSONObject cv = items.optJSONObject(i);
                    if (cv == null) continue;
                    LinearLayout card = Ui.card(CvLibraryActivity.this);
                    String type = cv.optString("kind", "original");
                    String created = cv.optString("createdAt", "");
                    if (created.length() >= 10) created = Ui.dateLabel(created.substring(0, 10));
                    String size = Ui.fileSize(cv.optLong("size", 0));
                    String subtitle = ("tailored".equals(type) ? "AI tailored" : "Original") +
                            (created.isEmpty() ? "" : " · " + created) + (size.isEmpty() ? "" : " · " + size);
                    card.addView(Ui.documentHeader(CvLibraryActivity.this, cv.optString("name"), subtitle));
                    Ui.divider(CvLibraryActivity.this, card);
                    card.addView(Ui.metaRow(CvLibraryActivity.this, R.drawable.ic_chevron_right,
                            BuildConfig.DEMO_MODE ? "Save a copy" : "Open document", Ui.blue()));
                    card.setOnClickListener(v -> {
                        if (BuildConfig.DEMO_MODE) export(cv); else open(cv.optString("id"));
                    });
                    container.addView(card);
                }
            }
            @Override public void failure(String error) { loading.setVisibility(View.GONE); toast(error); }
        });
    }
    private void export(JSONObject cv) {
        exportCvId = cv.optString("id");
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType(cv.optString("mimeType", "application/octet-stream"));
        save.putExtra(Intent.EXTRA_TITLE, cv.optString("name", "CV"));
        try { startActivityForResult(save, EXPORT_CV); }
        catch (Exception ex) { toast("Could not open the file picker"); }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("exportCvId", exportCvId);
    }
    private void open(String id) {
        api.get("/cvs/" + id + "/download-url", new ApiClient.Callback() {
            @Override public void success(JSONObject data) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(data.optString("url")));
                try { startActivity(intent); }
                catch (Exception ex) { toast("No app available to open the file URL"); }
            }
            @Override public void failure(String error) { toast(error); }
        });
    }
    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == EXPORT_CV) {
            if (result != RESULT_OK || data == null || data.getData() == null || exportCvId == null) return;
            loading.setVisibility(View.VISIBLE);
            api.exportDemoCv(getContentResolver(), exportCvId, data.getData(), new ApiClient.Callback() {
                @Override public void success(JSONObject saved) { loading.setVisibility(View.GONE); toast("CV copy saved"); }
                @Override public void failure(String error) { loading.setVisibility(View.GONE); toast(error); }
            });
            return;
        }
        if (req != PICK_CV || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String filename = "";
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = c.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0) filename = c.getString(nameIndex);
                if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex);
            }
        } catch (Exception ex) { toast("Could not read the selected file. Please choose it again."); return; }
        if (filename == null || !filename.toLowerCase().matches(".*\\.(pdf|docx|txt)$")) {
            toast("Please select a PDF, DOCX or TXT file"); return;
        }
        if (size > 10 * 1024 * 1024) { toast("File size must be at most 10 MB"); return; }
        loading.setVisibility(View.VISIBLE);
        api.upload(getContentResolver(), uri, filename, size, new ApiClient.Callback() {
            @Override public void success(JSONObject data) { loading.setVisibility(View.GONE); toast(BuildConfig.DEMO_MODE ? "CV imported on this phone" : "CV uploaded"); refresh(); }
            @Override public void failure(String error) { loading.setVisibility(View.GONE); new AlertDialog.Builder(CvLibraryActivity.this)
                    .setTitle(BuildConfig.DEMO_MODE ? "Import failed" : "Upload failed").setMessage(error).setPositiveButton("OK", null).show(); }
        });
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() { auth.close(); super.onDestroy(); }
}
