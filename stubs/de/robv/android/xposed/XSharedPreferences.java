package de.robv.android.xposed;
import java.io.File;
import java.util.Map;
public final class XSharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) {}
    public File getFile() { return null; }
    public boolean makeWorldReadable() { return false; }
    public synchronized void reload() {}
    public synchronized boolean hasFileChanged() { return false; }
    public Map<String, ?> getAll() { return null; }
    public String getString(String key, String defValue) { return null; }
    public boolean getBoolean(String key, boolean defValue) { return false; }
}
