//Motorola USB.apk decompiled by Skrilax_CZ

package com.motorola.usb;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class UsbReceiver extends BroadcastReceiver
{
	public void onReceive(Context context, Intent intent)
	{
		String action = intent.getAction();

		Log.d("UsbReceiver", "onReceive(), received intent -- " + action);
		
		if (action.equals("android.intent.action.BOOT_COMPLETED"))
			context.startService( new Intent("com.motorola.intent.action.USB_LAUNCH_USBSERVICE"));
		
	}
}
