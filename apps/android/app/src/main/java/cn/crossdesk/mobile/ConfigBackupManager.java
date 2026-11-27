package cn.crossdesk.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Encrypted backup/restore for local settings, recents, shortcuts, and user-saved passwords. */
final class ConfigBackupManager {
    private static final String TAG = "CrossDeskBackup";
    static final String FORMAT = "crossdesk-mobile-backup";
    static final int VERSION = 1;
    static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    static final int ITERATIONS = 100000;
    private static final int MIN_ITERATIONS = 10000;
    private static final int MAX_ITERATIONS = 500000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    static final int MAX_BACKUP_BYTES = 2 * 1024 * 1024;

    private static final String DEFAULT_HOST = "api.crossdesk.cn";
    private static final int DEFAULT_PORT = 9099;
    private static final String PREFS_SETTINGS = "settings";
    private static final String PREFS_IDENTITIES = "identities";
    private static final String PREFS_SHORTCUTS = "remote_shortcuts_v1";
    private static final String RECENT_LIST_PREFIX = "recentList:";
    private static final String RECENT_LEGACY_PREFIX = "recent:";
    private static final String REMOTE_PASSWORD_PREFIX = "remote-password:";
    private static final String WIN_LOCK_PREFIX = "win_lock_";
    private static final String[] SHORTCUT_KEYS = {
            "custom", "linux_desktop", "pins_windows", "pins_macos", "pins_linux"
    };

    static final class RestoreSummary {
        final int deviceCount;
        final int remotePasswordCount;
        final int lockPasswordCount;
        final String host;
        final int port;

        RestoreSummary(int deviceCount, int remotePasswordCount, int lockPasswordCount, String host, int port) {
            this.deviceCount = deviceCount;
            this.remotePasswordCount = remotePasswordCount;
            this.lockPasswordCount = lockPasswordCount;
            this.host = host;
            this.port = port;
        }

        String serverEndpoint() {
            return host + ":" + port;
        }
    }

    private ConfigBackupManager() {}

    static void exportToUri(Context context, Uri uri, String passphrase) throws Exception {
        if (context == null || uri == null) {
            throw new IllegalArgumentException(L10n.tr("无效的导出目标"));
        }
        if (passphrase == null || passphrase.length() < 4) {
            throw new IllegalArgumentException(L10n.tr("口令长度至少为 4 位"));
        }
        Context app = context.getApplicationContext();
        JSONObject payload = collectPayload(app);
        byte[] plaintext = payload.toString().getBytes(StandardCharsets.UTF_8);
        byte[] envelopeBytes = encryptEnvelope(plaintext, passphrase);
        Arrays.fill(plaintext, (byte) 0);
        writeBytesToUri(app, uri, envelopeBytes);
    }

