package com.youzeng;

import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.os.Process;

import com.youzeng.hook.YouzengConfig;

/**
 * 以 root 跑 {@code app_process}，直接改 LSPosed 的 modules_config.db。
 * 本机没有 {@code /data/adb/lspd/bin/cli}，CLI 脚本全部是空操作。
 */
public final class LsposedDbPatch {
    private static final String DB = "/data/adb/lspd/config/modules_config.db";
    private static final String MODULE = YouzengConfig.MODULE_PACKAGE;

    /** Java serialization of Boolean.TRUE */
    private static final byte[] JAVA_TRUE = hex(
            "aced0005737200116a6176612e6c616e672e426f6f6c65616ecd207280d59cfaee0200015a000576616c7565787001");
    /** Java serialization of Integer(-2) = CLI session timeout Disabled */
    private static final byte[] JAVA_INT_DISABLED = hex(
            "aced0005737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870fffffffe");

    private LsposedDbPatch() {
    }

    public static void main(String[] args) {
        System.out.println("LsposedDbPatch uid=" + Process.myUid());
        Looper.prepareMainLooper();
        SQLiteDatabase db = SQLiteDatabase.openDatabase(DB, null, SQLiteDatabase.OPEN_READWRITE);
        try {
            // 不要 automatic_add=1：那会把所有新装 App 都勾进作用域。
            db.execSQL("UPDATE modules SET automatic_add=0 WHERE module_pkg_name=?",
                    new String[]{MODULE});
            db.execSQL(
                    "DELETE FROM scope WHERE app_pkg_name=? AND mid IN "
                            + "(SELECT mid FROM modules WHERE module_pkg_name=?)",
                    new String[]{"system", MODULE});
            for (String pkg : YouzengConfig.autoAddScopePackages()) {
                insertScope(db, pkg);
            }
            putConfig(db, "enable_cli", JAVA_TRUE);
            putConfig(db, "cli_session_timeout", JAVA_INT_DISABLED);
            System.out.println("LsposedDbPatch ok");
        } finally {
            db.close();
        }
    }

    private static void insertScope(SQLiteDatabase db, String pkg) {
        db.execSQL(
                "INSERT OR REPLACE INTO scope(mid, app_pkg_name, user_id) "
                        + "SELECT mid, ?, 0 FROM modules WHERE module_pkg_name=?",
                new String[]{pkg, MODULE});
    }

    private static void putConfig(SQLiteDatabase db, String key, byte[] data) {
        // "group" 是 SQL 保留字，必须用反引号引起来，不能用 ContentValues
        db.execSQL(
                "INSERT OR REPLACE INTO configs(user_id, key, data, `group`, module_pkg_name)"
                        + " VALUES (0, ?, ?, 'config', 'lspd')",
                new Object[]{key, data});
    }

    private static byte[] hex(String s) {
        int n = s.length();
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(s.substring(i, i + 2), 16);
        }
        return out;
    }
}
