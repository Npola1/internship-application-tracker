package com.interntrack;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ScrollView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private final String[] filterOptions = {"All statuses", "Saved", "Applied", "Assessment", "Interview", "Offer", "Rejected", "Withdrawn"};
    private AuthManager auth;
    private ApiClient api;
    private LinearLayout container;
    private ProgressBar loading;
    private Spinner filter;
    private Button accountButton;
    private TextView accountDetails;
    private int refreshVersion;
    private final List<JSONObject> apps = new ArrayList<>();
    private boolean firstResume = true;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        auth = new AuthManager(this);
        api = new ApiClient(this, auth);
        container = findViewById(R.id.applicationsContainer);
        loading = findViewById(R.id.loading);
        filter = findViewById(R.id.filterStatus);
        accountButton = findViewById(R.id.accountButton);
        accountDetails = findViewById(R.id.accountDetails);
        findViewById(R.id.demoBanner).setVisibility(BuildConfig.DEMO_MODE ? View.VISIBLE : View.GONE);
        findViewById(R.id.serviceBanner).setVisibility(!BuildConfig.DEMO_MODE && !AuthManager.isConfigured() ? View.VISIBLE : View.GONE);
        filter.setAdapter(Ui.statusAdapter(this, filterOptions));
        Ui.styleStatusCounter(findViewById(R.id.countInterview), "Interview");
        Ui.styleStatusCounter(findViewById(R.id.countOffers), "Offer");
        findViewById(R.id.overviewButton).setSelected(true);
        findViewById(R.id.overviewButton).setOnClickListener(v -> ((ScrollView) findViewById(R.id.dashboardScroll)).smoothScrollTo(0, 0));
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) { render(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        findViewById(R.id.newApplicationButton).setOnClickListener(v -> {
            if (canUseTracker()) startActivity(new Intent(this, EditApplicationActivity.class));
            else askSignIn();
        });
        findViewById(R.id.cvLibraryButton).setOnClickListener(v -> {
            if (canUseTracker()) startActivity(new Intent(this, CvLibraryActivity.class));
            else askSignIn();
        });
        findViewById(R.id.refreshButton).setOnClickListener(v -> refresh());
        accountButton.setOnClickListener(v -> account());
        if (BuildConfig.DEMO_MODE) {
            accountButton.setText("Demo");
        } else if (!AuthManager.isConfigured()) {
            accountButton.setText("Sign in");
            accountDetails.setText("Connect the online service to sign in.");
            render();
        } else if (!auth.isSignedIn()) {
            askSignIn();
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (firstResume) { firstResume = false; if (auth != null && canUseTracker()) refresh(); return; }
        if (auth != null && canUseTracker()) refresh();
    }
    private boolean canUseTracker() {
        return BuildConfig.DEMO_MODE || (AuthManager.isConfigured() && auth.isSignedIn());
    }
    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == AuthManager.LOGIN_REQUEST_CODE) {
            auth.completeLogin(data, (ok, error) -> {
                if (ok) refresh();
                else Toast.makeText(this, error == null ? "Sign-in cancelled" : error, Toast.LENGTH_LONG).show();
            });
        }
    }
    private void askSignIn() {
        accountButton.setText("Sign in");
        if (!AuthManager.isConfigured()) { showServiceUnavailable(); return; }
        new AlertDialog.Builder(this).setTitle("Sign in to InternTrack")
                .setMessage("Use your account to keep applications and CVs private. You can also create an account in the sign-in page.")
                .setNegativeButton("Later", (d, w) -> render())
                .setPositiveButton("Continue", (d, w) -> {
                    try { auth.signIn(this); }
                    catch (ActivityNotFoundException ex) {
                        new AlertDialog.Builder(this).setTitle("A browser is needed")
                                .setMessage("Install or enable a web browser on your phone, then try signing in again.")
                                .setPositiveButton("OK", null).show();
                    }
                }).show();
    }
    private void showServiceUnavailable() {
        new AlertDialog.Builder(this).setTitle("Online service not connected")
                .setMessage("Sign-in, application tracking and your CV library need the connected online service. It hasn't been set up for this build yet.\n\nYou can use InternTrack Demo to manage applications and CVs on this phone without signing in.")
                .setNegativeButton("Close", null)
                .setPositiveButton("Open offline demo", (d, w) -> openDemo()).show();
    }
    private void openDemo() {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName("com.interntrack.demo", "com.interntrack.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(intent); }
        catch (ActivityNotFoundException ex) {
            new AlertDialog.Builder(this).setTitle("Install InternTrack Demo")
                    .setMessage("The offline demo isn't installed on this phone. Install InternTrack Demo, then open it to try application tracking and CV management without signing in.")
                    .setPositiveButton("OK", null).show();
        }
    }
    private void refresh() {
        if (!BuildConfig.DEMO_MODE && !AuthManager.isConfigured()) { showServiceUnavailable(); return; }
        if (!canUseTracker()) { clearDashboard(); render(); return; }
        final int version = ++refreshVersion;
        loading.setVisibility(View.VISIBLE);
        accountButton.setText(BuildConfig.DEMO_MODE ? "Demo" : "Account");
        api.get("/dashboard", new ApiClient.Callback() {
            @Override public void success(JSONObject data) {
                if (version != refreshVersion || !canUseTracker()) return;
                loading.setVisibility(View.GONE);
                JSONObject profile = data.optJSONObject("account");
                String name = profile == null ? "" : profile.optString("name", "");
                String email = profile == null ? "" : profile.optString("email", "");
                accountDetails.setText(BuildConfig.DEMO_MODE ? "Demo student" : (name.isEmpty() ? "" : name + " · ") + email);
                apps.clear();
                JSONArray results = data.optJSONArray("applications");
                if (results != null) for (int i = 0; i < results.length(); i++) {
                    JSONObject app = results.optJSONObject(i);
                    if (app != null) apps.add(app);
                }
                apps.sort((a, b) -> b.optString("updatedAt", "").compareTo(a.optString("updatedAt", "")));
                render();
            }
            @Override public void failure(String error) {
                if (version != refreshVersion || !canUseTracker()) return;
                loading.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                render();
            }
        });
    }
    private void render() {
        if (container == null) return;
        container.removeAllViews();
        int interviews = 0, offers = 0;
        for (JSONObject app : apps) {
            if ("Interview".equals(app.optString("status"))) interviews++;
            if ("Offer".equals(app.optString("status"))) offers++;
        }
        Ui.setCounter(findViewById(R.id.countTotal), apps.size(), "Total");
        Ui.setCounter(findViewById(R.id.countInterview), interviews, "Interviews");
        Ui.setCounter(findViewById(R.id.countOffers), offers, "Offers");
        if (!BuildConfig.DEMO_MODE && !AuthManager.isConfigured()) {
            LinearLayout setup = Ui.emptyState(this, "Your online workspace isn't connected",
                    "Sign-in, applications and CVs become available when the online service is connected. Try the offline demo in the meantime.", R.drawable.ic_link);
            Ui.gap(this, setup, 16);
            Button demo = new Button(this, null, 0, R.style.SecondaryButton);
            demo.setText("Open offline demo");
            demo.setTextSize(14);
            demo.setTextColor(Ui.blue());
            demo.setAllCaps(false);
            demo.setBackgroundResource(R.drawable.secondary_button);
            demo.setOnClickListener(v -> openDemo());
            setup.addView(demo, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
            container.addView(setup);
            return;
        }
        if (!canUseTracker()) {
            container.addView(Ui.spacer(this, "Sign in to see your application pipeline."));
            return;
        }
        String selected = filterOptions[filter.getSelectedItemPosition()];
        int displayed = 0;
        for (JSONObject app : apps) {
            if (!selected.equals("All statuses") && !selected.equals(app.optString("status"))) continue;
            displayed++;
            LinearLayout card = Ui.card(this);
            card.addView(Ui.applicationHeader(this, app.optString("company", "Company"), app.optString("role", "")));
            Ui.gap(this, card, 14);
            String status = app.optString("status", "Saved");
            String date = app.optString("appliedDate", "");
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.HORIZONTAL);
            details.setGravity(Gravity.CENTER_VERTICAL);
            details.addView(Ui.statusBadge(this, status));
            if (!date.trim().isEmpty()) {
                TextView applied = Ui.text(this, Ui.dateLabel(date), 12, Ui.muted(), false);
                applied.setGravity(Gravity.END);
                LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(0, -2, 1);
                dateParams.setMarginStart(Ui.dp(this, 10));
                details.addView(applied, dateParams);
            }
            card.addView(details);
            Ui.divider(this, card);
            String cvName = app.optString("cvName", "");
            card.addView(Ui.metaRow(this, R.drawable.ic_document, cvName.trim().isEmpty() ? "No CV attached" : cvName, Ui.muted()));
            String listingUrl = app.optString("jobUrl", "");
            if (!listingUrl.trim().isEmpty()) {
                Ui.gap(this, card, 10);
                LinearLayout urlText = Ui.metaRow(this, R.drawable.ic_link, listingUrl, Ui.blue());
                urlText.setContentDescription("Open internship listing: " + listingUrl);
                urlText.setOnClickListener(v -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(listingUrl))); }
                    catch (Exception ex) { Toast.makeText(this, "Could not open link", Toast.LENGTH_LONG).show(); }
                });
                card.addView(urlText);
            }
            card.setOnClickListener(v -> {
                Intent intent = new Intent(this, EditApplicationActivity.class);
                intent.putExtra("applicationId", app.optString("id"));
                startActivity(intent);
            });
            container.addView(card);
        }
        if (displayed == 0) container.addView(Ui.emptyState(this, apps.isEmpty() ? "Your next chapter starts here" : "Nothing at this stage",
                apps.isEmpty() ? "Add an internship to keep its details, progress and CV in one place." : "Try another status or update an application.", R.drawable.ic_briefcase));
    }
    private void account() {
        if (BuildConfig.DEMO_MODE) {
            new AlertDialog.Builder(this).setTitle("Offline demo")
                    .setMessage("Try application tracking and import CVs without signing in. Your changes stay on this phone.\n\nAI tailoring and account sync need the connected service. Demo records are separate from online account data. Uninstalling the demo removes its saved data.")
                    .setNeutralButton("Application tips", (d, w) -> tips()).setPositiveButton("Close", null).show();
            return;
        }
        if (!AuthManager.isConfigured()) { showServiceUnavailable(); return; }
        if (!auth.isSignedIn()) { askSignIn(); return; }
        api.get("/me", new ApiClient.Callback() {
            @Override public void success(JSONObject data) {
                String displayName = data.optString("name", "");
                String email = data.optString("email", "");
                String body = (displayName.isEmpty() ? "" : displayName + "\n") + email +
                        "\n\nYour applications and CVs are associated with this account.";
                new AlertDialog.Builder(MainActivity.this).setTitle("Account")
                        .setMessage(body)
                        .setNeutralButton("Tips", (d, w) -> tips())
                        .setNegativeButton("Close", null)
                        .setPositiveButton("Sign out", (d, w) -> signOut()).show();
            }
            @Override public void failure(String error) {
                Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                new AlertDialog.Builder(MainActivity.this).setTitle("Account")
                        .setItems(new String[]{"Application tips", "Sign out"}, (d, which) -> {
                            if (which == 0) tips(); else signOut();
                        }).show();
            }
        });
    }
    private void tips() {
        api.publicTips(new ApiClient.Callback() {
            @Override public void success(JSONObject data) {
                JSONArray array = data.optJSONArray("tips");
                StringBuilder body = new StringBuilder();
                if (array != null) for (int i = 0; i < array.length(); i++)
                    body.append("• ").append(array.optString(i)).append("\n\n");
                new AlertDialog.Builder(MainActivity.this).setTitle(data.optString("title", "Application tips"))
                        .setMessage(body.toString().trim()).setPositiveButton("Done", null).show();
            }
            @Override public void failure(String error) { Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show(); }
        });
    }
    private void signOut() {
        auth.signOut(); clearDashboard(); accountButton.setText("Sign in"); render();
    }
    private void clearDashboard() {
        refreshVersion++;
        apps.clear();
        loading.setVisibility(View.GONE);
        accountDetails.setText("Sign in to view your account details.");
    }
    @Override protected void onDestroy() { if (auth != null) auth.close(); super.onDestroy(); }
}
