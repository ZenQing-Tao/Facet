package app.facet.kit;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** 通过 GitHub Releases 检查、下载并安装新版本。 */
final class Updater {
    static final String REPO = "giuiu9527/Facet";
    static final String ACTION_INSTALL_RESULT = "app.facet.kit.INSTALL_RESULT";

    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    static final class Release {
        String tag;
        String notes;
        String apkUrl;
        String apkName;
        String sha256;   // 小写十六进制，GitHub 没给就是 null
        long size;
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    private Updater() {}

    static Release fetchLatest() throws Exception {
        HttpURLConnection c = open(API);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new Exception("还没有发布任何版本");
            if (code != 200) throw new Exception("GitHub 返回 " + code);
            JSONObject o = new JSONObject(readAll(c.getInputStream()));
            Release r = new Release();
            r.tag = o.optString("tag_name");
            r.notes = o.optString("body", "").trim();
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.optString("name");
                    if (name.toLowerCase().endsWith(".apk")) {
                        r.apkName = name;
                        r.apkUrl = a.optString("browser_download_url");
                        r.size = a.optLong("size", -1);
                        String d = a.optString("digest", "");
                        if (d.startsWith("sha256:")) r.sha256 = d.substring(7).toLowerCase();
                        break;
                    }
                }
            }
            if (r.tag.isEmpty() || r.apkUrl == null || r.apkUrl.isEmpty()) {
                throw new Exception("最新版本里没有找到安装包");
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** 版本号比较：v1.2.10 > v1.2.9；只看数字段。 */
    static boolean isNewer(String remote, String local) {
        int[] a = parse(remote), b = parse(local);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parse(String v) {
        String[] parts = v.replaceFirst("^[vV]", "").split("[^0-9]+");
        int[] out = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            if (!p.isEmpty()) out[n++] = Integer.parseInt(p.length() > 9 ? p.substring(0, 9) : p);
        }
        return java.util.Arrays.copyOf(out, n);
    }

    static File download(Context ctx, Release r, Progress cb) throws Exception {
        File out = new File(ctx.getCacheDir(), "update.apk");
        HttpURLConnection c = open(r.apkUrl);
        try {
            if (c.getResponseCode() != 200) throw new Exception("下载失败：HTTP " + c.getResponseCode());
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : r.size;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    md.update(buf, 0, n);
                    done += n;
                    if (cb != null) cb.onProgress(done, total);
                }
                if (total > 0 && done != total) throw new Exception("下载不完整");
            }
            if (r.sha256 != null && !hex(md.digest()).equals(r.sha256)) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                throw new Exception("安装包校验失败，已丢弃");
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

    /** 交给系统安装器；结果通过 ACTION_INSTALL_RESULT 广播回来。 */
    static void install(Context ctx, File apk) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("update.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(ACTION_INSTALL_RESULT).setPackage(ctx.getPackageName());
            PendingIntent pend = PendingIntent.getBroadcast(ctx, id, i,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pend.getIntentSender());
        } catch (Exception e) {
            pi.abandonSession(id);
            throw e;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Facet-Updater");
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
