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

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

public class UsbSettings
{
    private static final String TAG = "UsbSettings";

    private static final String KEY_UI = "ui_mode";
    private static final String KEY_PC = "pc_mode";

    public static int readCurrentMode(final Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int retval;

        if (prefs.contains(KEY_PC)) {
            retval = prefs.getInt(KEY_PC, -1);
            Log.d(TAG, "USB mode (PC) : " + retval);
        } else if (prefs.contains(KEY_UI)) {
            retval = prefs.getInt(KEY_UI, -1);
            Log.d(TAG, "USB mode (UI) : " + retval);
        } else {
            retval = context.getResources().getInteger(R.integer.default_usb_mode);
            Log.d(TAG, "USB mode (fallback) : " + retval);
        }

        return retval;
    }

    public static void writeMode(final Context context, int value, boolean fromUi) {
        SharedPreferences.Editor editor =
                PreferenceManager.getDefaultSharedPreferences(context).edit();
        final String key = fromUi ? KEY_UI : KEY_PC;

        if (value < 0) {
            editor.remove(key);
        } else {
            editor.putInt(key, value);
        }
        editor.commit();
    }
}
