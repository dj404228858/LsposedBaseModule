package com.youzeng;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机、本模块重装、目标 App 安装/替换时，用 LSPosed_mod CLI 重新勾选推荐作用域。
 */
public class LsposedScopeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            LsposedScopeCli.applyRecommendedScopeAsync(context);
            return;
        }
        if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                || Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;
            LsposedScopeCli.appendWatchedPackageAsync(context, pkg);
        }
    }
}
