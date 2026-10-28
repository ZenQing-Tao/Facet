package app.facet.kit;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 澎湃 OS 4 的原生桌面 LSPosed 进不去，图标只能写进主题图标包 /data/system/theme/icons（一个 zip）。
 * 换主题会重新生成这个包，所以 system_server 里的 Hook 发现它变了就通知 {@link ThemeReceiver} 再写一次。
 * 包里放一个标记条目记录写入的是哪一版配置，标记一致就跳过，避免「写入 → 文件变化 → 再写入」的循环。
 */
final class ThemeIcons {
    static final String THEME_DIR = "/data/system/theme";
    static final String THEME_ICONS = THEME_DIR + "/icons";
    static final String ACTION_CHANGED = "app.facet.kit.THEME_ICONS_CHANGED";
    private static final String ICON_DIR = "res/drawable-xxhdpi/";
    private static final String STAMP = "facet/stamp";

    private ThemeIcons() {}

    /** force=false 时（主题变化自动触发）包里已经是这版配置就什么都不做。返回给用户看的结果。 */
    static synchronized String sync(Context ctx, boolean force) {
        File src = new File(ctx.getCacheDir(), "theme_icons_src");
        File out = new File(ctx.getCacheDir(), "theme_icons_new");
        try {
            Map<String, Bitmap> custom = customIcons(ctx);
            if (custom.isEmpty()) return "还没有给任何应用设置图标";
            String stamp = stampOf(ctx);
            su("cp " + THEME_ICONS + " " + src.getAbsolutePath() + " && chmod 644 " + src.getAbsolutePath());
            if (!force && stamp.equals(readStamp(src))) return "桌面图标已是最新";
            int written = rewrite(src, out, custom, stamp);
            String ctxLabel = su("ls -Z " + THEME_ICONS + " | awk '{print $1}'").trim();
            su("cp " + out.getAbsolutePath() + " " + THEME_ICONS
                    + " && chown system_theme:system_theme " + THEME_ICONS
                    + " && chmod 755 " + THEME_ICONS
                    + (ctxLabel.startsWith("u:") ? " && chcon " + ctxLabel + " " + THEME_ICONS : "")
                    + " && (killall " + homePackage(ctx) + " || true)");
            return "已写入 " + written + " 个图标并重启桌面";
        } catch (IOException e) {
            return "无法获取 Root（" + e.getMessage() + "）";
        } catch (Exception e) {
            return "写入失败：" + e.getMessage();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    static SharedPreferences prefs(Context ctx) {
        try {
            //noinspection deprecation
            return ctx.getSharedPreferences(Const.PREFS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            return ctx.getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE);
        }
    }

    private static Map<String, Bitmap> customIcons(Context ctx) {
        Map<String, Bitmap> m = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(Const.ICON) || !(e.getValue() instanceof String)) continue;
            String key = k.substring(Const.ICON.length());
            if (key.contains("@")) continue;   // 主题包不分用户，分身用不上
            byte[] d = Base64.decode((String) e.getValue(), Base64.DEFAULT);
            Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length);
            if (b != null) m.put(key, b);
        }
        return m;
    }

    /** 当前图标配置的摘要，写进包里当标记。 */
    private static String stampOf(Context ctx) {
        Map<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            if (e.getKey().startsWith(Const.ICON)) sorted.put(e.getKey(), e.getValue());
        }
        return Integer.toHexString(sorted.hashCode());
    }

    private static String readStamp(File zip) {
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry e = zf.getEntry(STAMP);
            return e == null ? "" : new String(readFully(zf.getInputStream(e)), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 复制主题 zip，替换（或新增）自定义图标那几项，其余条目原样保留（含 STORED/DEFLATED 方式）。 */
    private static int rewrite(File src, File out, Map<String, Bitmap> custom, String stamp) throws Exception {
        int written = 0;
        Set<String> done = new HashSet<>();
        int size = 256;
        try (ZipFile zf = new ZipFile(src);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (STAMP.equals(name)) continue;
                byte[] data = readFully(zf.getInputStream(e));
                if (name.startsWith(ICON_DIR) && name.endsWith(".png")) {
                    String pkg = name.substring(ICON_DIR.length(), name.length() - 4);
                    Bitmap mask = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (mask != null) size = mask.getWidth();
                    if (custom.containsKey(pkg) && mask != null) {
                        data = png(themed(custom.get(pkg), mask));
                        done.add(pkg);
                        written++;
                    }
                }
                put(zos, name, data, e.getMethod());
            }
            for (Map.Entry<String, Bitmap> c : custom.entrySet()) {
                if (done.contains(c.getKey())) continue;
                put(zos, ICON_DIR + c.getKey() + ".png", png(themed(c.getValue(), roundMask(size))), ZipEntry.STORED);
                written++;
            }
            put(zos, STAMP, stamp.getBytes("UTF-8"), ZipEntry.STORED);
        }
        return written;
    }

    private static void put(ZipOutputStream zos, String name, byte[] data, int method) throws IOException {
        ZipEntry ne = new ZipEntry(name);
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(data);
            ne.setMethod(ZipEntry.STORED);
            ne.setSize(data.length);
            ne.setCompressedSize(data.length);
            ne.setCrc(crc.getValue());
        } else {
            ne.setMethod(ZipEntry.DEFLATED);
        }
        zos.putNextEntry(ne);
        zos.write(data);
        zos.closeEntry();
    }

    /** 白色底板 + 自定义图标，再按 mask 的透明度裁成主题形状。 */
    private static Bitmap themed(Bitmap icon, Bitmap mask) {
        int w = mask.getWidth(), h = mask.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        c.drawBitmap(icon, null, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawBitmap(mask, 0, 0, p);
        return out;
    }

    private static Bitmap roundMask(int s) {
        Bitmap m = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(m);
        c.drawRoundRect(new RectF(0, 0, s, s), s * 0.225f, s * 0.225f, new Paint(Paint.ANTI_ALIAS_FLAG));
        return m;
    }

    private static byte[] png(Bitmap b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        return o.toByteArray();
    }

    static String homePackage(Context ctx) {
        ResolveInfo ri = ctx.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
            return ri.activityInfo.packageName;
        }
        return "com.miui.home";
    }

    /** 以 root 执行，失败（退出码非 0）抛异常，返回标准输出。 */
    static String su(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        String outText = new String(readFully(p.getInputStream()));
        String err = new String(readFully(p.getErrorStream())).trim();
        if (p.waitFor() != 0) throw new Exception(err.isEmpty() ? "命令失败：" + cmd : err);
        return outText;
    }

    static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
