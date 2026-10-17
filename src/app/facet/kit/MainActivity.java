package app.facet.kit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int ICON_PX = 256;

    private SharedPreferences sp;
    private boolean prefsShared;
    private final List<AppItem> all = new ArrayList<>();
    private final List<AppItem> shown = new ArrayList<>();
    private AppAdapter adapter;
    private TextView status;
    private String query = "";

    // 编辑对话框状态
    private String editPkg;
    private AlertDialog progressDialog;
    private SharedPreferences state;
    private ImageView editPreview;
    private String editIcon;      // 新选的图标（base64），null 表示未改动
    private boolean editIconCleared;

    /** 模块被 LSPosed 加载后会被 Hook 成返回 true。 */
    public static boolean isModuleActive() {
        return false;
    }

    static final class AppItem {
        String pkg;
        String label;
        Drawable icon;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            sp = getSharedPreferences(Const.PREFS, Context.MODE_WORLD_READABLE);
            prefsShared = true;
        } catch (SecurityException e) {
            sp = getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE);
            prefsShared = false;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, 0);

        status = new TextView(this);
        status.setTextSize(13);
        root.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("重启桌面", v -> restart("com.miui.home")), weight());
        buttons.addView(button("重启系统界面", v -> restart("com.android.systemui")), weight());
        buttons.addView(button("检查更新", v -> checkUpdate(true)), weight());
        root.addView(buttons);

        EditText search = new EditText(this);
        search.setHint("搜索应用名或包名");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                filter();
            }
        });
        root.addView(search);

        ListView list = new ListView(this);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> showEditor(shown.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        state = getSharedPreferences("state", Context.MODE_PRIVATE);
        //noinspection ResultOfMethodCallIgnored
        new java.io.File(getCacheDir(), "update.apk").delete();   // 上次更新留下的安装包
        IntentFilter f = new IntentFilter(Updater.ACTION_INSTALL_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(installReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(installReceiver, f);
        }
        updateStatus();
        loadApps();
        // 每 12 小时静默检查一次，只有发现新版本才会打扰
        if (System.currentTimeMillis() - state.getLong("last_check", 0) > 12L * 3600 * 1000) {
            checkUpdate(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(installReceiver);
        if (progressDialog != null) progressDialog.dismiss();
    }

    // ---------------------------------------------------------------- 更新

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void checkUpdate(boolean manual) {
        if (manual) toast("正在检查更新…");
        new Thread(() -> {
            try {
                Updater.Release r = Updater.fetchLatest();
                state.edit().putLong("last_check", System.currentTimeMillis()).apply();
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (Updater.isNewer(r.tag, versionName())) {
                        showUpdateDialog(r);
                    } else if (manual) {
                        toast("已是最新版本 v" + versionName());
                    }
                });
            } catch (Exception e) {
                if (manual) runOnUiThread(() -> toast("检查更新失败：" + e.getMessage()
                        + "\n（GitHub 需要能访问外网）"));
            }
        }).start();
    }

    private void showUpdateDialog(Updater.Release r) {
        String notes = r.notes.length() > 800 ? r.notes.substring(0, 800) + "…" : r.notes;
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + r.tag)
                .setMessage("当前 v" + versionName() + (notes.isEmpty() ? "" : "\n\n" + notes))
                .setPositiveButton("下载并安装", (d, w) -> startDownload(r))
                .setNegativeButton("以后再说", null)
                .show();
    }

    private void startDownload(Updater.Release r) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast("请先允许本应用安装应用，授权后再点一次检查更新");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        TextView msg = new TextView(this);
        msg.setPadding(dp(24), dp(16), dp(24), dp(8));
        msg.setText("准备下载…");
        progressDialog = new AlertDialog.Builder(this).setTitle("正在更新 " + r.tag)
                .setView(msg).setCancelable(false).show();
        new Thread(() -> {
            try {
                java.io.File apk = Updater.download(this, r, (done, total) -> runOnUiThread(() ->
                        msg.setText(total > 0
                                ? String.format(Locale.ROOT, "已下载 %d%%（%.1f / %.1f MB）",
                                done * 100 / total, done / 1048576f, total / 1048576f)
                                : String.format(Locale.ROOT, "已下载 %.1f MB", done / 1048576f))));
                runOnUiThread(() -> msg.setText("正在交给系统安装…"));
                Updater.install(this, apk);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (progressDialog != null) progressDialog.dismiss();
                    toast("更新失败：" + e.getMessage());
                });
            }
        }).start();
    }

    private final BroadcastReceiver installReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @SuppressWarnings("deprecation") Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            if (progressDialog != null) progressDialog.dismiss();
            if (status != PackageInstaller.STATUS_SUCCESS) {
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                toast("安装失败：" + (m == null ? "状态 " + status : m));
            }
        }
    };

    private void updateStatus() {
        int count = 0;
        Set<String> pkgs = new HashSet<>();
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith(Const.NAME)) pkgs.add(k.substring(Const.NAME.length()));
            if (k.startsWith(Const.ICON)) pkgs.add(k.substring(Const.ICON.length()));
        }
        count = pkgs.size();
        String s;
        if (!isModuleActive()) {
            s = "⚠ 模块未激活：请在 LSPosed 里启用本模块并勾选作用域，然后重启手机。";
        } else if (!prefsShared) {
            s = "⚠ 配置无法共享给系统（LSPosed 版本过旧？）";
        } else {
            s = "✓ 模块已激活，已自定义 " + count + " 个应用。修改后点下面按钮让桌面/通知生效。";
        }
        status.setText(s + "\n版本 v" + versionName() + " · GitHub: " + Updater.REPO);
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            Set<String> seen = new HashSet<>();
            List<AppItem> items = new ArrayList<>();
            for (ResolveInfo ri : ris) {
                String pkg = ri.activityInfo.packageName;
                if (Const.SELF.equals(pkg) || !seen.add(pkg)) continue;
                AppItem it = new AppItem();
                it.pkg = pkg;
                it.label = String.valueOf(ri.activityInfo.applicationInfo.loadLabel(pm));
                it.icon = ri.activityInfo.applicationInfo.loadIcon(pm);
                items.add(it);
            }
            runOnUiThread(() -> {
                all.clear();
                all.addAll(items);
                filter();
            });
        }).start();
    }

    private boolean isCustomized(String pkg) {
        return sp.contains(Const.NAME + pkg) || sp.contains(Const.ICON + pkg);
    }

    private void filter() {
        shown.clear();
        for (AppItem it : all) {
            if (query.isEmpty() || it.pkg.toLowerCase(Locale.ROOT).contains(query)
                    || it.label.toLowerCase(Locale.ROOT).contains(query)
                    || sp.getString(Const.NAME + it.pkg, "").toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(it);
            }
        }
        Collections.sort(shown, (a, b) -> {
            boolean ca = isCustomized(a.pkg), cb = isCustomized(b.pkg);
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- 编辑

    private void showEditor(AppItem item) {
        editPkg = item.pkg;
        editIcon = null;
        editIconCleared = false;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        TextView pkgView = new TextView(this);
        pkgView.setText("原名：" + item.label + "\n包名：" + item.pkg);
        pkgView.setTextSize(12);
        box.addView(pkgView);

        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("新名称（留空 = 用原名）");
        name.setText(sp.getString(Const.NAME + item.pkg, ""));
        box.addView(name);

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        iconRow.setPadding(0, dp(12), 0, 0);
        editPreview = new ImageView(this);
        String cur = sp.getString(Const.ICON + item.pkg, null);
        Bitmap curBmp = decode(cur);
        if (curBmp != null) editPreview.setImageBitmap(curBmp); else editPreview.setImageDrawable(item.icon);
        iconRow.addView(editPreview, new LinearLayout.LayoutParams(dp(64), dp(64)));
        Button pick = button("选择图片", v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "选择图标图片"), REQ_PICK);
        });
        Button reset = button("恢复原图标", v -> {
            editIcon = null;
            editIconCleared = true;
            editPreview.setImageDrawable(item.icon);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(12);
        iconRow.addView(pick, lp);
        iconRow.addView(reset, new LinearLayout.LayoutParams(-2, -2));
        box.addView(iconRow);

        CheckBox shape = new CheckBox(this);
        shape.setText("套用当前主题的图标形状");
        shape.setChecked(sp.getBoolean(Const.SHAPE + item.pkg, true));
        box.addView(shape);

        new AlertDialog.Builder(this)
                .setTitle("自定义 " + item.label)
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    SharedPreferences.Editor e = sp.edit();
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) e.remove(Const.NAME + item.pkg); else e.putString(Const.NAME + item.pkg, n);
                    if (editIcon != null) e.putString(Const.ICON + item.pkg, editIcon);
                    else if (editIconCleared) e.remove(Const.ICON + item.pkg);
                    e.putBoolean(Const.SHAPE + item.pkg, shape.isChecked());
                    e.commit();
                    toast("已保存。点「重启桌面」「重启系统界面」生效");
                    updateStatus();
                    filter();
                })
                .setNeutralButton("全部恢复", (d, w) -> {
                    sp.edit().remove(Const.NAME + item.pkg).remove(Const.ICON + item.pkg)
                            .remove(Const.SHAPE + item.pkg).commit();
                    toast("已恢复默认");
                    updateStatus();
                    filter();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Bitmap b = loadSquare(data.getData());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            editIcon = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            editIconCleared = false;
            if (editPreview != null) editPreview.setImageBitmap(b);
        } catch (Exception e) {
            toast("读取图片失败：" + e.getMessage());
        }
    }

    private Bitmap loadSquare(Uri uri) throws Exception {
        ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
        Bitmap b = ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int min = Math.min(info.getSize().getWidth(), info.getSize().getHeight());
            int sample = 1;
            while (min / (sample * 2) >= ICON_PX) sample *= 2;
            decoder.setTargetSampleSize(sample);
        });
        int size = Math.min(b.getWidth(), b.getHeight());
        Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - size) / 2, (b.getHeight() - size) / 2, size, size);
        return Bitmap.createScaledBitmap(sq, ICON_PX, ICON_PX, true);
    }

    private static Bitmap decode(String b64) {
        if (b64 == null) return null;
        try {
            byte[] d = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(d, 0, d.length);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 重启

    private void restart(String pkg) {
        new Thread(() -> {
            String msg;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "killall " + pkg});
                String err = new String(readFully(p.getErrorStream())).trim();
                int code = p.waitFor();
                if (code == 0) {
                    msg = "已重启 " + pkg;
                } else if (err.contains("no process")) {
                    msg = pkg + " 当前没有在运行，无需重启";
                } else {
                    msg = "重启失败（退出码 " + code + "）" + (err.isEmpty() ? "" : "：" + err);
                }
            } catch (java.io.IOException e) {
                // su 根本启动不了：没授权，或者设备没有 Root
                msg = "无法获取 Root（" + e.getMessage() + "）\n"
                        + "请在 KernelSU / Magisk 的超级用户列表里给「" + getString(R.string.app_name) + "」授权后再试";
            } catch (Exception e) {
                msg = "重启失败：" + e;
            }
            String m = msg;
            runOnUiThread(() -> toast(m));
        }).start();
    }

    private static byte[] readFully(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    // ---------------------------------------------------------------- UI 工具

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            if (convert == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(8), 0, dp(8));
                ImageView iv = new ImageView(MainActivity.this);
                row.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));
                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dp(12), 0, 0, 0);
                TextView t1 = new TextView(MainActivity.this);
                t1.setTextSize(16);
                TextView t2 = new TextView(MainActivity.this);
                t2.setTextSize(12);
                t2.setAlpha(0.7f);
                texts.addView(t1);
                texts.addView(t2);
                row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            } else {
                row = (LinearLayout) convert;
            }
            AppItem it = shown.get(pos);
            ImageView iv = (ImageView) row.getChildAt(0);
            LinearLayout texts = (LinearLayout) row.getChildAt(1);
            TextView t1 = (TextView) texts.getChildAt(0);
            TextView t2 = (TextView) texts.getChildAt(1);
            Bitmap custom = decode(sp.getString(Const.ICON + it.pkg, null));
            if (custom != null) iv.setImageBitmap(custom); else iv.setImageDrawable(it.icon);
            String alias = sp.getString(Const.NAME + it.pkg, null);
            t1.setText(alias != null ? alias + "  ←  " + it.label : it.label);
            t2.setText(it.pkg + (custom != null ? " · 已换图标" : ""));
            return row;
        }
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        return b;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
