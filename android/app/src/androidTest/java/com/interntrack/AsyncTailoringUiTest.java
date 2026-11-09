package com.interntrack;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import net.openid.appauth.*;
import org.hamcrest.Matcher;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.intent.matcher.IntentMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/** Controlled HTTP boundary, real ApiClient/activities/poller/storage. Run in its own instrumentation process. */
@RunWith(AndroidJUnit4.class)
public class AsyncTailoringUiTest {
    private static final String APP="11111111-1111-4111-8111-111111111111", SOURCE="22222222-2222-4222-8222-222222222222",
            GENERATED="33333333-3333-4333-8333-333333333333", JOB="44444444-4444-4444-8444-444444444444";
    private static final AtomicInteger starts=new AtomicInteger(),polls=new AtomicInteger();
    private static final AtomicInteger saves=new AtomicInteger();
    private static volatile String state="RUNNING";
    private static volatile boolean unavailable;
    private static volatile boolean cvUnavailable;
    private static JSONObject application;
    private Context context;
    private ActivityScenario<EditApplicationActivity> screen;
    @BeforeClass public static void intercept() {
        Assume.assumeTrue("Run through the isolated controlled-response suite",
                "true".equals(InstrumentationRegistry.getArguments().getString("controlledNetwork")));
        URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
            protected URLConnection openConnection(URL url)throws IOException {
                if(!url.getHost().equals(URI.create(BuildConfig.API_URL).getHost()))throw new IOException("Unexpected host in controlled test");
                return new FakeConnection(url);
            }
        }:null);
    }
    @Before public void setup() throws Exception {
        Assume.assumeFalse(BuildConfig.DEMO_MODE);
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("tailoring",0).edit().clear().commit();
        var configuration=new AuthorizationServiceConfiguration(Uri.parse(BuildConfig.COGNITO_DOMAIN+"/oauth2/authorize"),Uri.parse(BuildConfig.COGNITO_DOMAIN+"/oauth2/token"));
        var request=new TokenRequest.Builder(configuration,BuildConfig.COGNITO_CLIENT_ID).setGrantType(GrantTypeValues.REFRESH_TOKEN).setRefreshToken("dummy-refresh").build();
        var auth=new AuthState();auth.update(new TokenResponse.Builder(request).setAccessToken("dummy-access").setTokenType("Bearer")
                .setAccessTokenExpirationTime(System.currentTimeMillis()+3600000).build(),null);
        new SecureStore(context).write(auth.jsonSerializeString());
        synchronized(AsyncTailoringUiTest.class) {
            application=new JSONObject().put("id",APP).put("company","Controlled AI employer").put("role","Java Intern")
                .put("status","Applied").put("appliedDate","2026-08-12").put("jobUrl","https://example.org/job")
                .put("jobDescription","Java internship using unit tests").put("notes","").put("cvId",SOURCE).put("cvName","Original.txt");
        }
        state="RUNNING";unavailable=false;cvUnavailable=false;starts.set(0);polls.set(0);saves.set(0);Intents.init();
        screen=ActivityScenario.launch(new Intent(context,EditApplicationActivity.class).putExtra("applicationId",APP));
        waitFor(allOf(withId(R.id.companyInput),withText("Controlled AI employer")));
        onView(withId(R.id.cvInput)).perform(scrollTo());
        waitFor(allOf(withId(R.id.cvInput),withSpinnerText(containsString("Original.txt"))));
    }
    @After public void cleanup() {
        if(screen!=null)screen.close();Intents.release();
        if(context!=null){new SecureStore(context).clear();context.getSharedPreferences("tailoring",0).edit().clear().commit();}
    }
    private static void waitFor(Matcher<View> matcher) {
        long end=System.currentTimeMillis()+12000;
        do {try {onView(matcher).check(matches(isDisplayed()));return;}catch(AssertionError|NoMatchingViewException error){
            try {Thread.sleep(50);}catch(InterruptedException interrupted){throw new AssertionError(interrupted);}
        }}while(System.currentTimeMillis()<end);
        onView(matcher).check(matches(isDisplayed()));
    }
    private void start() {
        onView(withId(R.id.tailorButton)).perform(scrollTo(),click());
        waitFor(allOf(withId(R.id.tailorButton),withText("Tailoring in progress")));
    }
    private void complete() { state="COMPLETED";waitFor(withText("Tailored CV ready")); }
    @Test public void pendingJobDisablesActionsAndResumesAfterActivityRecreation() {
        start();onView(withId(R.id.saveButton)).check(matches(not(isEnabled())));
        assertEquals(JOB,context.getSharedPreferences("tailoring",0).getString(APP,null));
        screen.recreate();onView(withId(R.id.tailorButton)).perform(scrollTo());
        waitFor(allOf(withId(R.id.tailorButton),withText("Tailoring in progress")));
        complete();assertEquals(1,starts.get());assertNull(context.getSharedPreferences("tailoring",0).getString(APP,null));
    }
    @Test public void completedJobShowsAReviewPreviewBeforeAttachment() {
        start();complete();onView(withId(android.R.id.message)).check(matches(withText(containsString("Review all changes"))));
        onView(withId(android.R.id.message)).check(matches(withText(containsString("CONTROLLED GENERATED CV"))));
        onView(withId(android.R.id.button2)).perform(click());
        synchronized(AsyncTailoringUiTest.class){assertEquals(SOURCE,application.optString("cvId"));}
    }
    @Test public void reviewedResultIsAttachedOnlyOnConfirmation() throws Exception {
        start();complete();onView(withId(android.R.id.button1)).perform(click());
        long end=System.currentTimeMillis()+5000;
        do {synchronized(AsyncTailoringUiTest.class){if(GENERATED.equals(application.optString("cvId")))return;}Thread.sleep(50);}while(System.currentTimeMillis()<end);
        fail("Confirmed CV was not attached");
    }
    @Test public void downloadOpensTheReturnedDocumentUrl() {
        Intents.intending(hasAction(Intent.ACTION_VIEW)).respondWith(new android.app.Instrumentation.ActivityResult(Activity.RESULT_OK,null));
        start();complete();onView(withId(android.R.id.button3)).perform(click());
        long end=System.currentTimeMillis()+5000;
        do {if(Intents.getIntents().stream().anyMatch(intent->"https://example.org/tailored.docx".equals(String.valueOf(intent.getData()))))return;
            try {Thread.sleep(50);}catch(InterruptedException error){throw new AssertionError(error);}}while(System.currentTimeMillis()<end);
        fail("CV download did not open the returned URL");
    }
    @Test public void failedJobExplainsTheCauseAndClearsPendingState() {
        start();state="FAILED";waitFor(withText("CV tailoring unavailable"));
        onView(withId(android.R.id.message)).check(matches(withText(containsString("no OpenAI API credits"))));
        onView(withId(android.R.id.button1)).perform(click());onView(withId(R.id.tailorButton)).check(matches(isEnabled()));
        assertNull(context.getSharedPreferences("tailoring",0).getString(APP,null));assertEquals(1,starts.get());
    }
    @Test public void networkFailureRetainsTheJobAndManualStatusCheckDoesNotStartAnother() {
        unavailable=true;start();waitFor(allOf(withId(R.id.tailorButton),withText("Check tailoring status")));
        assertEquals(JOB,context.getSharedPreferences("tailoring",0).getString(APP,null));
        unavailable=false;state="COMPLETED";onView(withId(R.id.tailorButton)).perform(click());
        waitFor(withText("Tailored CV ready"));assertEquals(1,starts.get());assertTrue(polls.get()>=3);
    }
    @Test public void savingWhileTheCvLibraryIsUnavailableKeepsTheSubmittedCv() throws Exception {
        cvUnavailable=true;screen.recreate();
        waitFor(allOf(withId(R.id.companyInput),withText("Controlled AI employer")));
        onView(withId(R.id.saveButton)).perform(scrollTo(),click());
        long end=System.currentTimeMillis()+5000;
        while(saves.get()==0&&System.currentTimeMillis()<end)Thread.sleep(50);
        assertEquals(1,saves.get());
        synchronized(AsyncTailoringUiTest.class){assertEquals(SOURCE,application.optString("cvId"));}
    }
    private static JSONObject cv(String id,String name)throws JSONException {return new JSONObject().put("id",id).put("name",name).put("kind",id.equals(SOURCE)?"original":"tailored");}
    private static class FakeConnection extends HttpURLConnection {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();JSONObject response;int status=200;
        FakeConnection(URL url){super(url);}
        public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
        public OutputStream getOutputStream(){return bytes;}
        private void respond() {
            if(response!=null)return;
            synchronized(AsyncTailoringUiTest.class) {
                try {
                    String path=url.getPath();
                    if(path.equals("/cvs")) {
                        if(cvUnavailable){status=503;response=new JSONObject().put("message","Controlled CV library failure");}
                        else response=new JSONObject().put("cvs",new JSONArray().put(cv(SOURCE,"Original.txt")).put(cv(GENERATED,"Tailored.docx")));
                    }
                    else if(path.equals("/applications/"+APP+"/tailor")){starts.incrementAndGet();status=202;response=new JSONObject().put("jobId",JOB).put("status","QUEUED");}
                    else if(path.equals("/applications/"+APP)){
                        if("PUT".equals(method)){application=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)).put("id",APP);saves.incrementAndGet();}
                        response=new JSONObject(application.toString());
                    }else if(path.equals("/tailoring-jobs/"+JOB)){
                        polls.incrementAndGet();
                        if(unavailable){status=503;response=new JSONObject().put("message","Controlled network failure");}
                        else {
                            response=new JSONObject().put("jobId",JOB).put("status",state);
                            if(state.equals("COMPLETED"))response.put("tailoredCv",cv(GENERATED,"Tailored.docx")).put("preview","CONTROLLED GENERATED CV: Java and unit testing.");
                            if(state.equals("FAILED"))response.put("message","The app has no OpenAI API credits. The app owner needs to add credits.");
                        }
                    }else if(path.equals("/cvs/"+GENERATED+"/download-url"))response=new JSONObject().put("url","https://example.org/tailored.docx");
                    else {status=404;response=new JSONObject().put("message","Unknown controlled route");}
                }catch(Exception error){throw new AssertionError("Controlled response setup failed",error);}
            }
        }
        public int getResponseCode(){respond();return status;}
        public InputStream getInputStream(){respond();return new ByteArrayInputStream(response.toString().getBytes(StandardCharsets.UTF_8));}
        public InputStream getErrorStream(){return getInputStream();}
    }
}
