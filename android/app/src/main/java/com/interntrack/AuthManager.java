package com.interntrack;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ResponseTypeValues;

import org.json.JSONException;

final class AuthManager {
    static final int LOGIN_REQUEST_CODE = 402;
    private final AuthorizationService service;
    private final AuthorizationServiceConfiguration configuration;
    private final SecureStore store;
    private AuthState state;
    private final Handler main = new Handler(Looper.getMainLooper());

    interface TokenCallback { void onToken(String token, String error); }
    interface LoginCallback { void onResult(boolean ok, String error); }

    AuthManager(Context context) {
        service = new AuthorizationService(context);
        store = new SecureStore(context);
        String saved = store.read();
        try { state = saved == null ? new AuthState() : AuthState.jsonDeserialize(saved); }
        catch (JSONException ex) { state = new AuthState(); store.clear(); }
        String domain = BuildConfig.COGNITO_DOMAIN.replaceAll("/+$", "");
        configuration = new AuthorizationServiceConfiguration(
                Uri.parse(domain + "/oauth2/authorize"), Uri.parse(domain + "/oauth2/token"));
    }
    static boolean isConfigured() {
        return !BuildConfig.API_URL.contains("REPLACE")
                && !BuildConfig.COGNITO_DOMAIN.contains("REPLACE")
                && !BuildConfig.COGNITO_CLIENT_ID.equals("REPLACE");
    }
    boolean isSignedIn() { return state.isAuthorized(); }
    void signIn(Activity activity) {
        AuthorizationRequest request = new AuthorizationRequest.Builder(configuration, BuildConfig.COGNITO_CLIENT_ID,
                ResponseTypeValues.CODE, Uri.parse("interntrack://oauth2redirect"))
                .setScopes("openid", "email", "profile", "aws.cognito.signin.user.admin")
                .build();
        activity.startActivityForResult(service.getAuthorizationRequestIntent(request), LOGIN_REQUEST_CODE);
    }
    void completeLogin(Intent data, LoginCallback callback) {
        if (data == null) { callback.onResult(false, "Sign-in cancelled"); return; }
        AuthorizationResponse response = AuthorizationResponse.fromIntent(data);
        AuthorizationException exception = AuthorizationException.fromIntent(data);
        if (response == null) {
            callback.onResult(false, exception == null ? "Sign-in cancelled" : exception.errorDescription);
            return;
        }
        state.update(response, exception);
        service.performTokenRequest(response.createTokenExchangeRequest(), (token, error) -> {
            state.update(token, error);
            if (error == null && state.isAuthorized()) {
                try { store.write(state.jsonSerializeString()); }
                catch (IllegalStateException ex) {
                    state = new AuthState(); store.clear();
                    main.post(() -> callback.onResult(false, "Could not save sign-in securely. Please retry."));
                    return;
                }
                main.post(() -> callback.onResult(true, null));
            } else main.post(() -> callback.onResult(false, "Could not exchange authentication code"));
        });
    }
    void withAccessToken(TokenCallback callback) {
        if (!state.isAuthorized()) { main.post(() -> callback.onToken(null, "Sign in first")); return; }
        state.performActionWithFreshTokens(service, (accessToken, idToken, ex) -> {
            if (ex != null || accessToken == null) {
                main.post(() -> callback.onToken(null, "Session expired. Sign in again."));
                return;
            }
            try { store.write(state.jsonSerializeString()); }
            catch (IllegalStateException error) {
                state = new AuthState(); store.clear();
                main.post(() -> callback.onToken(null, "Could not save the session securely. Sign in again."));
                return;
            }
            main.post(() -> callback.onToken(accessToken, null));
        });
    }
    void signOut() { state = new AuthState(); store.clear(); }
    void close() { service.dispose(); }
}
