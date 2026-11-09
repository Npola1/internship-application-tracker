package com.interntrack;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public final class EditApplicationActivity extends Activity {
    private static final String[] STATUSES = {"Saved", "Applied", "Assessment", "Interview", "Offer", "Rejected", "Withdrawn"};
    private AuthManager auth;
    private ApiClient api;
    private String applicationId;
    private String selectedCvId = "";
    private final List<JSONObject> cvList = new ArrayList<>();
    private EditText company, role, jobUrl, notes, jobDescription;
    private Spinner status, cv;
    private Button appliedDate, saveButton, tailorButton, deleteButton;
    private final Handler poller = new Handler(Looper.getMainLooper());
    private String tailoringJobId;
    private long pollingStarted;
    private int pollFailures;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_editor);
        auth = new AuthManager(this); api = new ApiClient(this, auth);
        applicationId = getIntent().getStringExtra("applicationId");
        if (state != null) applicationId = state.getString("applicationId", applicationId);
        if (!BuildConfig.DEMO_MODE && applicationId != null) {
            var preferences = getSharedPreferences("tailoring", MODE_PRIVATE);
            if (System.currentTimeMillis() - preferences.getLong(applicationId + ".startedAt", 0) < 24L * 60 * 60 * 1000)
                tailoringJobId = preferences.getString(applicationId, null);
        }
        company = findViewById(R.id.companyInput);
        role = findViewById(R.id.roleInput);
        jobUrl = findViewById(R.id.jobUrlInput);
        appliedDate = findViewById(R.id.dateInput);
        appliedDate.setOnClickListener(v -> pickAppliedDate());
        notes = findViewById(R.id.notesInput);
        jobDescription = findViewById(R.id.jobDescriptionInput);
        status = findViewById(R.id.statusInput);
        cv = findViewById(R.id.cvInput);
        saveButton = findViewById(R.id.saveButton);
        tailorButton = findViewById(R.id.tailorButton);
        deleteButton = findViewById(R.id.deleteButton);
        status.setAdapter(Ui.statusAdapter(this, STATUSES));
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        saveButton.setOnClickListener(v -> save(false));
        if (BuildConfig.DEMO_MODE) {
            findViewById(R.id.demoBanner).setVisibility(View.VISIBLE);
            tailorButton.setText("AI tailoring · online only");
            tailorButton.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("AI tailoring requires the connected service")
                    .setMessage("You can save applications and attach CVs in the demo. AI tailoring becomes available in the connected app.")
                    .setPositiveButton("OK", null).show());
        } else tailorButton.setOnClickListener(v -> {
            if (tailoringJobId == null) save(true); else beginPolling();
        });
        deleteButton.setOnClickListener(v -> confirmDelete());
        deleteButton.setVisibility(applicationId == null ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.editorHeading)).setText(applicationId == null ? "New application" : "Edit application");
        loadCvs();
        if (applicationId != null) loadApplication();
        if (tailoringJobId != null) setBusy(true);
    }
    private void pickAppliedDate() {
        LocalDate initial = LocalDate.now();
        String saved = appliedDate.getText().toString().trim();
        if (!saved.isEmpty()) {
            try { initial = LocalDate.parse(saved); }
            catch (DateTimeParseException ignored) { /* Start at today if an old draft has an invalid date. */ }
        }
        DatePickerDialog picker = new DatePickerDialog(this, (view, year, month, day) ->
                appliedDate.setText(LocalDate.of(year, month + 1, day).toString()),
                initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
        picker.setButton(DatePickerDialog.BUTTON_NEUTRAL, "Clear", (dialog, which) -> appliedDate.setText(""));
        picker.show();
    }
    private void loadCvs() {
        api.get("/cvs", new ApiClient.Callback() {
            @Override public void success(JSONObject data) {
                cvList.clear();
                List<String> names = new ArrayList<>();
                names.add("No CV selected");
                JSONArray results = data.optJSONArray("cvs");
                if (results != null) for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.optJSONObject(i);
                    if (item == null) continue;
                    cvList.add(item);
                    names.add(item.optString("name") + " (" + item.optString("kind", "original") + ")");
                }
                cv.setAdapter(Ui.choiceAdapter(EditApplicationActivity.this, names));
                pickCv(selectedCvId);
            }
            @Override public void failure(String error) { toast("CV list: " + error); }
        });
    }
    private void pickCv(String id) {
        if (id == null || id.trim().isEmpty()) { cv.setSelection(0); return; }
        for (int i = 0; i < cvList.size(); i++) {
            if (id.equals(cvList.get(i).optString("id"))) { cv.setSelection(i + 1); return; }
        }
    }
    private void loadApplication() {
        api.get("/applications/" + applicationId, new ApiClient.Callback() {
            @Override public void success(JSONObject app) {
                company.setText(app.optString("company"));
                role.setText(app.optString("role"));
                jobUrl.setText(app.optString("jobUrl"));
                appliedDate.setText(app.optString("appliedDate"));
                notes.setText(app.optString("notes"));
                jobDescription.setText(app.optString("jobDescription"));
                for (int i = 0; i < STATUSES.length; i++)
                    if (STATUSES[i].equals(app.optString("status"))) status.setSelection(i);
                selectedCvId = app.optString("cvId", "");
                pickCv(selectedCvId);
                if (tailoringJobId != null) beginPolling();
            }
            @Override public void failure(String error) { setBusy(false); toast(error); }
        });
    }
    private String selectedCv() {
        // A missing library response must not detach an already submitted CV.
        if (cv.getAdapter() == null) return selectedCvId;
        int index = cv.getSelectedItemPosition();
        return index <= 0 || index > cvList.size() ? "" : cvList.get(index - 1).optString("id", "");
    }
    private JSONObject collectForm() throws Exception {
        if (company.getText().toString().trim().isEmpty() || role.getText().toString().trim().isEmpty())
            throw new Exception("Company and role are required");
        JSONObject input = new JSONObject();
        input.put("company", company.getText().toString());
        input.put("role", role.getText().toString());
        input.put("jobUrl", jobUrl.getText().toString());
        input.put("appliedDate", appliedDate.getText().toString());
        input.put("status", STATUSES[status.getSelectedItemPosition()]);
        input.put("cvId", selectedCv());
        input.put("notes", notes.getText().toString());
        input.put("jobDescription", jobDescription.getText().toString());
        return input;
    }
    private void setBusy(boolean busy) {
        saveButton.setEnabled(!busy);
        tailorButton.setEnabled(!busy);
        deleteButton.setEnabled(!busy);
        saveButton.setText(busy ? (tailoringJobId == null ? "Saving..." : "Tailoring CV...") : "Save application");
        if (!BuildConfig.DEMO_MODE)
            tailorButton.setText(tailoringJobId == null ? "Tailor CV with AI" : busy ? "Tailoring in progress" : "Check tailoring status");
    }
    private void save(boolean thenTailor) {
        JSONObject input;
        try {
            input = collectForm();
            if (thenTailor) {
                if (selectedCv().trim().isEmpty()) throw new Exception("Select a CV from your library before tailoring");
                if (jobDescription.getText().toString().trim().isEmpty()) throw new Exception("Paste the job description first");
            }
        } catch (Exception ex) { toast(ex.getMessage()); return; }
        setBusy(true);
        ApiClient.Callback callback = new ApiClient.Callback() {
            @Override public void success(JSONObject app) {
                setBusy(false);
                applicationId = app.optString("id");
                deleteButton.setVisibility(View.VISIBLE);
                if (thenTailor) generateTailoredCv();
                else { toast("Application saved"); finish(); }
            }
            @Override public void failure(String error) { setBusy(false); toast(error); }
        };
        if (applicationId == null) api.post("/applications", input, callback);
        else api.put("/applications/" + applicationId, input, callback);
    }
    private void generateTailoredCv() {
        setBusy(true);
        try {
            JSONObject input = new JSONObject();
            input.put("cvId", selectedCv());
            input.put("jobDescription", jobDescription.getText().toString());
            api.post("/applications/" + applicationId + "/tailor", input, new ApiClient.Callback() {
                @Override public void success(JSONObject data) {
                    String id = data.optString("jobId", "");
                    if (id.isEmpty()) { setBusy(false); toast("Could not start tailoring. Please retry."); return; }
                    rememberJob(id);
                    beginPolling();
                }
                @Override public void failure(String error) { setBusy(false); toast(error); }
            });
        } catch (Exception ex) { setBusy(false); toast(ex.getMessage()); }
    }
    private void rememberJob(String id) {
        tailoringJobId = id;
        var edit = getSharedPreferences("tailoring", MODE_PRIVATE).edit();
        if (id == null) edit.remove(applicationId).remove(applicationId + ".startedAt");
        else edit.putString(applicationId, id).putLong(applicationId + ".startedAt", System.currentTimeMillis());
        edit.apply();
    }
    private void beginPolling() {
        poller.removeCallbacksAndMessages(null);
        pollingStarted = SystemClock.elapsedRealtime();
        pollFailures = 0;
        setBusy(true);
        pollTailoringJob();
    }
    private void pollTailoringJob() {
        if (tailoringJobId == null || isFinishing() || isDestroyed()) return;
        if (SystemClock.elapsedRealtime() - pollingStarted > 4 * 60 * 1000) {
            setBusy(false);
            toast("This is taking longer than expected. Check the status again or look in your CV library shortly.");
            return;
        }
        api.get("/tailoring-jobs/" + tailoringJobId, new ApiClient.Callback() {
            @Override public void success(JSONObject result) {
                if (isFinishing() || isDestroyed()) return;
                pollFailures = 0;
                String state = result.optString("status");
                if ("COMPLETED".equals(state)) {
                    rememberJob(null);
                    setBusy(false);
                    JSONObject tailored = result.optJSONObject("tailoredCv");
                    if (tailored != null) showTailored(tailored, result.optString("preview"));
                    else toast("The result is unavailable. Check your CV library.");
                } else if ("FAILED".equals(state)) {
                    rememberJob(null);
                    setBusy(false);
                    new AlertDialog.Builder(EditApplicationActivity.this)
                            .setTitle("CV tailoring unavailable")
                            .setMessage(result.optString("message", "Tailoring failed. Please retry."))
                            .setPositiveButton("OK", null).show();
                } else poller.postDelayed(() -> pollTailoringJob(), 2000);
            }
            @Override public void failure(String error) {
                if (isFinishing() || isDestroyed()) return;
                if (++pollFailures >= 3) {
                    setBusy(false);
                    toast("Could not check tailoring: " + error + ". You can check again shortly.");
                } else poller.postDelayed(() -> pollTailoringJob(), 2000);
            }
        });
    }
    private void showTailored(JSONObject tailored, String preview) {
        String id = tailored.optString("id");
        String msg = "Generated " + tailored.optString("name") +
                ".\n\nReview all changes before submitting. AI cannot verify your claims.\n\nPREVIEW\n" + preview;
        new AlertDialog.Builder(this).setTitle("Tailored CV ready")
                .setMessage(msg)
                .setNegativeButton("Close", (d, w) -> loadCvs())
                .setNeutralButton("Download", (d, w) -> { download(id); loadCvs(); })
                .setPositiveButton("Attach to application", (d, w) -> {
                    // Re-fetch the library and select the generated CV before updating the saved application.
                    try {
                        JSONObject input = collectForm();
                        input.put("cvId", id);
                        api.put("/applications/" + applicationId, input, new ApiClient.Callback() {
                            @Override public void success(JSONObject app) {
                                selectedCvId = id; loadCvs(); toast("Tailored CV attached. Review before submitting.");
                            }
                            @Override public void failure(String error) { toast(error); }
                        });
                    } catch (Exception ex) { toast(ex.getMessage()); }
                }).show();
    }
    private void download(String id) {
        api.get("/cvs/" + id + "/download-url", new ApiClient.Callback() {
            @Override public void success(JSONObject result) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(result.getString("url")))); }
                catch (Exception ex) { toast("Could not open document: " + ex.getMessage()); }
            }
            @Override public void failure(String error) { toast(error); }
        });
    }
    private void confirmDelete() {
        if (applicationId == null) return;
        new AlertDialog.Builder(this).setTitle("Delete application?")
                .setMessage("This deletes the application record, but not any CV files in your library.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> {
                    api.delete("/applications/" + applicationId, new ApiClient.Callback() {
                        @Override public void success(JSONObject result) { toast("Application deleted"); finish(); }
                        @Override public void failure(String error) { toast(error); }
                    });
                }).show();
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("applicationId", applicationId);
    }
    @Override protected void onDestroy() {
        poller.removeCallbacksAndMessages(null);
        auth.close();
        super.onDestroy();
    }
}
