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
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Settings.System;
import android.telephony.TelephonyManager;
import android.util.Log;
import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController.AlertParams;

public class UsbModeSelectionActivity extends AlertActivity
	implements DialogInterface.OnClickListener
{
	private final int NO_ITEM = -1;

	private boolean isModemAvailable = true;
	private boolean isNGPAvailable = true;
	private boolean isMtpAvailable = true;
	private boolean isRndisAvailable = true;

	private String[] tmpArray;
	private int[] modeAtPosition = new int[]{ -1, -1, -1, -1, -1 , -1, -1 };
	private int currentUsbModeIndex;
	private int previousUsbModeIndex;

	private DialogInterface.OnClickListener mUsbClickListener;
	private BroadcastReceiver mUsbModeSwitchReceiver;

	public UsbModeSelectionActivity()
	{

		mUsbModeSwitchReceiver = new BroadcastReceiver()
		{
			public void onReceive(Context context, Intent intent)
			{
				String action = intent.getAction();
				Log.d("UsbModeSelectionActivity", "onReceive(), received Intent -- " + action);
				if (action.equals("com.motorola.intent.action.USB_CABLE_DETACHED"))
					finish();
			}
		};

		mUsbClickListener = new DialogInterface.OnClickListener()
		{
			public void onClick(DialogInterface dialog, int which)
			{
				currentUsbModeIndex = modeAtPosition[which];
				Log.d("UsbModeSelectionActivity", "onClick() -- " + String.valueOf(which) + "->" + String.valueOf(currentUsbModeIndex));
			}
		};
	}

	private void ReadPreviousUsbMode()
	{
		try
		{
			int modeFromPC = System.getInt(getContentResolver(), "USB_MODE_FROM_PC");

			if ( modeFromPC == -1)
			{
				try
				{
					previousUsbModeIndex = System.getInt(getContentResolver(), "USB_SETTING");
				}
				catch (SettingNotFoundException ex)
				{
					Log.w("UsbModeSelectionActivity", "ReadPreviousUsbMode()", ex);
					previousUsbModeIndex = SystemProperties.getInt("ro.default_usb_mode", 0);
					System.putInt(getContentResolver(), "USB_SETTING", previousUsbModeIndex);
				}
			}
			else
				previousUsbModeIndex = modeFromPC;
		}
		catch (SettingNotFoundException ex)
		{
			Log.w("UsbModeSelectionActivity", "ReadPreviousUsbMode()", ex);
			try
			{
				previousUsbModeIndex = System.getInt(getContentResolver(), "USB_SETTING");
			}
			catch (SettingNotFoundException ex2)
			{
				Log.w("UsbModeSelectionActivity", "ReadPreviousUsbMode()", ex2);
				previousUsbModeIndex = SystemProperties.getInt("ro.default_usb_mode", 0);
				System.putInt(getContentResolver(), "USB_SETTING", previousUsbModeIndex);
			}
		}
		Log.d("UsbModeSelectionActivity", "ReadPreviousUsbMode() = " + String.valueOf(previousUsbModeIndex));
	}

	private boolean getModemAvailableFlex()
	{
		return SystemProperties.get("ro.modem_available", "1").equals("1");
		/*
		String str;
		if (TelephonyManager.getDefault().getPhoneType() == 1)
		{
			Log.d("UsbModeSelectionActivity", "umts phone");
			str = SystemProperties.get("ro.modem_available", "0");
		} else {
			Log.d("UsbModeSelectionActivity", "cdma phone");
			str = SystemProperties.get("ro.modem_available", "1");
		}
		return str.equals("1");
		*/
	}

	private boolean getNGPAvailableFlex()
	{
		String str;
		if (TelephonyManager.getDefault().getPhoneType() == 1)
		{
			Log.d("UsbModeSelectionActivity", "umts phone");
			str = SystemProperties.get("ro.ngp_available", "1");
		} else {
			Log.d("UsbModeSelectionActivity", "cdma phone");
			str = SystemProperties.get("ro.ngp_available", "0");
		}
		return str.equals("1");
	}

	private boolean getMtpAvailableFlex()
	{
		return SystemProperties.get("ro.mtp_available", "1").equals("1");
	}

	private boolean getRndisAvailableFlex()
	{
		return SystemProperties.get("ro.rndis_available", "1").equals("1");
	}

	private int getPositionFromMode(int mode)
	{
		Log.d("UsbModeSelectionActivity", "getPositionFromMode() --  " + String.valueOf(mode));

		int i = 0;
		while (i <= UsbService.USB_MODE_NONE)
		{
			if (modeAtPosition[i] == mode)
				return i;
			i = i + 1;
		}
		return previousUsbModeIndex;
	}

	public void onClick(DialogInterface dialog, int which)
	{
		Log.d("UsbModeSelectionActivity", "onClick() --  " + String.valueOf(which));

		if (which == -1 )
		{
			if (currentUsbModeIndex != previousUsbModeIndex)
			{
				Intent intent = new Intent("com.motorola.intent.action.USB_MODE_SWITCH_FROM_UI");
				intent.putExtra("USB_MODE_INDEX", String.valueOf(currentUsbModeIndex));
				sendBroadcast(intent);
			}
		}
	}

	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		Log.d("UsbModeSelectionActivity", "onCreate()");

		isNGPAvailable = getNGPAvailableFlex();
		isModemAvailable = getModemAvailableFlex();
		isMtpAvailable = getMtpAvailableFlex();
		isRndisAvailable = getRndisAvailableFlex();

		int len = 2;

		if (isNGPAvailable)
			len = len + 1;
		if (isMtpAvailable)
			len = len + 1;
		if (isRndisAvailable)
			len = len + 1;
		if (isModemAvailable)
			len = len + 1;

		tmpArray = new String[len];

		int j = 0;

		if (isNGPAvailable)
		{
			tmpArray[j] = getString(R.string.usb_mode_ngp);
			modeAtPosition[j] = UsbService.USB_MODE_NGP;
			j = j + 1;
		}

		if (isMtpAvailable)
		{
			tmpArray[j] = getString(R.string.usb_mode_mtp);
			modeAtPosition[j] = UsbService.USB_MODE_MTP;
			j = j + 1;
		}

		if (true)
		{
			tmpArray[j] = getString(R.string.usb_mode_msc);
			modeAtPosition[j] = UsbService.USB_MODE_MSC;
			j = j + 1;
		}

		if (isRndisAvailable)
		{
			tmpArray[j] = getString(R.string.usb_mode_rndis);
			modeAtPosition[j] = UsbService.USB_MODE_RNDIS;
			j = j + 1;
		}

		if (isModemAvailable)
		{
			tmpArray[j] = getString(R.string.usb_mode_modem);
			modeAtPosition[j] = UsbService.USB_MODE_MODEM;
			j = j + 1;
		}

		if (true)
		{
			tmpArray[j] = getString(R.string.usb_mode_none);
			modeAtPosition[j] = UsbService.USB_MODE_NONE;
			j = j + 1;
		}

		ReadPreviousUsbMode();

		currentUsbModeIndex = previousUsbModeIndex;
		mAlertParams.mIconId = com.android.internal.R.drawable.ic_dialog_usb;
		mAlertParams.mTitle = getString(R.string.usb_connection);
		mAlertParams.mItems = tmpArray;
		mAlertParams.mOnClickListener = mUsbClickListener;
		mAlertParams.mCheckedItem = getPositionFromMode(previousUsbModeIndex);
		mAlertParams.mIsSingleChoice = true;
		mAlertParams.mPositiveButtonText = getString(R.string.usb_ok);
		mAlertParams.mPositiveButtonListener = this;
		mAlertParams.mNegativeButtonText = getString(R.string.usb_cancel);
		mAlertParams.mNegativeButtonListener = this;

		setupAlert();

		registerReceiver(mUsbModeSwitchReceiver, new IntentFilter("com.motorola.intent.action.USB_CABLE_DETACHED"));
	}

	protected void onDestroy()
	{
		super.onDestroy();
		unregisterReceiver(mUsbModeSwitchReceiver);
	}
}
