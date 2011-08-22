/*
 * Copyright (C) 2011 Skrilax_CZ
 * Based on Motorola Usb.apk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.motorola.usb;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.app.AlertActivity;

public class UsbModeSelectionActivity extends AlertActivity
        implements DialogInterface.OnClickListener
{
    private static final String TAG = "UsbModeSelectionActivity";

    private int currentUsbModeIndex;
    private int previousUsbModeIndex;

    private String[] mModeEntries;
    private int[] mModeValues;

    private DialogInterface.OnClickListener mUsbClickListener;
    private BroadcastReceiver mUsbModeSwitchReceiver;

    public UsbModeSelectionActivity() {
        mUsbModeSwitchReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "onReceive(), received Intent -- " + action);
                if (action.equals(UsbService.ACTION_CABLE_DETACHED)) {
                    finish();
                }
            }
        };

        mUsbClickListener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                currentUsbModeIndex = mModeValues[which];
                Log.d(TAG, "onClick() -- " + which + "->" + currentUsbModeIndex);
            }
        };
    }

    private int getPositionFromMode(int mode) {
        Log.d(TAG, "getPositionFromMode() --  " + mode);

        for (int i = 0; i < mModeValues.length; i++) {
            if (mode == mModeValues[i]) {
                return i;
            }
        }

        return 0;
    }

    public void onClick(DialogInterface dialog, int which) {
        Log.d(TAG, "onClick() --  " + String.valueOf(which));

        if (which == AlertDialog.BUTTON_POSITIVE) {
            if (currentUsbModeIndex != previousUsbModeIndex) {
                Intent intent = new Intent(UsbService.ACTION_MODE_SWITCH_FROM_UI);
                intent.putExtra(UsbService.EXTRA_MODE_SWITCH_MODE, currentUsbModeIndex);
                sendBroadcast(intent);
            }
        }
    }

    private boolean isUsbTethered() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        String[] tetheredIfaces = cm.getTetheredIfaces();
        String[] tetherableRegexs = cm.getTetherableUsbRegexs();

        for (String iface : tetheredIfaces) {
            for (String regex : tetherableRegexs) {
                if (iface.matches(regex)) {
                    return true;
                }
            }
        }
        return false;
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "onCreate()");

        Resources res = getResources();

        if (!res.getBoolean(R.bool.allow_mode_change_while_tethered) && isUsbTethered()) {
            Toast.makeText(this, R.string.usb_tethered_message, Toast.LENGTH_LONG).show();
            finish();
        }

        mModeEntries = res.getStringArray(R.array.usb_mode_entries);
        mModeValues = res.getIntArray(R.array.usb_mode_values);

        previousUsbModeIndex = UsbSettings.readCurrentMode(this);
        currentUsbModeIndex = previousUsbModeIndex;

        mAlertParams.mIconId = com.android.internal.R.drawable.ic_dialog_usb;
        mAlertParams.mTitle = getString(R.string.usb_connection);
        mAlertParams.mItems = mModeEntries;
        mAlertParams.mOnClickListener = mUsbClickListener;
        mAlertParams.mCheckedItem = getPositionFromMode(previousUsbModeIndex);
        mAlertParams.mIsSingleChoice = true;
        mAlertParams.mPositiveButtonText = getString(com.android.internal.R.string.ok);
        mAlertParams.mPositiveButtonListener = this;
        mAlertParams.mNegativeButtonText = getString(com.android.internal.R.string.cancel);
        mAlertParams.mNegativeButtonListener = this;

        setupAlert();

        registerReceiver(mUsbModeSwitchReceiver, new IntentFilter(UsbService.ACTION_CABLE_DETACHED));
    }

    protected void onDestroy()
    {
        super.onDestroy();
        unregisterReceiver(mUsbModeSwitchReceiver);
    }
}
