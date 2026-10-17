package app.facet.kit;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Base64;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "Facet: ";
    private static final int SYSUI_ICON_PX = 192;

    private static XSharedPreferences prefs;
    private static boolean frameworkHooked;
    private static volatile boolean loaded;
    private static long lastCheck;

    private static volatile Map<String, String> names = Collections.emptyMap();
    private static volatile Map<String, String> icons = Collections.emptyMap();
    private static volatile Map<String, Boolean> shapes = Collections.emptyMap();

    private static final Map<String, Bitmap> rawCache = new ConcurrentHashMap<>();
    private static final Map<String, Bitmap> styledCache = new ConcurrentHashMap<>();
    private static boolean launcherHooked;
    private static boolean systemUIHooked;
    private static final Map<String, String> origLabels = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> bypass = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (Const.SELF.equals(lp.packageName)) {
            XposedHelpers.findAndHookMethod(MainActivity.class.getName(), lp.classLoader,
                    "isModuleActive", XC_MethodReplacementTrue.INSTANCE);
            return;
        }
        synchronized (Hook.class) {
            if (!frameworkHooked) {
                frameworkHooked = true;
                prefs = new XSharedPreferences(Const.SELF, Const.PREFS);
                hookFramework();
                XposedBridge.log(TAG + "framework hooks installed in " + lp.processName);
            }
        }
        // 同一进程可能多次回调（插件等其它 ClassLoader），只在能找到目标类的那次挂钩
        if ("com.miui.home".equals(lp.packageName) && !launcherHooked
                && XposedHelpers.findClassIfExists("com.miui.home.icon.IconProvider", lp.classLoader) != null) {
            launcherHooked = true;
            hookLauncher(lp.classLoader);
        } else if ("com.android.systemui".equals(lp.packageName) && !systemUIHooked
                && XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.ExpandedNotification", lp.classLoader) != null) {
            systemUIHooked = true;
            hookSystemUI(lp.classLoader);
        }
    }

    // ---------------------------------------------------------------- 配置

    private static void refresh() {
        long now = SystemClock.uptimeMillis();
        if (loaded && now - lastCheck < 2000) return;
        lastCheck = now;
        try {
            if (!loaded || prefs.hasFileChanged()) {
                prefs.reload();
                parse(prefs.getAll());
                loaded = true;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "load prefs failed: " + t);
            loaded = true;
        }
    }

    private static void parse(Map<String, ?> all) {
        Map<String, String> n = new HashMap<>();
        Map<String, String> i = new HashMap<>();
        Map<String, Boolean> s = new HashMap<>();
        if (all != null) {
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                if (k.startsWith(Const.NAME) && v instanceof String && !((String) v).isEmpty()) {
                    n.put(k.substring(Const.NAME.length()), (String) v);
                } else if (k.startsWith(Const.ICON) && v instanceof String && !((String) v).isEmpty()) {
                    i.put(k.substring(Const.ICON.length()), (String) v);
                } else if (k.startsWith(Const.SHAPE) && v instanceof Boolean) {
                    s.put(k.substring(Const.SHAPE.length()), (Boolean) v);
                }
            }
        }
        names = n;
        icons = i;
        shapes = s;
        rawCache.clear();
        styledCache.clear();
    }

    private static String nameFor(String pkg) {
        if (pkg == null) return null;
        refresh();
        return names.get(pkg);
    }

    private static boolean hasIcon(String pkg) {
        if (pkg == null) return false;
        refresh();
        return icons.containsKey(pkg);
    }

    private static Bitmap rawBitmap(String pkg) {
        String b64 = icons.get(pkg);
        if (b64 == null) return null;
        Bitmap b = rawCache.get(pkg);
        if (b == null) {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b == null) return null;
            rawCache.put(pkg, b);
        }
        return b;
    }

    /**
     * 最终显示用的位图。开启「套用主题形状」时交给澎湃主题引擎处理（遮罩/底板/描边与当前主题一致），
     * 主题引擎不可用时退回到圆角矩形。
     */
    private static Bitmap styledBitmap(String pkg) {
        if (!hasIcon(pkg)) return null;
        Bitmap b = styledCache.get(pkg);
        if (b != null) return b;
        Bitmap raw = rawBitmap(pkg);
        if (raw == null) return null;
        Boolean shape = shapes.get(pkg);
        if (shape != null && !shape) {
            b = raw;
        } else {
            b = themeStyled(raw);
            if (b == null) b = roundRect(raw);
        }
        styledCache.put(pkg, b);
        return b;
    }

    private static Bitmap themeStyled(Bitmap raw) {
        try {
            Class<?> ic = Class.forName("miui.content.res.IconCustomizer");
            Method m = ic.getMethod("generateIconStyleDrawable", Drawable.class);
            Object r = m.invoke(null, new BitmapDrawable(Resources.getSystem(), raw));
            if (r instanceof BitmapDrawable) return ((BitmapDrawable) r).getBitmap();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "IconCustomizer unavailable: " + t);
        }
        return null;
    }

    private static Bitmap roundRect(Bitmap raw) {
        int s = SYSUI_ICON_PX;
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Path p = new Path();
        p.addRoundRect(new RectF(0, 0, s, s), s * 0.225f, s * 0.225f, Path.Direction.CW);
        c.clipPath(p);
        c.drawBitmap(raw, null, new Rect(0, 0, s, s), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static Drawable iconFor(String pkg, Resources res) {
        Bitmap b = styledBitmap(pkg);
        if (b == null) return null;
        return new BitmapDrawable(res != null ? res : Resources.getSystem(), b);
    }

    /** SystemUI 的图标缓存要求是 Bitmap。 */
    private static Bitmap sysuiBitmap(String pkg) {
        return styledBitmap(pkg);
    }

    // ---------------------------------------------------------------- 通用（每个作用域进程）

    private static void hookFramework() {
        Class<?> apm = XposedHelpers.findClass("android.app.ApplicationPackageManager", null);

        safeHookAll(apm, "getApplicationLabel", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (bypass.get() != null) return;
                Object ai = param.args.length > 0 ? param.args[0] : null;
                if (ai instanceof ApplicationInfo) {
                    String n = nameFor(((ApplicationInfo) ai).packageName);
                    if (n != null) param.setResult(n);
                }
            }
        });

        XC_MethodHook labelHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (bypass.get() != null || param.hasThrowable()) return;
                if (!(param.thisObject instanceof PackageItemInfo)) return;
                PackageItemInfo pi = (PackageItemInfo) param.thisObject;
                String n = nameFor(pi.packageName);
                if (n == null) return;
                if (pi instanceof ApplicationInfo) {
                    param.setResult(n);
                } else if (pi instanceof ComponentInfo) {
                    ComponentInfo ci = (ComponentInfo) pi;
                    ApplicationInfo ai = ci.applicationInfo;
                    boolean sameAsApp = ci.nonLocalizedLabel == null
                            && (ci.labelRes == 0 || (ai != null && ci.labelRes == ai.labelRes));
                    if (!sameAsApp && ai != null && param.args.length > 0
                            && param.args[0] instanceof PackageManager && param.getResult() != null) {
                        String orig = origLabel(ai, (PackageManager) param.args[0]);
                        sameAsApp = orig != null && orig.contentEquals(param.getResult().toString());
                    }
                    if (sameAsApp) param.setResult(n);
                }
            }
        };
        for (String m : new String[]{"loadLabel", "loadUnsafeLabel", "loadSafeLabel"}) {
            safeHookAll(PackageItemInfo.class, m, labelHook);
            safeHookAll(ComponentInfo.class, m, labelHook);
        }

        XC_MethodHook itemIconHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length < 2 || !(param.args[0] instanceof PackageItemInfo)) return;
                PackageItemInfo item = (PackageItemInfo) param.args[0];
                ApplicationInfo ai = param.args[1] instanceof ApplicationInfo ? (ApplicationInfo) param.args[1] : null;
                if (!hasIcon(item.packageName)) return;
                boolean sameAsApp = item instanceof ApplicationInfo || item.icon == 0
                        || (ai != null && item.icon == ai.icon);
                if (!sameAsApp) return;
                Drawable d = iconFor(item.packageName, null);
                if (d == null) return;
                if ("loadItemIcon".equals(param.method.getName()) && ai != null) {
                    d = ((PackageManager) param.thisObject).getUserBadgedIcon(d, UserHandle.getUserHandleForUid(ai.uid));
                }
                param.setResult(d);
            }
        };
        safeHookAll(apm, "loadItemIcon", itemIconHook);
        safeHookAll(apm, "loadUnbadgedItemIcon", itemIconHook);

        safeHookAll(LauncherActivityInfo.class, "getIcon", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                ComponentName cn = ((LauncherActivityInfo) param.thisObject).getComponentName();
                Drawable d = cn == null ? null : iconFor(cn.getPackageName(), null);
                if (d != null) param.setResult(d);
            }
        });
    }

    private static String origLabel(ApplicationInfo ai, PackageManager pm) {
        String cached = origLabels.get(ai.packageName);
        if (cached != null) return cached;
        bypass.set(Boolean.TRUE);
        try {
            CharSequence l = ai.loadLabel(pm);
            String s = l == null ? "" : l.toString();
            origLabels.put(ai.packageName, s);
            return s;
        } catch (Throwable t) {
            return null;
        } finally {
            bypass.remove();
        }
    }

    // ---------------------------------------------------------------- 系统桌面 + 最近任务

    private static void hookLauncher(ClassLoader cl) {
        Class<?> iconProvider = XposedHelpers.findClassIfExists("com.miui.home.icon.IconProvider", cl);
        safeHookAll(iconProvider, "getActivityIcon", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof LauncherActivityInfo)) return;
                ComponentName cn = ((LauncherActivityInfo) param.args[0]).getComponentName();
                Drawable d = cn == null ? null : iconFor(cn.getPackageName(), null);
                if (d != null) param.setResult(d);
            }
        });

        Class<?> iconLoader = XposedHelpers.findClassIfExists(
                "com.android.systemui.shared.recents.model.IconLoader", cl);
        XC_MethodHook taskIconHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Drawable d = iconFor(taskPackage(param.args[0]), null);
                if (d != null) param.setResult(d);
            }
        };
        safeHookAll(iconLoader, "getAndInvalidateIfModified", taskIconHook);
        safeHookAll(iconLoader, "getIcon", taskIconHook);
        safeHookAll(iconLoader, "getBadgedActivityIcon", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof ComponentInfo)) return;
                Drawable d = iconFor(((ComponentInfo) param.args[0]).packageName, null);
                if (d != null) param.setResult(d);
            }
        });

        Class<?> taskLoader = XposedHelpers.findClassIfExists(
                "com.android.systemui.shared.recents.model.RecentsTaskLoader", cl);
        safeHookAll(taskLoader, "getAndUpdateActivityTitle", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String n = nameFor(taskPackage(param.args[0]));
                if (n != null) param.setResult(n);
            }
        });

        Class<?> recentsModel = XposedHelpers.findClassIfExists("com.miui.home.recents.RecentsModel", cl);
        safeHookAll(recentsModel, "onTaskDescriptionChanged", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof ActivityManager.RunningTaskInfo)) return;
                ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) param.args[0];
                ComponentName cn = info.baseActivity != null ? info.baseActivity : info.topActivity;
                String n = cn == null ? null : nameFor(cn.getPackageName());
                if (n == null) return;
                try {
                    Object task = XposedHelpers.callMethod(param.thisObject, "findTaskById", info.taskId);
                    if (task != null) XposedHelpers.setObjectField(task, "title", n);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static String taskPackage(Object taskKey) {
        if (taskKey == null) return null;
        try {
            return (String) XposedHelpers.callMethod(taskKey, "getPackageName");
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 系统界面（通知）

    private static void hookSystemUI(ClassLoader cl) {
        Class<?> expanded = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.ExpandedNotification", cl);
        safeHookAll(expanded, "getAppName", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String pkg = (String) XposedHelpers.getObjectField(param.thisObject, "mPkgName");
                if (pkg == null) pkg = (String) XposedHelpers.callMethod(param.thisObject, "getPackageName");
                String n = nameFor(pkg);
                if (n != null) param.setResult(n);
            }
        });

        Class<?> appIcons = XposedHelpers.findClassIfExists("com.miui.systemui.graphics.AppIconsManager", cl);
        XC_MethodHook drawableHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Bitmap b = sysuiBitmap((String) param.args[0]);
                if (b != null) param.setResult(new BitmapDrawable(Resources.getSystem(), b));
            }
        };
        safeHookAll(appIcons, "getAppIconInner", drawableHook);
        safeHookAll(appIcons, "loadAppIcon", drawableHook);
        safeHookAll(appIcons, "getAppIconBitmap", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Bitmap b = sysuiBitmap((String) param.args[1]);
                if (b != null) param.setResult(b);
            }
        });
    }

    // ---------------------------------------------------------------- 工具

    private static void safeHookAll(Class<?> cls, String method, XC_MethodHook hook) {
        if (cls == null) {
            XposedBridge.log(TAG + "class missing for " + method);
            return;
        }
        try {
            int n = XposedBridge.hookAllMethods(cls, method, hook).size();
            if (n == 0) XposedBridge.log(TAG + "no method " + cls.getName() + "#" + method);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook " + cls.getName() + "#" + method + " failed: " + t);
        }
    }

    private static final class XC_MethodReplacementTrue extends XC_MethodHook {
        static final XC_MethodReplacementTrue INSTANCE = new XC_MethodReplacementTrue();

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(Boolean.TRUE);
        }
    }
}
