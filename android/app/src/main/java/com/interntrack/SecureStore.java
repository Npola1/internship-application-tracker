package com.interntrack;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores Cognito AuthState encrypted by a non-exportable Android Keystore key. */
final class SecureStore {
    private static final String ALIAS = "interntrack.auth.state.v1";
    private final SharedPreferences prefs;
    SecureStore(Context context) { prefs = context.getSharedPreferences("secure_state", Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
    }
    String read() {
        try {
            String encoded = prefs.getString("auth", null);
            if (encoded == null) return null;
            byte[] data = Base64.decode(encoded, Base64.NO_WRAP);
            byte[] iv = new byte[12];
            System.arraycopy(data, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (Exception ex) { clear(); return null; }
    }
    void write(String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // Android Keystore requires the cipher to generate the encryption IV.
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            prefs.edit().putString("auth", Base64.encodeToString(combined, Base64.NO_WRAP)).apply();
        } catch (Exception ex) { throw new IllegalStateException("Could not encrypt auth state", ex); }
    }
    void clear() { prefs.edit().remove("auth").apply(); }
}
