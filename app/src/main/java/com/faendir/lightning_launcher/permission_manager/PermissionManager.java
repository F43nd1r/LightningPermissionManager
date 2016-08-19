package com.faendir.lightning_launcher.permission_manager;

import android.app.Application;
import android.content.Context;

import org.acra.ACRA;
import org.acra.ReportingInteractionMode;
import org.acra.annotation.ReportsCrashes;
import org.acra.sender.HttpSender;

/**
 * Main Application
 */
@ReportsCrashes(
        httpMethod = HttpSender.Method.PUT,
        reportType = HttpSender.Type.JSON,
        formUri = "https://faendir.smileupps.com/acra-permission-manager/_design/acra-storage/_update/report",
        formUriBasicAuthLogin = "permission_manager",
        formUriBasicAuthPassword = "pmR3p0rt",
        mode = ReportingInteractionMode.TOAST,
        resToastText = R.string.acra_toast,
        buildConfigClass = BuildConfig.class
)
public class PermissionManager extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        ACRA.init(this);
    }
}
