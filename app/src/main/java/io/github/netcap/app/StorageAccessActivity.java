package io.github.netcap.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

/** 承接系统授权结果；授权期间悬浮对话框隐藏，返回后恢复原选择。 */
public final class StorageAccessActivity extends Activity {
    private static final int REQUEST = 41;
    private boolean returned;
    @Override
    public void onCreate(Bundle state) {
        setTheme(ThemeUi.app(this));
        super.onCreate(state);
        if (Environment.isExternalStorageManager()) {
            done();
            return;
        }
        if (state != null)
            return;
        try {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName())), REQUEST);
        } catch (Exception first) {
            try {
                startActivityForResult(
                    new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQUEST);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开授权页面，请在系统设置中允许 NetCap 管理所有文件",
                         Toast.LENGTH_LONG).show();
                done();
            }
        }
    }
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST)
            done();
    }
    private void done() {
        if (returned)
            return;
        returned = true;
        finish();
        CaptureService.storageAccessReturned();
    }
}
