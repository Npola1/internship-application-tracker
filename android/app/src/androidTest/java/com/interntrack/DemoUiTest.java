package com.interntrack;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.*;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.hamcrest.Matcher;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.intent.matcher.IntentMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DemoUiTest {
    private Context context;
    private DemoRepository repo;
    private ActivityScenario<?> screen;
    @Before public void setup() throws Exception {
        Assume.assumeTrue("UI CRUD runs in the separate offline demo",BuildConfig.DEMO_MODE);
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        delete(new File(context.getFilesDir(),"demo"));
        repo=new DemoRepository(new File(context.getFilesDir(),"demo"));
        JSONArray initial=repo.request("GET","/applications",null).getJSONArray("applications");
        for(int i=0;i<initial.length();i++) repo.request("DELETE","/applications/"+initial.getJSONObject(i).getString("id"),null);
        Intents.init();
    }
    @After public void cleanup() { if(screen!=null) screen.close(); if(BuildConfig.DEMO_MODE) Intents.release(); }
    private static void delete(File file) {
        if(file.isDirectory()) for(File child:file.listFiles()) delete(child);
        if(file.exists()&&!file.delete()) throw new AssertionError("Could not clear test data");
    }
    private JSONObject application(String company,String status) throws Exception {
        return new JSONObject().put("company",company).put("role","Java Intern").put("status",status).put("jobUrl","https://example.org/internship");
    }
    private void main() { screen=ActivityScenario.launch(MainActivity.class); waitFor(withText("Demo student")); }
    private void editor(JSONObject app) {
        screen=ActivityScenario.launch(new Intent(context,EditApplicationActivity.class).putExtra("applicationId",app.optString("id")));
        waitFor(allOf(withId(R.id.companyInput),withText(app.optString("company"))));
    }
    private void library() { screen=ActivityScenario.launch(CvLibraryActivity.class); waitFor(withText("0 documents")); }
    private Uri fixture(String name) {
        return Uri.parse("content://"+InstrumentationRegistry.getInstrumentation().getContext().getPackageName()+".fixtures/"+name);
    }
    private void pick(String name) {
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(new android.app.Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(fixture(name))));
    }
    private static void waitFor(Matcher<View> matcher) {
        long end=System.currentTimeMillis()+6000; Throwable last=null;
        do {
            try { onView(matcher).check(matches(isDisplayed())); return; }
            catch(AssertionError|NoMatchingViewException error) { last=error; }
            try { Thread.sleep(50); } catch(InterruptedException error) { throw new AssertionError(error); }
        } while(System.currentTimeMillis()<end);
        throw new AssertionError("Expected view not shown",last);
    }
    private void waitForStoredValue(String id,String field,String expected) throws Exception {
        long end=System.currentTimeMillis()+5000;
        do { if(expected.equals(repo.request("GET","/applications/"+id,null).optString(field))) return; Thread.sleep(50); } while(System.currentTimeMillis()<end);
        fail("Application change was not saved");
    }
    private void waitForApplicationCount(int expected) throws Exception {
        long end=System.currentTimeMillis()+5000;
        do { if(repo.request("GET","/applications",null).getJSONArray("applications").length()==expected) return; Thread.sleep(50); } while(System.currentTimeMillis()<end);
        fail("Application count was not updated");
    }
    private static void chooseDate(int year,int month,int day) {
        onView(withId(R.id.dateInput)).perform(scrollTo(),click());
        onView(isAssignableFrom(DatePicker.class)).perform(new ViewAction(){
            public Matcher<View> getConstraints(){return isAssignableFrom(DatePicker.class);}
            public String getDescription(){return "Select a calendar date";}
            public void perform(UiController ui,View view){((DatePicker)view).updateDate(year,month-1,day);ui.loopMainThreadUntilIdle();}
        });
    }
    @Test public void dashboardShowsAccountCountersAndAllStatusFilters() throws Exception {
        String[] statuses={"Saved","Applied","Assessment","Interview","Offer","Rejected","Withdrawn"};
        for(String status:statuses) repo.request("POST","/applications",application("Employer "+status,status));
        main(); waitFor(allOf(withId(R.id.countTotal),withText("7\nTotal")));
        onView(withId(R.id.countInterview)).check(matches(withText("1\nInterviews")));
        onView(withId(R.id.countOffers)).check(matches(withText("1\nOffers")));
        for(String status:statuses) {
            onView(withId(R.id.filterStatus)).perform(scrollTo(),click());
            onData(equalTo(status)).perform(click());
            waitFor(withText("Employer "+status));
            for(String other:statuses) if(!other.equals(status)) onView(withText("Employer "+other)).check(doesNotExist());
        }
    }
    @Test public void createsAnApplicationThroughTheEditorAndRetainsItAfterReopening() throws Exception {
        main(); onView(withId(R.id.newApplicationButton)).perform(scrollTo(),click());
        onView(withId(R.id.companyInput)).perform(replaceText("Created in UI"),androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
        onView(withId(R.id.roleInput)).perform(replaceText("Engineering Intern"),androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
        chooseDate(2026,8,12); onView(withId(android.R.id.button1)).perform(click());
        onView(withId(R.id.saveButton)).perform(scrollTo(),click()); waitFor(withText("Created in UI"));
        screen.close(); screen=null; main(); waitFor(withText("Created in UI"));
        var saved=repo.request("GET","/applications",null).getJSONArray("applications").getJSONObject(0);
        assertEquals("2026-08-12",saved.getString("appliedDate"));
    }
    @Test public void missingRequiredFieldsDoNotCreateAnApplication() throws Exception {
        main(); onView(withId(R.id.newApplicationButton)).perform(scrollTo(),click());
        onView(withId(R.id.saveButton)).perform(scrollTo(),click());
        assertEquals(0,repo.request("GET","/applications",null).getJSONArray("applications").length());
        onView(withId(R.id.editorHeading)).perform(scrollTo()).check(matches(withText("New application")));
    }
    @Test public void datePickerFormatsJanuaryAndSingleDigitDays() throws Exception {
        var app=repo.request("POST","/applications",application("Date test","Applied")); editor(app);
        chooseDate(2026,1,2); onView(withId(android.R.id.button1)).perform(click());
        onView(withId(R.id.dateInput)).check(matches(withText("2026-01-02")));
    }
    @Test public void cancellingDateSelectionPreservesTheExistingDate() throws Exception {
        var app=repo.request("POST","/applications",application("Date test","Applied").put("appliedDate","2026-08-12")); editor(app);
        chooseDate(2028,2,29); onView(withId(android.R.id.button2)).perform(click());
        onView(withId(R.id.dateInput)).check(matches(withText("2026-08-12")));
    }
    @Test public void clearDateAndEditNotesPersistOnSave() throws Exception {
        var app=repo.request("POST","/applications",application("Edit test","Applied").put("appliedDate","2026-08-12")); editor(app);
        onView(withId(R.id.dateInput)).perform(scrollTo(),click());
        onView(withId(android.R.id.button3)).perform(click());
        onView(withId(R.id.notesInput)).perform(scrollTo(),replaceText("Interview next week"),androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
        onView(withId(R.id.saveButton)).perform(scrollTo(),click());
        waitForStoredValue(app.getString("id"),"notes","Interview next week");
        var saved=repo.request("GET","/applications/"+app.getString("id"),null);
        assertEquals("",saved.getString("appliedDate")); assertEquals("Interview next week",saved.getString("notes"));
    }
    @Test public void deleteRequiresConfirmationAndRemovesOnlyTheApplication() throws Exception {
        var app=repo.request("POST","/applications",application("Delete test","Rejected")); editor(app);
        onView(withId(R.id.deleteButton)).perform(scrollTo(),click());
        onView(withId(android.R.id.button2)).perform(click());
        assertEquals(1,repo.request("GET","/applications",null).getJSONArray("applications").length());
        onView(withId(R.id.deleteButton)).perform(click()); onView(withId(android.R.id.button1)).perform(click());
        waitForApplicationCount(0); assertEquals(0,repo.request("GET","/applications",null).getJSONArray("applications").length());
    }
    @Test public void libraryImportsAndExportsAnActualDocumentThroughPickerResults() throws Exception {
        library(); pick("cv.txt");
        onView(withId(R.id.uploadButton)).perform(click()); waitFor(withText("Student-CV.txt"));
        onView(withId(R.id.cvCount)).check(matches(withText("1 document")));
        Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(new android.app.Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(fixture("export.txt"))));
        onView(withText("Student-CV.txt")).perform(click());
        long end=System.currentTimeMillis()+5000; byte[] exported=null;
        do { try(var in=context.getContentResolver().openInputStream(fixture("export.txt"))){exported=ApiClient.readAll(in,1024*1024);}catch(Exception ignored){}
            if(java.util.Arrays.equals(FixtureProvider.CV,exported)) break; Thread.sleep(50);
        } while(System.currentTimeMillis()<end);
        assertArrayEquals(FixtureProvider.CV,exported);
    }
    @Test public void unsupportedPickerResultsAreNotImported() throws Exception {
        library(); pick("bad.exe"); onView(withId(R.id.uploadButton)).perform(click());
        assertEquals(0,repo.request("GET","/cvs",null).getJSONArray("cvs").length());
    }
    @Test public void oversizedPickerResultsAreNotImported() throws Exception {
        library(); pick("large.pdf"); onView(withId(R.id.uploadButton)).perform(click());
        assertEquals(0,repo.request("GET","/cvs",null).getJSONArray("cvs").length());
    }
    @Test public void cancelledFilePickerKeepsTheLibraryUsable() throws Exception {
        library(); Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(new android.app.Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null));
        onView(withId(R.id.uploadButton)).perform(click());
        onView(withId(R.id.uploadButton)).check(matches(isEnabled())); onView(withId(R.id.cvCount)).check(matches(withText("0 documents")));
    }
    @Test public void cvCanBeSelectedAndAttachedInTheApplicationEditor() throws Exception {
        var cv=repo.importCv(new java.io.ByteArrayInputStream(FixtureProvider.CV),"Student-CV.txt",FixtureProvider.CV.length);
        var app=repo.request("POST","/applications",application("Attach CV","Applied")); editor(app);
        onView(withId(R.id.cvInput)).perform(scrollTo(),click()); onData(equalTo("Student-CV.txt (original)")).perform(click());
        onView(withId(R.id.saveButton)).perform(scrollTo(),click()); waitForStoredValue(app.getString("id"),"cvId",cv.getString("id"));
        assertEquals(cv.getString("id"),repo.request("GET","/applications/"+app.getString("id"),null).getString("cvId"));
    }
    @Test public void offlineAiExplainsItsLimitationWithoutGeneratingAFakeCv() throws Exception {
        var app=repo.request("POST","/applications",application("AI test","Saved")); editor(app);
        onView(withId(R.id.tailorButton)).perform(scrollTo(),click());
        onView(withText("AI tailoring requires the connected service")).check(matches(isDisplayed()));
        assertEquals(0,repo.request("GET","/cvs",null).getJSONArray("cvs").length());
    }
    @Test public void bottomNavigationAccountAndBundledTipsWork() {
        main(); onView(withId(R.id.cvLibraryButton)).perform(click()); waitFor(withId(R.id.uploadButton));
        onView(withId(R.id.backButton)).perform(click()); onView(withId(R.id.accountButton)).perform(click());
        onView(withText("Offline demo")).check(matches(isDisplayed())); onView(withId(android.R.id.button3)).perform(click());
        waitFor(withText("Application tracking checklist"));
    }
    @Test public void internshipLinkOpensTheStoredHttpsUrl() throws Exception {
        repo.request("POST","/applications",application("Link employer","Applied"));
        Intents.intending(hasAction(Intent.ACTION_VIEW)).respondWith(new android.app.Instrumentation.ActivityResult(Activity.RESULT_OK,null));
        main(); onView(withText("https://example.org/internship")).perform(scrollTo(),click());
        Intents.intended(allOf(hasAction(Intent.ACTION_VIEW),hasData("https://example.org/internship")));
    }
    @Test public void changingStatusUpdatesTheDashboardCounters() throws Exception {
        repo.request("POST","/applications",application("Status edit","Saved"));main();
        onView(withText("Status edit")).perform(scrollTo(),click());
        onView(withId(R.id.statusInput)).perform(scrollTo(),click());onData(equalTo("Offer")).perform(click());
        onView(withId(R.id.saveButton)).perform(scrollTo(),click());
        waitFor(allOf(withId(R.id.countOffers),withText("1\nOffers")));
    }
    @Test public void refreshRetrievesNewlyStoredApplications() throws Exception {
        main();repo.request("POST","/applications",application("Newly stored","Saved"));
        onView(withId(R.id.refreshButton)).perform(click());waitFor(withText("Newly stored"));
    }
    @Test public void overviewNavigationReturnsToTheTop() throws Exception {
        for(int i=0;i<4;i++)repo.request("POST","/applications",application("Scroll employer "+i,"Saved"));
        main();onView(withText("Scroll employer 0")).perform(scrollTo());
        onView(withId(R.id.overviewButton)).perform(click());waitFor(withText("Your next opportunity."));
    }
}
