package com.interntrack;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import net.openid.appauth.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Run only through scripts/test_live_android.py, which provisions and cleans a disposable cloud user. */
@RunWith(AndroidJUnit4.class)
public class LiveClientTest {
    private static Context context;
    private static JSONObject fixture;
    private AuthManager auth;
    private ApiClient api;
    @BeforeClass public static void fixture() throws Exception {
        Assume.assumeFalse(BuildConfig.DEMO_MODE);
        Assume.assumeTrue("Run through the disposable-user wrapper",
                "true".equals(InstrumentationRegistry.getArguments().getString("liveFixture")));
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        var path=new java.io.File(context.getFilesDir(),"live-test-auth.json").toPath();
        Assume.assumeTrue("Run via the disposable-user wrapper",Files.exists(path));
        try { fixture=new JSONObject(new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8)); } finally { Files.deleteIfExists(path); }
    }
    @Before public void setup() throws Exception {
        var configuration=new AuthorizationServiceConfiguration(Uri.parse(BuildConfig.COGNITO_DOMAIN+"/oauth2/authorize"),Uri.parse(BuildConfig.COGNITO_DOMAIN+"/oauth2/token"));
        var tokens=fixture.getJSONObject("tokens");
        var request=new TokenRequest.Builder(configuration,BuildConfig.COGNITO_CLIENT_ID).setGrantType(GrantTypeValues.REFRESH_TOKEN).setRefreshToken(tokens.getString("refresh_token")).build();
        var state=new AuthState();state.update(new TokenResponse.Builder(request).setAccessToken(tokens.getString("access_token"))
                .setRefreshToken(tokens.getString("refresh_token")).setTokenType("Bearer").setAccessTokenExpirationTime(System.currentTimeMillis()+3600000).build(),null);
        if("nativeRefresh".equals(InstrumentationRegistry.getArguments().getString("mode")))state.setNeedsTokenRefresh(true);
        new SecureStore(context).write(state.jsonSerializeString());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{auth=new AuthManager(context);api=new ApiClient(context,auth);});
    }
    @After public void cleanup() {
        if(auth!=null)InstrumentationRegistry.getInstrumentation().runOnMainSync(auth::close);
        if(context!=null)new SecureStore(context).clear();
    }
    private JSONObject request(String method,String path,JSONObject body) throws Exception {
        var done=new CountDownLatch(1);var value=new AtomicReference<JSONObject>();
        ApiClient.Callback callback=new ApiClient.Callback(){public void success(JSONObject result){value.set(result);done.countDown();}public void failure(String error){done.countDown();}};
        switch(method){case "POST":api.post(path,body,callback);break;case "PUT":api.put(path,body,callback);break;case "DELETE":api.delete(path,callback);break;default:api.get(path,callback);}
        assertTrue("Android API request timed out",done.await(40,TimeUnit.SECONDS));
        assertNotNull("Android API request failed; credentials and URLs are omitted",value.get());return value.get();
    }
    private JSONObject upload() throws Exception { return upload("Native-Student-CV.txt"); }
    private JSONObject upload(String name) throws Exception {
        var done=new CountDownLatch(1);var value=new AtomicReference<JSONObject>();
        Uri uri=Uri.parse("content://"+InstrumentationRegistry.getInstrumentation().getContext().getPackageName()+".fixtures/cv.txt");
        api.upload(context.getContentResolver(),uri,name,FixtureProvider.CV.length,new ApiClient.Callback(){
            public void success(JSONObject result){value.set(result);done.countDown();}public void failure(String error){done.countDown();}
        });
        assertTrue("Android CV upload timed out",done.await(40,TimeUnit.SECONDS));
        assertNotNull("Android CV upload failed; private details are omitted",value.get());return value.get();
    }
    @Test public void nativeDashboardLoadsTheRealDynamoDbAccount() throws Exception {
        var dashboard=request("GET","/dashboard",null);
        assertEquals(fixture.getString("sub"),dashboard.getJSONObject("account").getString("userId"));
        assertEquals("Feature test student",dashboard.getJSONObject("account").getString("name"));
    }
    @Test public void nativeClientUploadsDownloadsAndAttachesACvToAnApplication() throws Exception {
        var cv=upload();var link=request("GET","/cvs/"+cv.getString("id")+"/download-url",null);
        byte[] downloaded;
        try {
            var connection=(java.net.HttpURLConnection)new java.net.URL(link.getString("url")).openConnection();
            try { connection.setConnectTimeout(10000);connection.setReadTimeout(10000);downloaded=ApiClient.readAll(connection.getInputStream(),1024*1024); }
            finally { connection.disconnect(); }
        } catch(Exception error){throw new AssertionError("Native signed CV download failed; private URL omitted");}
        assertArrayEquals(FixtureProvider.CV,downloaded);
        var app=request("POST","/applications",new JSONObject().put("company","Native client test").put("role","Java Intern")
                .put("jobUrl","https://example.org/java").put("appliedDate","2026-08-12").put("status","Applied").put("cvId",cv.getString("id")));
        String path="/applications/"+app.getString("id");
        assertEquals("2026-08-12",request("GET",path,null).getString("appliedDate"));
        app.put("status","Interview");assertEquals("Interview",request("PUT",path,app).getString("status"));
        assertEquals(cv.getString("id"),request("GET",path,null).getString("cvId"));
        request("DELETE",path,null);
    }
    @Test public void nativeClientLoadsCloudFrontTips() throws Exception {
        var done=new CountDownLatch(1);var result=new AtomicReference<JSONObject>();
        api.publicTips(new ApiClient.Callback(){public void success(JSONObject value){result.set(value);done.countDown();}public void failure(String error){done.countDown();}});
        assertTrue(done.await(20,TimeUnit.SECONDS));assertNotNull(result.get());assertTrue(result.get().getJSONArray("tips").length()>0);
    }
    @Test public void nativeAppAuthRefreshesTheCognitoToken() throws Exception {
        var saved=AuthState.jsonDeserialize(new SecureStore(context).read());saved.setNeedsTokenRefresh(true);
        new SecureStore(context).write(saved.jsonSerializeString());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{auth.close();auth=new AuthManager(context);api=new ApiClient(context,auth);});
        assertEquals(fixture.getString("sub"),request("GET","/me",null).getString("userId"));
    }
    @Test public void onlineLibraryOpensTheSelectedPrivateCvInAViewer() throws Exception {
        upload("Native-library-CV.txt");
        androidx.test.espresso.intent.Intents.init();
        try(var screen=androidx.test.core.app.ActivityScenario.launch(CvLibraryActivity.class)) {
            androidx.test.espresso.intent.Intents.intending(androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(android.content.Intent.ACTION_VIEW))
                    .respondWith(new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,null));
            long end=System.currentTimeMillis()+7000;
            do { try { androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Native-library-CV.txt"))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));break;
                }catch(AssertionError|androidx.test.espresso.NoMatchingViewException error){Thread.sleep(50);} }while(System.currentTimeMillis()<end);
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Native-library-CV.txt"))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            end=System.currentTimeMillis()+7000;
            boolean opened=false;
            do {
                for(var intent:androidx.test.espresso.intent.Intents.getIntents())
                    if(android.content.Intent.ACTION_VIEW.equals(intent.getAction())&&intent.getData()!=null
                            &&"https".equals(intent.getData().getScheme())&&intent.getData().getPath().contains(fixture.getString("sub"))) opened=true;
                if(opened)break;Thread.sleep(50);
            }while(System.currentTimeMillis()<end);
            assertTrue("The library did not open the selected private CV; URL omitted",opened);
        } finally { androidx.test.espresso.intent.Intents.release(); }
    }
}
