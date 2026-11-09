package com.interntrack;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OnlineGatingTest {
    private ActivityScenario<MainActivity> screen;
    @Before public void setup() {
        Assume.assumeFalse(BuildConfig.DEMO_MODE);
        new SecureStore(InstrumentationRegistry.getInstrumentation().getTargetContext()).clear();
        screen=ActivityScenario.launch(MainActivity.class);
        onView(withText("Sign in to InternTrack")).check(matches(isDisplayed()));
        onView(withId(android.R.id.button2)).perform(click());
    }
    @After public void cleanup() { if(screen!=null) screen.close(); }
    @Test public void newApplicationRequiresSignIn() {
        onView(withId(R.id.newApplicationButton)).perform(scrollTo(),click());
        onView(withText("Sign in to InternTrack")).check(matches(isDisplayed()));
        onView(withId(R.id.companyInput)).check(doesNotExist());
    }
    @Test public void cvLibraryRequiresSignIn() {
        onView(withId(R.id.cvLibraryButton)).perform(click());
        onView(withText("Sign in to InternTrack")).check(matches(isDisplayed()));
        onView(withId(R.id.uploadButton)).check(doesNotExist());
    }
    @Test public void accountOffersSignInAndDoesNotRevealPrivateData() {
        onView(withId(R.id.accountButton)).perform(click());
        onView(withText("Sign in to InternTrack")).check(matches(isDisplayed()));
    }
    @Test public void onlineApkContainsRealHttpsServiceSettings() {
        assertTrue(AuthManager.isConfigured());
        assertTrue(BuildConfig.API_URL.startsWith("https://"));
        assertTrue(BuildConfig.COGNITO_DOMAIN.startsWith("https://"));
        assertTrue(BuildConfig.ASSET_BASE_URL.startsWith("https://"));
    }
}
