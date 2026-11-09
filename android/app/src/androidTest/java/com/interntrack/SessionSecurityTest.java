package com.interntrack;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import net.openid.appauth.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SessionSecurityTest {
    private Context context;
    private SecureStore store;
    @Before public void setup() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        store=new SecureStore(context); store.clear();
    }
    @After public void cleanup() { store.clear(); }
    @Test public void encryptedSessionSurvivesANewStoreInstance() {
        store.write("dummy-session-value");
        assertEquals("dummy-session-value",new SecureStore(context).read());
        assertFalse(context.getSharedPreferences("secure_state",0).getString("auth","").contains("dummy-session-value"));
    }
    @Test public void repeatedWritesUseDifferentCiphertexts() {
        store.write("dummy-session-value"); String first=context.getSharedPreferences("secure_state",0).getString("auth","");
        store.write("dummy-session-value"); String second=context.getSharedPreferences("secure_state",0).getString("auth","");
        assertNotEquals(first,second); assertEquals("dummy-session-value",store.read());
    }
    @Test public void tamperingFailsClosedAndRemovesTheCorruptedSession() {
        store.write("dummy-session-value");
        var prefs=context.getSharedPreferences("secure_state",0);
        byte[] encrypted=Base64.decode(prefs.getString("auth",""),Base64.NO_WRAP);
        encrypted[encrypted.length-1]^=1;
        prefs.edit().putString("auth",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit();
        assertNull(store.read()); assertFalse(prefs.contains("auth"));
    }
    @Test public void malformedAndTruncatedCiphertextsFailClosed() {
        for(String bad:new String[]{"%%%",Base64.encodeToString(new byte[]{1,2,3},Base64.NO_WRAP)}) {
            context.getSharedPreferences("secure_state",0).edit().putString("auth",bad).commit();
            assertNull(store.read());
        }
    }
    private AuthManager manager() {
        var result=new AtomicReference<AuthManager>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->result.set(new AuthManager(context)));
        return result.get();
    }
    private void close(AuthManager manager) { InstrumentationRegistry.getInstrumentation().runOnMainSync(manager::close); }
    @Test public void invalidSerializedAuthStateIsDiscarded() {
        store.write("not-json"); var auth=manager();
        try { assertFalse(auth.isSignedIn()); assertNull(store.read()); } finally { close(auth); }
    }
    @Test public void cancelledLoginDoesNotAuthorizeTheApp() {
        var auth=manager();
        try {
            var error=new AtomicReference<String>();
            auth.completeLogin(null,(ok,message)->{assertFalse(ok);error.set(message);});
            assertEquals("Sign-in cancelled",error.get()); assertFalse(auth.isSignedIn());
        } finally { close(auth); }
    }
    @Test public void loggedOutRequestsAreRejectedWithoutANetworkCall() throws Exception {
        var auth=manager(); var done=new CountDownLatch(1); var result=new AtomicReference<String>();
        try {
            auth.withAccessToken((token,error)->{result.set(token==null?error:"unexpected token");done.countDown();});
            assertTrue(done.await(5,TimeUnit.SECONDS)); assertEquals("Sign in first",result.get());
        } finally { close(auth); }
    }
    @Test public void freshTokensPersistAndSignOutClearsThem() throws Exception {
        var configuration=new AuthorizationServiceConfiguration(Uri.parse("https://example.org/authorize"),Uri.parse("https://example.org/token"));
        var state=new AuthState();
        var request=new TokenRequest.Builder(configuration,"test-client").setGrantType(GrantTypeValues.REFRESH_TOKEN).setRefreshToken("dummy-refresh-token").build();
        state.update(new TokenResponse.Builder(request).setAccessToken("dummy-access-token").setTokenType("Bearer")
                .setAccessTokenExpirationTime(System.currentTimeMillis()+3600000).setRefreshToken("dummy-refresh-token").build(),null);
        store.write(state.jsonSerializeString()); var auth=manager();
        try {
            assertTrue(auth.isSignedIn());
            var done=new CountDownLatch(1); var token=new AtomicReference<String>();
            auth.withAccessToken((value,error)->{token.set(value);done.countDown();});
            assertTrue(done.await(5,TimeUnit.SECONDS)); assertEquals("dummy-access-token",token.get());
            auth.signOut(); assertFalse(auth.isSignedIn()); assertNull(store.read());
        } finally { close(auth); }
        var reopened=manager(); try { assertFalse(reopened.isSignedIn()); } finally { close(reopened); }
    }
    @Test public void signInRequestsPkceStateAndTheCorrectCallback() {
        var auth=manager(); var captured=new AtomicReference<Intent>();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->auth.signIn(new Activity(){
                @Override public void startActivityForResult(Intent intent,int code) { captured.set(intent); }
            }));
            Bundle extras=captured.get().getExtras(); Uri browser=null;
            for(String key:extras.keySet()) { Object value=extras.get(key); if(value instanceof Intent) browser=((Intent)value).getData(); }
            assertNotNull(browser);
            assertEquals("code",browser.getQueryParameter("response_type"));
            assertEquals("S256",browser.getQueryParameter("code_challenge_method"));
            assertTrue(browser.getQueryParameter("code_challenge").length()>=43);
            assertFalse(browser.getQueryParameter("state").isEmpty());
            assertEquals("interntrack://oauth2redirect",browser.getQueryParameter("redirect_uri"));
            assertEquals(BuildConfig.COGNITO_CLIENT_ID,browser.getQueryParameter("client_id"));
        } finally { close(auth); }
    }
}
