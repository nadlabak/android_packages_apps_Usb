//Motorola USB.apk decompiled by Skrilax_CZ

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
	private int currentUsbModeIndex;
	private boolean isModemAvailable = true;
	private boolean isNGPAvailable = true;
	private DialogInterface.OnClickListener mUsbClickListener;
	private BroadcastReceiver mUsbModeSwitchReceiver;
	private int[] modeAtPosition;
	private int previousUsbModeIndex;
	private String[] tmpArray;

	public UsbModeSelectionActivity()
	{
		modeAtPosition = new int[]{ -1, -1, -1, -1, -1 };
		tmpArray = null;
		
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
				Log.d("UsbModeSelectionActivity", "onClick() --  " + String.valueOf(which));
				currentUsbModeIndex = modeAtPosition[which];
			}
		};
	}

	private void ReadPreviousUsbMode()
	{
		Log.d("UsbModeSelectionActivity", "ReadPreviousUsbMode()");
		
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
					previousUsbModeIndex = SystemProperties.getInt("ro.default_usb_mode", 0);
					System.putInt(getContentResolver(), "USB_SETTING", previousUsbModeIndex);
				}
			}
			else
				previousUsbModeIndex = modeFromPC;
		}
		catch (SettingNotFoundException ex)
		{
			try
			{
				previousUsbModeIndex = System.getInt(getContentResolver(), "USB_SETTING");
			}
			catch (SettingNotFoundException ex2)
			{
				previousUsbModeIndex = SystemProperties.getInt("ro.default_usb_mode", 0);
				System.putInt(getContentResolver(), "USB_SETTING", previousUsbModeIndex);
			}
		}
	}

	private boolean getModemAvailableFlex()
	{
		String str;
		
		if (TelephonyManager.getDefault().getPhoneType() == 1)
		{
			Log.d("UsbModeSelectionActivity", "umts phone");
			str = SystemProperties.get("ro.modem_available", "0");
		}
		else
		{
			Log.d("UsbModeSelectionActivity", "cdma phone");
			str = SystemProperties.get("ro.modem_available", "1");
		}
		
		return str.equals("1");
	}

	private boolean getNGPAvailableFlex()
	{
		String str;
		
		if (TelephonyManager.getDefault().getPhoneType() == 1)
		{
			Log.d("UsbModeSelectionActivity", "umts phone");
			str = SystemProperties.get("ro.ngp_available", "1");
		}
		else
		{
			Log.d("UsbModeSelectionActivity", "cdma phone");
			str = SystemProperties.get("ro.ngp_available", "0");
		}
		
		return str.equals("1");
	}

	private int getPositionFromMode(int mode)
	{
		int i = 0;
		
		while (true)
		{
			if (i < 5)
			{
				int j = modeAtPosition[i];
				if (j == mode)
					return i;
			}
			else
				return i;
				
			i = i + 1;
		}
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

		if (isNGPAvailable && isModemAvailable)
			tmpArray = new String[5];
		else if (isNGPAvailable || isModemAvailable)
			tmpArray = new String[4];
		else
			tmpArray = new String[3];
		
		int j = 0;
		
		if (isNGPAvailable)
		{
			tmpArray[j] = getString(R.string.usb_mode_ngp);
			modeAtPosition[j] = 0;
			j = j + 1;
		}
		
		tmpArray[j] = getString(R.string.usb_mode_mtp);
		modeAtPosition[j] = 1;
		j = j + 1;
		
		tmpArray[j] = getString(R.string.usb_mode_msc);
		modeAtPosition[j] = 2;
		j = j + 1;
		
		if (isModemAvailable)
		{
			tmpArray[j] = getString(R.string.usb_mode_modem);
			modeAtPosition[j] = 3;
			j = j + 1;
		}
		
		tmpArray[j] = getString(R.string.usb_mode_none);
		modeAtPosition[j] = 4;
		j = j + 1;
		
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
