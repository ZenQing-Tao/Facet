package app.facet.kit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** system_server 里的 Hook 发现主题图标包变了（换主题）就发这个广播，这里把自定义图标重新写进去。 */
public class ThemeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ThemeIcons.ACTION_CHANGED.equals(intent.getAction())) return;
        PendingResult pr = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                Log.i("Facet", "theme icons changed: " + ThemeIcons.sync(app, false));
            } finally {
                pr.finish();
            }
        }).start();
    }
}
