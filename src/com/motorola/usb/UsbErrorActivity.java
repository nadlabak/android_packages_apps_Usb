//Motorola USB.apk decompiled by Skrilax_CZ

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
import com.android.internal.app.AlertController.AlertParams;

public class UsbErrorActivity extends AlertActivity
	implements DialogInterface.OnClickListener
{
	private BroadcastReceiver mUsbErrorReceiver;

	public UsbErrorActivity()
	{
		mUsbErrorReceiver = new BroadcastReceiver()
		{
			public void onReceive(Context context, Intent intent)
			{
				String action = intent.getAction();
				Log.d("UsbErrorActivity", "onReceive(), received Intent -- " + action);
				if (action.equals("com.motorola.intent.action.USB_CABLE_DETACHED"))
					finish();
			}
		};
	}

	public void onClick(DialogInterface dialog, int which)
	{
		finish();
	}

	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		
		IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction("com.motorola.intent.action.USB_CABLE_DETACHED");
		
		registerReceiver(mUsbErrorReceiver, intentFilter);
		
		mAlertParams.mIconId = com.android.internal.R.drawable.ic_dialog_usb;
		mAlertParams.mTitle = getString(R.string.usb_connection);
		
		
		mAlertParams.mMessage = getString(R.string.usb_error_message) + " " + 
														getIntent().getStringExtra("USB_MODE_STRING") + 
														getString(R.string.usb_period);
		
		mAlertParams.mPositiveButtonText = getString(R.string.usb_ok);
		mAlertParams.mPositiveButtonListener = this;
		setupAlert();
	}

	protected void onDestroy()
	{
		super.onDestroy();
		unregisterReceiver(mUsbErrorReceiver);
	}
}
