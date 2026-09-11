package com.csy.cmbspend;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 把台账备份到**卸载不会删除**的公共目录 `内部存储/当月消费/`，用于重装后自动恢复。
 *
 *  读写策略（按优先级）：
 *    1. 已授予「所有文件访问权限」(MANAGE_EXTERNAL_STORAGE) → **直接按路径读写**
 *       `/storage/emulated/0/当月消费/`。这是首选：路径固定、卸载不删，且重装后只要重新授权，
 *       App 就能直接读回备份、自动恢复，不需要用户手挑文件。
 *    2. 未授权（API 29+）→ 回退用 MediaStore 写 Download/当月消费/。能写能存，
 *       但重装后因 uid 变化无法自动读回，需用户用「从备份导入」手动选文件。
 *    3. API 26–28 → 直接写公共目录（需 WRITE_EXTERNAL_STORAGE，manifest 已限 maxSdkVersion=28）。
 *
 *  为什么不用 App 私有目录或 Android/data：
 *    实测（2026-09-11）卸载会同时删除 /data/data/<pkg> 与 /sdcard/Android/data/<pkg>。 */
final class PublicBackup {

    /** 自建目录名，位于内部存储根目录下 */
    private static final String DIR_NAME = "当月消费";
    private static final String FILE_NAME = "台账备份.json";
    private static final String CSV_NAME = "消费明细导出.csv";

    private PublicBackup() {}

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    /** 是否已获得「所有文件访问权限」（API 30+；以下版本用旧存储权限，直接算作可用）。 */
    static boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /** 自建目录的绝对路径（供 UI 展示）。 */
    static String dirPath() {
        return new File(Environment.getExternalStorageDirectory(), DIR_NAME).getAbsolutePath();
    }

    /** 确保自建目录存在，返回它；无权限或失败返回 null。 */
    private static File ensureDir() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) {
                // mkdirs 可能因已存在而失败，再确认一次
                if (!dir.isDirectory()) return null;
            }
            return dir.isDirectory() ? dir : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /** 写备份 JSON；成功返回 true。 */
    static boolean write(Context ctx, String json) {
        return writeFile(ctx, FILE_NAME, "application/json", json);
    }

    /** 写导出 CSV。 */
    static boolean writeExport(Context ctx, String content) {
        return writeFile(ctx, CSV_NAME, "text/csv", content);
    }

    private static boolean writeFile(Context ctx, String name, String mime, String content) {
        if (content == null) return false;
        // 1. 有所有文件权限 → 直接按路径写自建目录
        if (hasAllFilesAccess()) {
            File dir = ensureDir();
            if (dir != null) {
                try (FileOutputStream fos = new FileOutputStream(new File(dir, name))) {
                    fos.write(content.getBytes(StandardCharsets.UTF_8));
                    return true;
                } catch (Exception ignored) {
                    // 落到 MediaStore 兜底
                }
            }
        }
        // 2. 回退：MediaStore（API 29+）
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return writeViaMediaStore(ctx, name, mime, content);
            }
            return writeViaLegacyPublic(name, content);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 读备份 JSON；优先从自建目录按路径读，其次 MediaStore 兜底。读不到返回空串。 */
    static String read(Context ctx) {
        // 1. 自建目录直接读（重装 + 重新授权后即可自动恢复）
        if (hasAllFilesAccess()) {
            try {
                File f = new File(ensureDirSafe(), FILE_NAME);
                if (f.isFile()) {
                    byte[] b = Files.readAllBytes(f.toPath());
                    return new String(b, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
            }
        }
        // 2. MediaStore 兜底
        if (Build.VERSION.SDK_INT >= 29) {
            String s = readViaMediaStore(ctx);
            if (s != null && !s.isEmpty()) return s;
        }
        // 3. 旧版公共目录兜底
        return readViaLegacyPublic();
    }

    private static File ensureDirSafe() {
        File dir = new File(Environment.getExternalStorageDirectory(), DIR_NAME);
        return dir;
    }

    // ------------------------------------------------------------------
    // MediaStore 实现（API 29+，无权限也可以用）
    // ------------------------------------------------------------------

    private static boolean writeViaMediaStore(Context ctx, String name, String mime, String content)
            throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String relPath = Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "/";

        Uri target = null;
        String[] proj = {MediaStore.Downloads._ID};
        String sel = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                + MediaStore.Downloads.RELATIVE_PATH + "=?";
        try (Cursor c = cr.query(collection, proj, sel, new String[]{name, relPath}, null)) {
            if (c != null && c.moveToFirst()) {
                target = ContentUris.withAppendedId(collection, c.getLong(0));
            }
        }
        if (target == null) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, relPath);
            target = cr.insert(collection, cv);
        }
        if (target == null) return false;
        try (OutputStream os = cr.openOutputStream(target, "wt")) {
            if (os == null) return false;
            os.write(content.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        return true;
    }

    private static String readViaMediaStore(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String relPath = Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "/";
        String[] proj = {MediaStore.Downloads._ID};
        String sel = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                + MediaStore.Downloads.RELATIVE_PATH + "=?";
        try (Cursor c = cr.query(collection, proj, sel, new String[]{FILE_NAME, relPath}, null)) {
            if (c == null || !c.moveToFirst()) return "";
            Uri uri = ContentUris.withAppendedId(collection, c.getLong(0));
            try (InputStream is = cr.openInputStream(uri)) {
                if (is == null) return "";
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // 旧版公共目录（API 26-28）
    // ------------------------------------------------------------------

    private static boolean writeViaLegacyPublic(String name, String content) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) return false;
        try (FileOutputStream fos = new FileOutputStream(new File(dir, name))) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return true;
    }

    private static String readViaLegacyPublic() {
        try {
            File f = new File(new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), DIR_NAME), FILE_NAME);
            if (!f.exists()) return "";
            byte[] b = Files.readAllBytes(f.toPath());
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
