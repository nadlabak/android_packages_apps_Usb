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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import com.android.internal.app.AlertActivity;

public class UsbErrorActivity extends AlertActivity
        implements DialogInterface.OnClickListener
{
    private static final String TAG = "UsbErrorActivity";

    private BroadcastReceiver mUsbErrorReceiver;

    public UsbErrorActivity() {
        mUsbErrorReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "onReceive(), received Intent -- " + action);
                if (action.equals(UsbService.ACTION_CABLE_DETACHED)) {
                    finish();
                }
            }
        };
    }

    public void onClick(DialogInterface dialog, int which) {
        finish();
    }

    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UsbService.ACTION_CABLE_DETACHED);

        registerReceiver(mUsbErrorReceiver, intentFilter);

        mAlertParams.mIconId = com.android.internal.R.drawable.ic_dialog_usb;
        mAlertParams.mTitle = getString(R.string.usb_connection);

        mAlertParams.mMessage = getString(R.string.usb_error_message,
                getIntent().getStringExtra(UsbService.EXTRA_ERROR_MODE_STRING));

        mAlertParams.mPositiveButtonText = getString(com.android.internal.R.string.ok);
        mAlertParams.mPositiveButtonListener = this;
        setupAlert();
    }

    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mUsbErrorReceiver);
    }
}