    static RestoreSummary importFromUri(Context context, Uri uri, String passphrase) throws Exception {
        if (context == null || uri == null) {
            throw new IllegalArgumentException(L10n.tr("无效的备份文件"));
        }
        if (passphrase == null || passphrase.isEmpty()) {
            throw new IllegalArgumentException(L10n.tr("请输入备份口令"));
        }
        Context app = context.getApplicationContext();
        byte[] envelopeBytes = readBytesFromUri(app, uri);
        byte[] plaintext = decryptEnvelope(envelopeBytes, passphrase);
        try {
            JSONObject payload = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
            return applyPayload(app, payload);
        } catch (JSONException error) {
            throw new IllegalArgumentException(L10n.tr("备份内容格式无效"), error);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static JSONObject collectPayload(Context context) throws JSONException {
        SharedPreferences settings = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
        SharedPreferences identities = context.getSharedPreferences(PREFS_IDENTITIES, Context.MODE_PRIVATE);
        SharedPreferences shortcutsPrefs = context.getSharedPreferences(PREFS_SHORTCUTS, Context.MODE_PRIVATE);
        SecretStore secrets = new SecretStore(context);

        JSONObject server = new JSONObject();
        server.put("host", settings.getString("host", DEFAULT_HOST));
        server.put("port", settings.getInt("port", DEFAULT_PORT));

        JSONObject prefs = new JSONObject();
        prefs.put("relativeMouse", settings.getBoolean("relativeMouse", true));
        prefs.put("videoPreference", settings.getInt("videoPreference", 1));
        prefs.put("autoLandscape", settings.getBoolean("autoLandscape", false));
        prefs.put("autoClipboard", settings.getBoolean("autoClipboard", true));
        prefs.put("lastRemote", settings.getString("lastRemote", ""));
        prefs.put("virtualMouseMode", settings.getInt("virtualMouseMode", VirtualMouseBar.MODE_EXPANDED));
        prefs.put("thumbMouseLayout", settings.getString("thumbMouseLayout", ""));

        JSONObject recents = new JSONObject();
        Map<String, ?> allSettings = settings.getAll();
        for (Map.Entry<String, ?> entry : allSettings.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key != null && key.startsWith(RECENT_LIST_PREFIX) && value instanceof String) {
                String serverKey = key.substring(RECENT_LIST_PREFIX.length());
                if (serverKey.isEmpty()) continue;
                try {
                    recents.put(serverKey, new JSONArray((String) value));
                } catch (JSONException error) {
                    Log.w(TAG, "Skipped malformed recentList for " + serverKey, error);
                }
            }
        }
        for (Map.Entry<String, ?> entry : allSettings.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key != null && key.startsWith(RECENT_LEGACY_PREFIX) && value instanceof String) {
                String serverKey = key.substring(RECENT_LEGACY_PREFIX.length());
                String legacyId = (String) value;
                if (serverKey.isEmpty() || legacyId.isEmpty() || recents.has(serverKey)) continue;
                recents.put(serverKey, new JSONArray().put(
                        new JSONObject().put("id", legacyId).put("name", legacyId)));
            }
        }

        JSONArray remotePasswords = new JSONArray();
        Iterator<String> serverKeys = recents.keys();
        while (serverKeys.hasNext()) {
            String serverKey = serverKeys.next();
            JSONArray list = recents.optJSONArray(serverKey);
            if (list == null) continue;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null || !item.optBoolean("remember", false)) continue;
                String id = item.optString("id", "");
                if (id.isEmpty()) continue;
                String scope = REMOTE_PASSWORD_PREFIX + serverKey + ":" + id;
                try {
                    String password = secrets.get(scope);
                    if (!password.isEmpty()) {
                        remotePasswords.put(new JSONObject()
                                .put("server", serverKey)
                                .put("id", id)
                                .put("password", password));
                    }
                } catch (Exception error) {
                    Log.w(TAG, "Skipped unreadable remote password for " + scope, error);
                }
            }
        }

        // Only export biometric lock-screen credentials; never export signaling identity keys.
        JSONObject lockPasswords = new JSONObject();
        Map<String, ?> allIdentities = identities.getAll();
        for (String key : allIdentities.keySet()) {
            if (key != null && key.startsWith(WIN_LOCK_PREFIX) && key.length() > WIN_LOCK_PREFIX.length()) {
                try {
                    String password = secrets.get(key);
                    if (!password.isEmpty()) {
                        lockPasswords.put(key, password);
                    }
                } catch (Exception error) {
                    Log.w(TAG, "Skipped unreadable lock password for " + key, error);
                }
            }
        }

        JSONObject shortcuts = new JSONObject();
        Map<String, ?> allShortcuts = shortcutsPrefs.getAll();
        for (String key : SHORTCUT_KEYS) {
            if (shortcutsPrefs.contains(key)) {
                Object value = allShortcuts.get(key);
                if (value instanceof String) {
                    shortcuts.put(key, (String) value);
                }
            }
        }

        JSONObject payload = new JSONObject();
        payload.put("server", server);
        payload.put("preferences", prefs);
        payload.put("recents", recents);
        payload.put("remotePasswords", remotePasswords);
        payload.put("lockPasswords", lockPasswords);
        payload.put("shortcuts", shortcuts);
        return payload;
    }

    private static RestoreSummary applyPayload(Context context, JSONObject payload) throws Exception {
        JSONObject serverObj = payload.optJSONObject("server");
        String host = serverObj != null ? serverObj.optString("host", DEFAULT_HOST).trim() : DEFAULT_HOST;
        int port = serverObj != null ? serverObj.optInt("port", DEFAULT_PORT) : DEFAULT_PORT;
        if (!isValidHost(host)) {
            throw new IllegalArgumentException(L10n.tr("备份文件中的服务器地址无效"));
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(L10n.tr("备份文件中的服务器端口无效"));
        }

        SharedPreferences settings = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
        SharedPreferences.Editor settingsEditor = settings.edit();
        settingsEditor.putString("host", host);
        settingsEditor.putInt("port", port);

        JSONObject prefObj = payload.optJSONObject("preferences");
        if (prefObj != null) {
            settingsEditor.putBoolean("relativeMouse", prefObj.optBoolean("relativeMouse", true));
            int videoPreference = Math.max(0, Math.min(2, prefObj.optInt("videoPreference", 1)));
            settingsEditor.putInt("videoPreference", videoPreference);
            settingsEditor.putBoolean("autoLandscape", prefObj.optBoolean("autoLandscape", false));
            settingsEditor.putBoolean("autoClipboard", prefObj.optBoolean("autoClipboard", true));
            settingsEditor.putString("lastRemote", prefObj.optString("lastRemote", ""));
            if (prefObj.has("virtualMouseMode")) {
                int vmMode = Math.max(VirtualMouseBar.MODE_EXPANDED,
                        Math.min(VirtualMouseBar.MODE_MINI, prefObj.optInt("virtualMouseMode", VirtualMouseBar.MODE_EXPANDED)));
                settingsEditor.putInt("virtualMouseMode", vmMode);
            }
            if (prefObj.has("thumbMouseLayout") && prefObj.opt("thumbMouseLayout") instanceof String) {
                settingsEditor.putString("thumbMouseLayout", prefObj.optString("thumbMouseLayout", ""));
            }
        }

        int deviceCount = 0;
        JSONObject recentsObj = payload.optJSONObject("recents");
        if (recentsObj != null) {
            Iterator<String> serverKeys = recentsObj.keys();
            while (serverKeys.hasNext()) {
                String serverKey = serverKeys.next();
                if (serverKey == null || serverKey.trim().isEmpty()) continue;
                JSONArray list = recentsObj.optJSONArray(serverKey);
                if (list == null) continue;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item != null && !item.optString("id", "").isEmpty()) {
                        deviceCount++;
                    }
                }
                settingsEditor.putString(RECENT_LIST_PREFIX + serverKey, list.toString());
                settingsEditor.remove(RECENT_LEGACY_PREFIX + serverKey);
            }
        }
        if (!settingsEditor.commit()) {
            throw new IOException(L10n.tr("保存应用设置失败"));
        }

        JSONObject shortcutsObj = payload.optJSONObject("shortcuts");
        if (shortcutsObj != null) {
            SharedPreferences shortcutsPrefs = context.getSharedPreferences(PREFS_SHORTCUTS, Context.MODE_PRIVATE);
            SharedPreferences.Editor scEditor = shortcutsPrefs.edit();
            for (String key : SHORTCUT_KEYS) {
                if (shortcutsObj.has(key) && !shortcutsObj.isNull(key)) {
                    Object raw = shortcutsObj.opt(key);
                    if (raw instanceof String) {
                        String value = (String) raw;
                        if ("linux_desktop".equals(key) && !"gnome".equals(value) && !"kde".equals(value)) {
                            value = "";
                        }
                        scEditor.putString(key, value);
                    }
                }
            }
            if (!scEditor.commit()) {
                throw new IOException(L10n.tr("保存快捷键配置失败"));
            }
        }

        SecretStore secrets = new SecretStore(context);
        int remotePasswordCount = 0;
        JSONArray remoteArray = payload.optJSONArray("remotePasswords");
        if (remoteArray != null) {
            for (int i = 0; i < remoteArray.length(); i++) {
                JSONObject entry = remoteArray.optJSONObject(i);
                if (entry == null) continue;
                String serverKey = entry.optString("server", "");
                String id = entry.optString("id", "");
                String password = entry.optString("password", "");
                if (serverKey.isEmpty() || id.isEmpty() || password.isEmpty()) continue;
                String scope = REMOTE_PASSWORD_PREFIX + serverKey + ":" + id;
                try {
                    secrets.put(scope, password);
                    remotePasswordCount++;
                } catch (Exception error) {
                    Log.w(TAG, "Failed to restore remote password for " + scope, error);
                }
            }
        } else {
            JSONObject remoteObj = payload.optJSONObject("remotePasswords");
            if (remoteObj != null) {
                Iterator<String> scopes = remoteObj.keys();
                while (scopes.hasNext()) {
                    String scope = scopes.next();
                    if (scope == null || !scope.startsWith(REMOTE_PASSWORD_PREFIX)
                            || scope.length() <= REMOTE_PASSWORD_PREFIX.length()) {
                        continue;
                    }
                    String password = remoteObj.optString(scope, "");
                    if (password.isEmpty()) continue;
                    try {
                        secrets.put(scope, password);
                        remotePasswordCount++;
                    } catch (Exception error) {
                        Log.w(TAG, "Failed to restore remote password for " + scope, error);
                    }
                }
            }
        }

        int lockPasswordCount = 0;
        JSONObject lockObj = payload.optJSONObject("lockPasswords");
        if (lockObj != null) {
            Iterator<String> lockKeys = lockObj.keys();
            while (lockKeys.hasNext()) {
                String key = lockKeys.next();
                if (key == null || !key.startsWith(WIN_LOCK_PREFIX)
                        || key.length() <= WIN_LOCK_PREFIX.length()) {
                    continue;
                }
                String password = lockObj.optString(key, "");
                if (password.isEmpty()) continue;
                try {
                    secrets.put(key, password);
                    lockPasswordCount++;
                } catch (Exception error) {
                    Log.w(TAG, "Failed to restore lock password for " + key, error);
                }
            }
        }

        return new RestoreSummary(deviceCount, remotePasswordCount, lockPasswordCount, host, port);
    }

    static boolean isValidHost(String host) {
        return host != null && !host.isEmpty() && host.length() <= 253
                && host.matches("[A-Za-z0-9.-]+")
                && !host.startsWith(".") && !host.endsWith(".");
    }

    private static byte[] encryptEnvelope(byte[] plaintext, String passphrase) throws Exception {
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);

        byte[] keyBytes = deriveKey(passphrase, salt, ITERATIONS);
        try {
            SecretKeySpec aesKey = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            JSONObject envelope = new JSONObject();
            envelope.put("format", FORMAT);
            envelope.put("version", VERSION);
            envelope.put("kdf", KDF_ALGORITHM);
            envelope.put("iterations", ITERATIONS);
            envelope.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
            envelope.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
            envelope.put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
            return envelope.toString().getBytes(StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private static byte[] decryptEnvelope(byte[] envelopeBytes, String passphrase) throws Exception {
        JSONObject envelope;
        try {
            envelope = new JSONObject(new String(envelopeBytes, StandardCharsets.UTF_8));
        } catch (JSONException error) {
            throw new IllegalArgumentException(L10n.tr("备份文件格式无效"), error);
        }

        if (!FORMAT.equals(envelope.optString("format", ""))) {
            throw new IllegalArgumentException(L10n.tr("不是有效的 CrossDesk 备份文件"));
        }
        if (envelope.optInt("version", -1) != VERSION) {
            throw new IllegalArgumentException(L10n.tr("不支持的备份文件版本"));
        }
        if (!KDF_ALGORITHM.equals(envelope.optString("kdf", ""))) {
            throw new IllegalArgumentException(L10n.tr("不支持的加密算法"));
        }
        int iterations = envelope.optInt("iterations", -1);
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException(L10n.tr("备份文件加密参数无效"));
        }

        byte[] salt;
        byte[] iv;
        byte[] ciphertext;
        try {
            salt = Base64.decode(envelope.optString("salt", ""), Base64.DEFAULT);
            iv = Base64.decode(envelope.optString("iv", ""), Base64.DEFAULT);
            ciphertext = Base64.decode(envelope.optString("ciphertext", ""), Base64.DEFAULT);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(L10n.tr("备份文件编码已损坏"), error);
        }

        if (salt.length != SALT_BYTES || iv.length != IV_BYTES || ciphertext.length < GCM_TAG_BITS / 8) {
            throw new IllegalArgumentException(L10n.tr("备份文件格式已损坏"));
        }

        byte[] keyBytes = deriveKey(passphrase, salt, iterations);
        try {
            SecretKeySpec aesKey = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException error) {
            throw new IllegalArgumentException(L10n.tr("口令错误或备份文件已损坏"), error);
        } catch (GeneralSecurityException error) {
            Log.e(TAG, "Decryption failed", error);
            throw new IllegalArgumentException(L10n.tr("解密备份文件失败"), error);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private static byte[] deriveKey(String passphrase, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    static byte[] readBytesFromUri(Context context, Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException(L10n.tr("无法打开所选备份文件"));
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int total = 0;
            int count;
            while ((count = input.read(chunk)) != -1) {
                total += count;
                if (total > MAX_BACKUP_BYTES) {
                    throw new IllegalArgumentException(L10n.tr("备份文件过大（超过 2 MB）"));
                }
                buffer.write(chunk, 0, count);
            }
            return buffer.toByteArray();
        } catch (SecurityException error) {
            Log.e(TAG, "Permission denied reading backup uri", error);
            throw new IOException(L10n.tr("没有权限读取所选文件"), error);
        }
    }

    static void writeBytesToUri(Context context, Uri uri, byte[] data) throws IOException {
        try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) {
                throw new IOException(L10n.tr("无法写入目标备份文件"));
            }
            output.write(data);
            output.flush();
        } catch (SecurityException error) {
            Log.e(TAG, "Permission denied writing backup uri", error);
            throw new IOException(L10n.tr("没有权限写入目标文件"), error);
        }
    }
}
