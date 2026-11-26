package cn.crossdesk.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Manages biometric-gated Windows lock screen password storage and unlock dispatch.
 * Reuses SecretStore (AndroidKeyStore AES-GCM) to keep host credentials encrypted on device.
 */
final class BiometricUnlockManager {
    interface UnlockCallback {
        void onUnlocked(String password);
    }

    private static final String PREFIX = "win_lock_";
    private final SecretStore secretStore;

    BiometricUnlockManager(SecretStore secretStore) {
        this.secretStore = secretStore;
    }

    private static String scope(String remoteId) {
        return PREFIX + (remoteId == null ? "" : remoteId);
    }

    boolean hasPassword(String remoteId) {
        if (remoteId == null || remoteId.isEmpty()) return false;
        try {
            return !secretStore.get(scope(remoteId)).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    void promptAndUnlock(Activity activity, String remoteId, UnlockCallback callback) {
        if (remoteId == null || remoteId.isEmpty()) return;
        if (!hasPassword(remoteId)) {
            showPasswordSetupDialog(activity, remoteId, () -> authenticateAndDispatch(activity, remoteId, callback));
            return;
        }
        authenticateAndDispatch(activity, remoteId, callback);
    }

    private void authenticateAndDispatch(Activity activity, String remoteId, UnlockCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            dispatchDecrypted(activity, remoteId, callback);
            return;
        }
        try {
            BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                    .setTitle(L10n.tr("指纹解锁电脑"))
                    .setSubtitle(L10n.tr("验证通过后将自动唤醒屏幕并输入锁屏密码"))
                    .setNegativeButton(L10n.tr("取消"), activity.getMainExecutor(), (dialog, which) -> {})
                    .build();
            prompt.authenticate(new CancellationSignal(), activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    dispatchDecrypted(activity, remoteId, callback);
                }

                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                            && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                        Toast.makeText(activity, errString, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Exception error) {
            Toast.makeText(activity, L10n.tr("设备不支持生物识别或未录入指纹"), Toast.LENGTH_SHORT).show();
        }
    }

    private void dispatchDecrypted(Activity activity, String remoteId, UnlockCallback callback) {
        try {
            String password = secretStore.get(scope(remoteId));
            if (password.isEmpty()) {
                showPasswordSetupDialog(activity, remoteId, null);
                return;
            }
            callback.onUnlocked(password);
        } catch (Exception error) {
            Toast.makeText(activity, L10n.tr("读取锁屏密码失败，请长按解锁按钮重新设置"), Toast.LENGTH_SHORT).show();
        }
    }

    void showPasswordSetupDialog(Activity activity, String remoteId, Runnable onSaved) {
        if (remoteId == null || remoteId.isEmpty()) return;
        int pad = Math.round(18 * activity.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView tip = new TextView(activity);
        tip.setText(L10n.tr("密码将通过本机 Android Keystore 加密保存，仅在指纹验证通过后发送给当前电脑用于解锁。"));
        tip.setTextSize(12);
        tip.setTextColor(MobileUi.SECONDARY);
        box.addView(tip);

        EditText input = new EditText(activity);
        input.setHint(L10n.tr("输入电脑 Windows 锁屏密码 / PIN"));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSingleLine(true);
        box.addView(input);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(L10n.tr("设置一键解锁密码"))
                .setView(box)
                .setPositiveButton(L10n.tr("保存"), (dialog, which) -> {
                    String value = input.getText().toString();
                    if (value.isEmpty()) {
                        Toast.makeText(activity, L10n.tr("密码不能为空"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        secretStore.put(scope(remoteId), value);
                        Toast.makeText(activity, L10n.tr("已加密保存，下次点击“解锁”即可指纹秒开"), Toast.LENGTH_SHORT).show();
                        if (onSaved != null) onSaved.run();
                    } catch (Exception error) {
                        Toast.makeText(activity, L10n.tr("加密保存失败"), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(L10n.tr("取消"), null);

        if (hasPassword(remoteId)) {
            builder.setNeutralButton(L10n.tr("清除密码"), (dialog, which) -> {
                secretStore.remove(scope(remoteId));
                Toast.makeText(activity, L10n.tr("已清除保存的解锁密码"), Toast.LENGTH_SHORT).show();
            });
        }
        builder.show();
    }
}
