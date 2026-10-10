package com.youzeng;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import com.youzeng.hook.YouzengConfig;

/**
 * 供<strong>其它应用进程</strong>（邮储 / 建行等目标 App）通过 ContentResolver 读取本模块写入的 SharedPreferences。
 * 参考 {@code D:\\MyProjects\\JinRong\\XposedModule\\ConfigProvider}。
 * <p>
 * MainActivity 使用 {@link YouzengConfig#MODULE_PREFS_NAME} 写入；此处同步读取同一文件。
 */
public class YouzengConfigProvider extends ContentProvider {

    public static final String AUTHORITY = YouzengConfig.CONFIG_PROVIDER_AUTHORITY;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"key", "value"});
        if (selectionArgs != null && selectionArgs.length > 0 && getContext() != null) {
            String key = selectionArgs[0];
            SharedPreferences prefs = YouzengModulePrefsStore.openExportable(getContext());
            String value = prefs.getString(key, "");
            cursor.addRow(new Object[]{key, value != null ? value : ""});
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
