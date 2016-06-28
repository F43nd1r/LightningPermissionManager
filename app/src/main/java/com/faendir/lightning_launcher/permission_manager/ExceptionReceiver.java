package com.faendir.lightning_launcher.permission_manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.acra.ACRA;

import java.io.Serializable;

public class ExceptionReceiver extends BroadcastReceiver {
    public ExceptionReceiver() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if(Strings.INTENT_EXCEPTION.equals(intent.getAction()) && intent.hasExtra(Strings.KEY_EXCEPTION)){
            Serializable s = intent.getSerializableExtra(Strings.KEY_EXCEPTION);
            if(s instanceof Throwable){
                ACRA.getErrorReporter().handleException((Throwable) s, false);
            }
        }
    }
}
