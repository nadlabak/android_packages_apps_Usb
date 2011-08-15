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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UEventObserver;
import android.os.storage.IMountService;
import android.os.storage.IMountService.Stub;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Settings.System;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;
import com.android.internal.telephony.ITelephony;
import java.util.Timer;
import java.util.TimerTask;

public class UsbService extends Service
{
	private static final String[] mUsbModeString = new String[]
	{
		"Motorola Phone Tools",
		"Windows Media Sync",
		"Memory Card Management",
		"USB Networking",
		"Phone as Modem",
//		"Test",
		"None",
	};

	/* Possible modes assigned by system/bin/usbd tool :

		usbd mode code & number  VID:PID  usb_device_mode  Comments

		usb_mode_ngp_adb      6 22b8:41da acm_eth_mtp_adb  Portal and tools (General Purpose)
		usb_mode_mtp_adb      7 22b8:41dc mtp_adb          Windows Media Sync (Photo & Media)
		usb_mode_msc_adb      8 22b8:41db msc_adb          usb-storage (to check)
		usb_mode_adb          9 ?         ?                usbd return usb_mode_hid:ok ... weird
		                        22b8:41d4 eth, msc_eth, msc_adb_eth (3 modes with same IDs)
		                        22b8:41e2 acm_eth_adb
		                        22b8:41ea eth_adb
		usb_mode_rndis_adb   17 22b8:41e5 rndis_adb        Usb networking (450Mbps) eth is (12Mbps)
		usb_mode_modem_adb   12 22b8      ?                to check...
		usb_mode_charge_adb  15 22b8:428c charge_adb

		usb_mode_ngp          2 22b8:41de cdrom ???
		usb_mode_mtp          3 22b8:41d6 mtp
		                        22b8:41d8
		usb_mode_msc          4 22b8:41d9 msc              seems cdrom
		usb_mode_rndis       16 22b8:41e4 rndis
		usb_mode_modem       11 22b8:6422 acm
		usb_mode_charge_only 14 22b8:4287 charge_only

		usb_mode_hid            this one is different (host mode)

		To get more informations on supported kernel usb modes, these modes are not exactly same as supported usbd ones :
		https://www.gitorious.org/android_kernel_omap/android_kernel_omap/blobs/motorola_342_145_r1561/drivers/usb/gadget/mot_android.c#line87
	*/

	//the two following tables are used to switch to this mode (if adb or not)
	private static final String[] mUsbModeSwitchCmdADBMapping = new String[] {
		"usb_mode_ngp_adb",
		"usb_mode_mtp_adb",
		"usb_mode_msc_adb",
		"usb_mode_rndis_adb",
		"usb_mode_modem",      // usb_mode_modem_adb doesnt works (with adb)
//		"usb_mode_adb",
		"usb_mode_charge_adb", // charge only but adb ?? :p
	};

	private static final String[] mUsbModeSwitchCmdMapping = new String[] {
		"usb_mode_ngp",        //usb_mode_ngp seems to share drivers (cdrom partition)
		"usb_mode_mtp",
		"usb_mode_msc",
		"usb_mode_rndis",
		"usb_mode_modem",
//		"usb_mode_hid",
		"usb_mode_charge_only",
	};

	private static final String[] mUsbStateString = new String[] {
		"USB Idle State",
		"USB Wait Device Enum State",
		"USB Service Startup State",
		"USB In-Switch Wait DevNod Close State",
		"USB Detach Wait DevNod Close State",
		"USB Attach Wait DevNod Close State",
	};

	public static final int USB_STATE_IDLE = 0;
	public static final int USB_STATE_WAIT_ENUM = 1;
	public static final int USB_STATE_SERVICE_STARTUP = 2;
	public static final int USB_STATE_SWITCH_DEVNOD_CLOSE = 3;
	public static final int USB_STATE_DETACH_DEVNOD_CLOSE = 4;
	public static final int USB_STATE_ATTACH_DEVNOD_CLOSE = 5;

	public static final int USB_MODE_NGP = 0;
	public static final int USB_MODE_MTP = 1;
	public static final int USB_MODE_MSC = 2;
	public static final int USB_MODE_RNDIS = 3;
	public static final int USB_MODE_MODEM = 4;
//	public static final int USB_MODE_HID  = 5;
	public static final int USB_MODE_NONE = 5;

	public static final int USB_SWITCH_FROM_IDLE = -1;
	public static final int USB_SWITCH_FROM_UI = 0;
	public static final int USB_SWITCH_FROM_AT_CMD = 1;
	public static final int USB_SWITCH_FROM_ADB = 2;
	public static final int USB_SWITCH_FROM_USBD = 3;
	public static final int USB_SWITCH_FROM_PHONE_UNLOCK = 4;

	private boolean pending_usblan_intent = false;
	private boolean atcmd_service_stopped = false;
	private boolean mtp_service_stopped = false;
	private boolean rndis_service_stopped = false;
	private boolean mUsbCableAttached = false;
	private boolean mADBEnabled = false;

	private boolean isModemAvailable = true;
	private boolean isNGPAvailable = true;
	private boolean isMtpAvailable = true;

	private int mADBStatusChangeMissedNumber = 0;

	private boolean mMediaMountedReceiverRegistered = false;
	private int mIsSwitchFrom = USB_SWITCH_FROM_IDLE;
	private int mUsbState = USB_STATE_IDLE;
	private int mNewUsbMode = USB_MODE_NONE;
	private int mCurrentUsbMode = USB_MODE_NONE;

	private String mUsbEvent;
	private UsbListener mUsbListener;

	private UEventObserver mUEventObserver;
	private ITelephony mPhoneService;
	private BroadcastReceiver mUsbServiceReceiver;
	private BroadcastReceiver mMediaMountedReceiver;
	private Notification mUsbConnectionNotification;
	private Timer mWait4DevCloseTimer;

	public UsbService()
	{
		mUsbServiceReceiver = new BroadcastReceiver()
		{
			public void onReceive(Context context, Intent intent)
			{
				String action = intent.getAction();
				Log.d("UsbService", "onReceive(), received intent:" + action);

				if (action.equals("com.motorola.intent.action.USB_ATCMD_DEV_CLOSED"))
				{
					Log.d("UsbService", "onReceive(USB_ATCMD_DEV_CLOSED)");
					atcmd_service_stopped = true;
					handleAtCmdMtpDevClosed();
				}
				else if (action.equals("com.motorola.intent.action.USB_MTP_EXIT_OK"))
				{
					Log.d("UsbService", "onReceive(USB_MTP_EXIT_OK)");
					mtp_service_stopped = true;
					handleAtCmdMtpDevClosed();
				}
				else if (action.equals("com.motorola.intent.action.USB_RNDIS_EXIT_OK"))
				{
					Log.d("UsbService", "onReceive(USB_RNDIS_EXIT_OK)");
					rndis_service_stopped = true;
					handleAtCmdMtpDevClosed();
				}
				else if (action.equals("com.motorola.intent.action.SHOW_USB_CABLE_ATTACH_TOAST"))
					Toast.makeText(context, getToastStringForCableAttach(), 1).show();
				else if (action.equals("com.motorola.intent.action.SHOW_USB_MODE_SWITCH_TOAST"))
					Toast.makeText(context, getToastStringForModeSwitch(), 1).show();
				else if (action.equals("android.keyguard.intent.SHOW"))
				{
					//nothing ??? (the orignial has empty clause
				}
				else if (action.equals("android.keyguard.intent.HIDE"))
					handleUsbModeSwitchFromPhoneUnlock();
				else if (action.equals("com.motorola.intent.action.USB_MODE_SWITCH_FROM_UI"))
				{
					String indexStr = intent.getStringExtra("USB_MODE_INDEX");
					int index;

					Log.d("UsbService", "onReceive(USB_MODE_SWITCH_FROM_UI) mode=" + indexStr);
					try
					{
						index = Integer.valueOf(indexStr);
						if (index < 0 || index > USB_MODE_NONE)
							index = -1;
					}
					catch(Exception ex)
					{
						Log.w("UsbService", "onReceive: ", ex);
						index = -1;
					}

					setUsbModeFromUI(index);
				}
				else if(action.equals("com.motorola.intent.action.USB_MODE_SWITCH_FROM_ATCMD"))
				{
					String indexStr = intent.getStringExtra("USB_MODE_INDEX");
					int index;

					Log.d("UsbService", "onReceive(USB_MODE_SWITCH_FROM_ATCMD) mode=" + indexStr);
					try
					{
						index = Integer.valueOf(indexStr);
						if (index < 0 || index > USB_MODE_NONE)
							index = -1;
					}
					catch(Exception ex)
					{
						Log.w("UsbService", "onReceive: ", ex);
						index = -1;
					}

					if (index >= 0) {
						setUsbModeFromAtCmd(index);
					}
				}
			}
		};

		mMediaMountedReceiver = new BroadcastReceiver()
		{
			public void onReceive(Context context, Intent intent)
			{
				String action = intent.getAction();
				int i = Log.d("UsbService", "onReceive(), received intent:" + action);

				if (action.equals("android.intent.action.MEDIA_MOUNTED"))
					startService(new Intent("com.motorola.intent.action.USB_MTP_CONNECTION"));

			}
		};

		mUEventObserver = new UEventObserver()
		{
			public void onUEvent(UEvent uevent)
			{
				Log.d("UsbService", "USBLAN Uevent: " + uevent.toString());

				if(Integer.parseInt(uevent.get("USB_CONNECT")) == 1)
				{
					Log.d("UsbService", "USBLAN enabled");

					if(mUsbState == USB_STATE_SERVICE_STARTUP)
						sendUsblanUpIntent();
				}
				else
				{
					Log.d("UsbService", "USBLAN disabled");
					sendUsblanDownIntent();
				}
			}
		};
	}

	private void DeviceEnumPostAction()
	{
		Log.d("UsbService", "DeviceEnumPostAction()");
		ReadCurrentUsbMode();

		switch (getUsbModeClass(mCurrentUsbMode))
		{
			case USB_MODE_NGP:
				StartAtCmdService();
				break;

			case USB_MODE_MTP:
				StartMtpService();
				break;

			case USB_MODE_MSC:
				EnterMassStorageMode();
				break;

			case USB_MODE_RNDIS:
				StartRndisService();
				break;

			case USB_MODE_MODEM:
				StartAtCmdService();
				StartMtpService();
				break;
		}
	}

	private void DeviceEnumPreAction()
	{
		Log.d("UsbService", "DeviceEnumPreAction()");
		ReadCurrentUsbMode();

		switch (getUsbModeClass(mCurrentUsbMode))
		{
			case USB_MODE_NGP:
				StartWaitDevNodeClosedTimer();
				StopAtCmdService();
				break;

			case USB_MODE_MTP:
				StartWaitDevNodeClosedTimer();
				StopMtpService();
				break;

			case USB_MODE_MSC:
				ExitMassStorageMode();
				mUsbEvent = "usb_devnode_closed";
				UsbEventHandler(mUsbEvent);
				break;

			case USB_MODE_RNDIS:
				StartWaitDevNodeClosedTimer();
				StopRndisService();
				break;

			case USB_MODE_MODEM:
				StartWaitDevNodeClosedTimer();
				StopAtCmdService();
				StopMtpService();
				break;

			default:
				mUsbEvent = "usb_devnode_closed";
				UsbEventHandler(mUsbEvent);
				break;
		}
	}

	private void EnterMassStorageMode()
	{
		Log.d("UsbService", "EnterMassStorageMode()");
		IMountService mountService = IMountService.Stub.asInterface(ServiceManager.getService("mount"));

		if (mountService != null)
		{
			try
			{
				mountService.setUsbMassStorageEnabled(true);
			}
			catch (RemoteException e)
			{
				Log.w("UsbService", "EnterMassStorageMode()", e);
			}

			sendBroadcast(new Intent("com.motorola.intent.action.USB_ENTER_MSC_MODE"));
		}
	}

	private void ExitMassStorageMode()
	 {
		Log.d("UsbService", "EnterMassStorageMode()");
		IMountService mountService = IMountService.Stub.asInterface(ServiceManager.getService("mount"));

		if (mountService != null)
		{
			try {
				mountService.setUsbMassStorageEnabled(false);
			}
			catch (RemoteException e)
			{
				Log.w("UsbService", "ExitMassStorageMode()", e);
			}

			sendBroadcast(new Intent("com.motorola.intent.action.USB_EXIT_MSC_MODE"));
		}
	}

	private void ReadCurrentUsbMode()
	{
		try
		{
			int modeFromPC = System.getInt(getContentResolver(), "USB_MODE_FROM_PC");

			if (modeFromPC == -1)
			{
				try {
					mCurrentUsbMode = System.getInt(getContentResolver(), "USB_SETTING");
					Log.d("UsbService", "Current Usb Mode: " + getUsbModeString(mCurrentUsbMode));
				}
				catch (SettingNotFoundException ex)
				{
					mCurrentUsbMode = SystemProperties.getInt("ro.default_usb_mode", 0);
					Log.d("UsbService", "read usb setting exception");
					System.putInt(getContentResolver(), "USB_SETTING", mCurrentUsbMode);
				}
			} else {
				mCurrentUsbMode = modeFromPC;
				Log.d("UsbService", "Current Usb Mode: " + getUsbModeString(mCurrentUsbMode));
			}
		}
		catch (SettingNotFoundException ex)
		{
			try {
				mCurrentUsbMode = System.getInt(getContentResolver(), "USB_SETTING");
				Log.d("UsbService", "Current Usb Mode: " + getUsbModeString(mCurrentUsbMode));
			}
			catch (SettingNotFoundException ex2)
			{
				mCurrentUsbMode = SystemProperties.getInt("ro.default_usb_mode", 0);
				Log.w("UsbService", "read usb setting exception", ex2);
				System.putInt(getContentResolver(), "USB_SETTING", mCurrentUsbMode);
			}
		}
	}

	private void StartAtCmdService()
	{
		Log.d("UsbService", "StartAtCmdService()");
		startService(new Intent("com.motorola.intent.action.USB_ATCMD_SERVICE_START"));
	}

	private void StartMtpService()
	{
		Log.d("UsbService", "StartMtpService()");
		String storageState = Environment.getExternalStorageState();

		if ((storageState.equals("shared")) || (storageState.equals("checking")))
		{
			Log.d("UsbService", "StartMtpService(), sd card currently shared or checking");
			IntentFilter myIntentFilter = new IntentFilter("android.intent.action.MEDIA_MOUNTED");
			myIntentFilter.addDataScheme("file");
			registerReceiver(mMediaMountedReceiver, myIntentFilter);
			mMediaMountedReceiverRegistered = true;
		}
		else
		{
			//to change, not available now in CM7
			try {
				startService(new Intent("com.motorola.intent.action.USB_MTP_CONNECTION"));
			} catch (Exception ignore) {}
		}
	}

	private void StartRndisService()
	{
		Log.d("UsbService", "StartRndisService() TODO dnsmasq ?");
		try {
			startService(new Intent("com.motorola.intent.action.USB_RNDIS_CONNECTION"));
		} catch (Exception ignore) {}
	}

	private void StartWaitDevNodeClosedTimer()
	{
		Log.d("UsbService", "StartWaitDevNodeClosedTimer()");
		mWait4DevCloseTimer = new Timer();
		mWait4DevCloseTimer.schedule(new TimerTask()
		{
			public void run()
			{
				WaitDevNodeClosedTimeout();
			}

		}, 2000);
	}

	private void StopAtCmdService()
	{
		Log.d("UsbService", "StopAtCmdService()");
		sendBroadcast(new Intent("com.motorola.intent.action.USB_ATCMD_SERVICE_STOP_OR_CLOSEDEV"));
	}

	private void StopMtpService()
	{
		Log.d("UsbService", "StopMtpService()");
		stopService(new Intent("com.motorola.intent.action.USB_MTP_CONNECTION"));

		if (mMediaMountedReceiverRegistered)
		{
			unregisterReceiver(mMediaMountedReceiver);
			mMediaMountedReceiverRegistered = false;
		}
	}

	private void StopRndisService()
	{
		Log.d("UsbService", "StopRndisService()");
		stopService(new Intent("com.motorola.intent.action.USB_RNDIS_CONNECTION"));
	}

	private void StopWaitDevNodeClosedTimer()
	{
		Log.d("UsbService", "StopWaitDevNodeClosedTimer()");
		mWait4DevCloseTimer.cancel();
	}

	private synchronized void UsbEventHandler(String event)
	{
		Log.d("UsbService", "UsbEventHandler(), Received event: " + event);
		Log.d("UsbService", "Current Usb State: " + mUsbStateString[mUsbState]);

		ReadCurrentUsbMode();

		switch(mUsbState)
		{
			case USB_STATE_IDLE:
				if (event.equals("usb_cable_inserted"))
				{
					mUsbState = USB_STATE_WAIT_ENUM;
					if (mADBEnabled)
						mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmdADB(mCurrentUsbMode));
					else
						mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmd(mCurrentUsbMode));

				}
				else if (event.equals("usb_cable_enumerated"))
				{
					Log.d("UsbService", "Idle state, receive USB_CABLE_ENUMERATE_EVENT.");
					mUsbState = USB_STATE_SERVICE_STARTUP;
					mIsSwitchFrom = USB_SWITCH_FROM_IDLE;
				}
				break;

			case USB_STATE_WAIT_ENUM:
				if (event.equals("usb_cable_removed"))
				{
					if (mIsSwitchFrom == USB_SWITCH_FROM_UI)
						UsbModeSwitchFail();

					mUsbListener.sendUsbModeSwitchCmd("usb_unload_driver");
					mUsbState = USB_STATE_IDLE;
					mIsSwitchFrom = USB_SWITCH_FROM_IDLE;
				}
				else if (event.equals("usb_device_enum_ok"))
				{
					if (mIsSwitchFrom == USB_SWITCH_FROM_UI)
					{
						WriteNewUsbMode(mNewUsbMode);
						UsbModeSwitchSuccess();
						System.putInt(getContentResolver(), "USB_MODE_FROM_PC", -1);
					}
					else if(mIsSwitchFrom == USB_SWITCH_FROM_USBD || mIsSwitchFrom == USB_SWITCH_FROM_AT_CMD)
						System.putInt(getContentResolver(), "USB_MODE_FROM_PC", mNewUsbMode);

					ReadCurrentUsbMode();

					if(mIsSwitchFrom != USB_SWITCH_FROM_IDLE)
					{
						setUsbConnectionNotificationVisibility(true, false);

						if (mCurrentUsbMode == USB_MODE_MODEM)
							enableInternalDataConnectivity(false);
						else
							enableInternalDataConnectivity(true);

						emitReconfigurationIntent(true);
					}

					if (mADBStatusChangeMissedNumber != 0)
					{
						mADBStatusChangeMissedNumber = mADBStatusChangeMissedNumber - 1;
						mADBEnabled = !mADBEnabled;
						mIsSwitchFrom = USB_SWITCH_FROM_ADB;
						mUsbEvent = "usb_switch_from_adb";
						mUsbState = USB_STATE_SERVICE_STARTUP;
						UsbEventHandler(mUsbEvent);
					}
					else
					{
						mUsbState = USB_STATE_SERVICE_STARTUP;
						mIsSwitchFrom = USB_SWITCH_FROM_IDLE;
					}

				}
				break;

			case USB_STATE_SERVICE_STARTUP:
				if(event.equals("usb_start_service"))
				{
					DeviceEnumPostAction();
					mUsbState = USB_STATE_SERVICE_STARTUP;
				}
				else if(event.equals("usb_cable_removed"))
				{
					mUsbState = USB_STATE_DETACH_DEVNOD_CLOSE;
					DeviceEnumPreAction();
				}
				else if(event.equals("usb_switch_from_ui") || event.equals("usb_switch_from_atcmd")
							|| event.equals("usb_switch_from_adb") || event.equals("usb_switch_from_usbd")
							|| event.equals("usb_switch_from_phone_unlock"))
				{
					mUsbState = USB_STATE_SWITCH_DEVNOD_CLOSE;
					DeviceEnumPreAction();
				}
				break;

			case USB_STATE_SWITCH_DEVNOD_CLOSE:
				if(event.equals("usb_cable_removed"))
				{
					if(mIsSwitchFrom == USB_SWITCH_FROM_UI)
						UsbModeSwitchFail();

					mIsSwitchFrom = USB_SWITCH_FROM_IDLE;
					mUsbState = USB_STATE_DETACH_DEVNOD_CLOSE;
				}
				else if(event.equals("usb_devnode_closed"))
				{
					mUsbState = USB_STATE_WAIT_ENUM;

					if(mIsSwitchFrom == USB_SWITCH_FROM_UI || mIsSwitchFrom == USB_SWITCH_FROM_AT_CMD
						|| mIsSwitchFrom == USB_SWITCH_FROM_USBD)
					{
						if(mADBEnabled)
							mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmdADB(mNewUsbMode));
						else
							mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmd(mNewUsbMode));
					}
					else if (mIsSwitchFrom == USB_SWITCH_FROM_ADB
						|| mIsSwitchFrom == USB_SWITCH_FROM_PHONE_UNLOCK)
					{
						if(mADBEnabled)
							mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmdADB(mCurrentUsbMode));
						else
							mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmd(mCurrentUsbMode));
					}
				}
				break;

			case USB_STATE_DETACH_DEVNOD_CLOSE:
				if(event.equals("usb_cable_inserted"))
					mUsbState = USB_STATE_ATTACH_DEVNOD_CLOSE;
				else if(event.equals("usb_devnode_closed"))
				{
					mUsbListener.sendUsbModeSwitchCmd("usb_unload_driver");
					mUsbState = USB_STATE_IDLE;
					mIsSwitchFrom = USB_SWITCH_FROM_IDLE;
				}
				break;

			case USB_STATE_ATTACH_DEVNOD_CLOSE:
				if(event.equals("usb_cable_removed"))
					mUsbState = USB_STATE_DETACH_DEVNOD_CLOSE;
				else if(event.equals("usb_devnode_closed"))
				{
					mUsbState = USB_STATE_WAIT_ENUM;

					if(mADBEnabled)
						mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmdADB(mCurrentUsbMode));
					else
						mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmd(mCurrentUsbMode));
				}

				break;
		}
	}

	private void UsbModeSwitchFail()
	{
		Log.d("UsbService", "UsbModeSwitchFail()");
		Intent myIntent = new Intent();
		myIntent.setAction("android.intent.action.MAIN");
		myIntent.addCategory("android.intent.category.LAUNCHER");
		myIntent.setFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_NEW_TASK);
		myIntent.setClassName("com.motorola.usb", "com.motorola.usb.UsbErrorActivity");

		switch (mNewUsbMode)
		{
			case USB_MODE_NGP:
				myIntent.putExtra("USB_MODE_STRING", getString(R.string.usb_mode_ngp));
				break;

			case USB_MODE_MTP:
				myIntent.putExtra("USB_MODE_STRING", getString(R.string.usb_mode_mtp));
				break;

			case USB_MODE_MSC:
				myIntent.putExtra("USB_MODE_STRING", getString(R.string.usb_mode_msc));
				break;

			case USB_MODE_RNDIS:
				myIntent.putExtra("USB_MODE_STRING", getString(R.string.usb_mode_rndis));
				break;

			case USB_MODE_MODEM:
				myIntent.putExtra("USB_MODE_STRING", getString(R.string.usb_mode_modem));
				break;

			case USB_MODE_NONE:
				myIntent.putExtra("USB_MODE_STRING", getString(R.string.usb_mode_none));
				break;
		}

		startActivity(myIntent);
	}

	private void UsbModeSwitchSuccess()
	{
		Log.d("UsbService", "UsbModeSwitchSuccess()");
	}

	private void WaitDevNodeClosedTimeout()
	{
		Log.d("UsbService", "WaitDevNodeClosedTimeout()");
		atcmd_service_stopped = false;
		mtp_service_stopped = false;
		rndis_service_stopped = false;
		mUsbEvent = "usb_devnode_closed";
		UsbEventHandler(mUsbEvent);
	}

	private void WriteNewUsbMode(int paramInt)
	{
		Log.d("UsbService", "WriteNewUsbMode(), New Usb Mode: " + String.valueOf(paramInt));
		System.putInt(getContentResolver(), "USB_SETTING", paramInt);
	}

	private PendingIntent createUsbModeSelectionDialogIntent()
	{
		Intent myIntent = new Intent();
		myIntent.setClass(this, UsbModeSelectionActivity.class);
		return PendingIntent.getActivity(this, 0, myIntent, 0);
	}

	private void enableInternalDataConnectivity(boolean enable)
	{
		Log.d("UsbService", "enableInternalDataConnectivity(): " + String.valueOf(enable));
		getPhoneService();

		if (mPhoneService != null)
		{
			if (enable)
			{
				try {
					Log.d("UsbService", "enableDataConnectivity()");
					mPhoneService.enableDataConnectivity();
				}
				catch (RemoteException ex)
				{
					Log.d("UsbService", "enableDataConnectivity() failed");
				}
			}
			else
			{
				try {
					Log.d("UsbService", "disableDataConnectivity()");
					mPhoneService.disableDataConnectivity();
				}
				catch (RemoteException ex)
				{
					Log.d("UsbService", "disableDataConnectivity() failed");
				}
			}
		}
	}

	private boolean getModemAvailableFlex()
	{
		String str;

		if (TelephonyManager.getDefault().getPhoneType() == 1) {
			Log.d("UsbModeSelectionActivity", "umts phone");
			str = SystemProperties.get("ro.modem_available", "0");
		} else {
			Log.d("UsbModeSelectionActivity", "cdma phone");
			str = SystemProperties.get("ro.modem_available", "1");
		}

		return str.equals("1");
	}

	private boolean getNGPAvailableFlex()
	{
		String str;

		if (TelephonyManager.getDefault().getPhoneType() == 1) {
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

	private void getPhoneService()
	{
		if (mPhoneService == null)
			mPhoneService = ITelephony.Stub.asInterface(ServiceManager.getService("phone"));
	}

	private String getToastStringForCableAttach()
	{
		Log.d("UsbService", "getToastStringForCableAttach()");
		ReadCurrentUsbMode();

		switch (mCurrentUsbMode)
		{
			case USB_MODE_NGP:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_ngp);

			case USB_MODE_MTP:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_mtp);

			case USB_MODE_MSC:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_msc);

			case USB_MODE_RNDIS:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_rndis);

			case USB_MODE_MODEM:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_modem) +
					getString(R.string.usb_period) + " " + getString(R.string.usb_toast_phone_data_disabled);

			case USB_MODE_NONE:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_none);
		}

		return null;
	}

	private String getToastStringForModeSwitch()
	{
		int i = Log.d("UsbService", "getToastStringForModeSwitch()");

		switch (mNewUsbMode)
		{
			case USB_MODE_NGP: //0
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_ngp);

			case USB_MODE_MTP: //1
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_mtp);

			case USB_MODE_MSC: //2
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_msc);

			case USB_MODE_RNDIS: //3
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_rndis);

			case USB_MODE_MODEM: //4
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_modem) +
					getString(R.string.usb_period) + " " + getString(R.string.usb_toast_phone_data_disabled);

			case USB_MODE_NONE: //5
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_none);
		}

		return null;
	}

	private int getUsbModeClass(int mode)
	{
		if (mode > USB_MODE_NONE) {
			Log.w("UsbService", "getUsbModeClass("+ String.valueOf(mode) +") Unknown mode !");
			mode = mCurrentUsbMode;
		}
		return checkUsbMode(mode);
	}

	private int checkUsbMode(int mode)
	{
		int checked = mode;
		if (mode < 0 || mode > USB_MODE_NONE) {
			Log.w("UsbService", "checkUsbMode(" + String.valueOf(mode) + ") mode unknown !");
			if (mCurrentUsbMode < 0 || mCurrentUsbMode >= USB_MODE_NONE)
				checked = USB_MODE_NONE;
			else
				checked = mCurrentUsbMode;
		}
		return checked;
	}

	private String getUsbModeString(int mode)
	{
		return mUsbModeString[checkUsbMode(mode)];
	}

	private String getUsbModeSwitchCmd(int mode)
	{
		mode = checkUsbMode(mode);
		return mUsbModeSwitchCmdMapping[mode];
	}

	private String getUsbModeSwitchCmdADB(int mode)
	{
		mode = checkUsbMode(mode);
		return mUsbModeSwitchCmdADBMapping[mode];
	}

	private void handleAtCmdMtpDevClosed()
	{
		Log.d("UsbService", "handleAtCmdMtpDevClosed()");

		if ((mUsbState != USB_STATE_SWITCH_DEVNOD_CLOSE) && (mUsbState != USB_STATE_DETACH_DEVNOD_CLOSE)
				&& (mUsbState != USB_STATE_ATTACH_DEVNOD_CLOSE))
		{
			atcmd_service_stopped = false;
			mtp_service_stopped = false;
			rndis_service_stopped = false;
		}

		ReadCurrentUsbMode();
		int usbModeClass = getUsbModeClass(mCurrentUsbMode);

		if (usbModeClass == USB_MODE_NGP)
		{
			if (!atcmd_service_stopped)
				return;

			StopWaitDevNodeClosedTimer();
			atcmd_service_stopped = false;
			mUsbEvent = "usb_devnode_closed";
			UsbEventHandler(mUsbEvent);
		}
		else if (usbModeClass == USB_MODE_MTP)
		{
			if (!mtp_service_stopped)
				return;

			StopWaitDevNodeClosedTimer();
			mtp_service_stopped = false;
			mUsbEvent = "usb_devnode_closed";
			UsbEventHandler(mUsbEvent);
		}
		else if (usbModeClass == USB_MODE_RNDIS)
		{
			if (!rndis_service_stopped)
				return;

			StopWaitDevNodeClosedTimer();
			rndis_service_stopped = false;
			mUsbEvent = "usb_devnode_closed";
			UsbEventHandler(mUsbEvent);
		}
		else if ((usbModeClass == USB_MODE_MODEM) && atcmd_service_stopped && mtp_service_stopped)
		{
			StopWaitDevNodeClosedTimer();
			atcmd_service_stopped = false;
			mtp_service_stopped = false;
			mUsbEvent = "usb_devnode_closed";
			UsbEventHandler(mUsbEvent);
		}
	}

	private void handleUsbModeSwitchFromPhoneUnlock()
	{
		Log.d("UsbService", "handleUsbModeSwitchFromPhoneUnlock()");
		//HID mode removed
	}

	private void sendUsblanDownIntent()
	{
		Log.d("UsbService", "sendUsblanDownIntent()");

		if (pending_usblan_intent)
		{
			Intent intent = new Intent();
			intent.setAction("com.motorola.intent.action.USBLANDOWN");
			sendBroadcast(intent);
			pending_usblan_intent = false;
		}
	}

	private void sendUsblanUpIntent()
	{
		Log.d("UsbService", "sendUsblanUpIntent()");

		if (mCurrentUsbMode == USB_MODE_NGP)
		{
			Intent intent = new Intent();
			intent.setAction("com.motorola.intent.action.USBLANUP");
			sendBroadcast(intent);
			pending_usblan_intent = true;
		}
	}

	private void setUsbConnectionNotificationVisibility(boolean visible, boolean forceDefaultSound)
	{
		Log.d("UsbService", "setUsbConnectionNotificationVisibility()");
		NotificationManager myNotificationManager = (NotificationManager)getSystemService("notification");

		if (myNotificationManager != null)
		{
			if (mUsbConnectionNotification == null)
			{
				mUsbConnectionNotification = new Notification();
				mUsbConnectionNotification.icon = com.android.internal.R.drawable.stat_sys_data_usb;
				mUsbConnectionNotification.when = 0;
				mUsbConnectionNotification.flags = Notification.FLAG_ONGOING_EVENT;
			}

			if (forceDefaultSound)
				mUsbConnectionNotification.defaults = mUsbConnectionNotification.defaults | Notification.DEFAULT_SOUND;
			else
				mUsbConnectionNotification.defaults = mUsbConnectionNotification.defaults & (~Notification.DEFAULT_SOUND);


			mUsbConnectionNotification.tickerText = getString(R.string.usb_selection_notification_title);
			ReadCurrentUsbMode();

			if (mCurrentUsbMode == USB_MODE_MODEM)
			{
				mUsbConnectionNotification.setLatestEventInfo(this, getString(R.string.usb_selection_notification_title),
					getString(R.string.usb_selection_notification_message_for_modem),
					createUsbModeSelectionDialogIntent());
			}
			else
			{
				mUsbConnectionNotification.setLatestEventInfo(this, getString(R.string.usb_selection_notification_title),
					getString(R.string.usb_selection_notification_message),
					createUsbModeSelectionDialogIntent());
			}

			if (visible)
				myNotificationManager.notify(mUsbConnectionNotification.icon, mUsbConnectionNotification);
			else
				myNotificationManager.cancel(mUsbConnectionNotification.icon);
		}
	}

	private void emitReconfigurationIntent(boolean connected)
	{
		Intent reconfigureIntent = new Intent("com.android.internal.usb.reconfigured");
		reconfigureIntent.putExtra("connected", connected);
		sendBroadcast(reconfigureIntent);
	}

	private void setUsbModeFromAtCmd(int mode)
	{
		Log.d("UsbService", "setUsbModeFromAtCmd(" + String.valueOf(mode) + ")");
		Log.d("UsbService", " New mode: " + getUsbModeString(mode));

		if (mUsbState == USB_STATE_SERVICE_STARTUP)
		{
			ReadCurrentUsbMode();
			if ((mode != mCurrentUsbMode)
				&& (isNGPAvailable || (mode != USB_MODE_NGP))
				&& (isModemAvailable || (mode != USB_MODE_MODEM)))
			{
				mIsSwitchFrom = USB_SWITCH_FROM_AT_CMD;
				mNewUsbMode = mode;
				mUsbEvent = "usb_switch_from_atcmd";
				UsbEventHandler(mUsbEvent);
			}
		}
	}

	private void setUsbModeFromUI(int mode)
	{
		Log.d("UsbService", "setUsbModeFromUI(" + String.valueOf(mode) + ")");
		Log.d("UsbService", "New mode: " + getUsbModeString(mode));

		mNewUsbMode = mode;

		if (mUsbState == USB_STATE_SERVICE_STARTUP)
		{
			mIsSwitchFrom = USB_SWITCH_FROM_UI;
			sendBroadcast(new Intent("com.motorola.intent.action.SHOW_USB_MODE_SWITCH_TOAST"));
			mUsbEvent = "usb_switch_from_ui";
			UsbEventHandler(mUsbEvent);
		}
		else
		{
			Log.w("UsbService", "not in USB_SERVICE_STARTUP_STATE state");
			Log.d("UsbService", "will show error dialog");
			UsbModeSwitchFail();
		}
	}

	public void handleADBOnOff(boolean enable)
	{
		Log.d("UsbService", "handleADBOnOff()");

		if (mADBEnabled != enable)
		{
			if (mUsbState != USB_STATE_IDLE)
			{
				if ((mUsbState == USB_STATE_SERVICE_STARTUP) && (mADBStatusChangeMissedNumber == 0))
				{
					mADBEnabled = enable;
					mIsSwitchFrom = USB_SWITCH_FROM_ADB;
					mUsbEvent = "usb_switch_from_adb";
					UsbEventHandler(mUsbEvent);
				}
				else
					mADBStatusChangeMissedNumber = mADBStatusChangeMissedNumber + 1;
			}
			else
				mADBEnabled = enable;
		}
	}

	public void handleGetDescriptor()
	{
		Log.d("UsbService", "handleGetDescriptor()");
		mUsbCableAttached = true;

		try
		{
			sendBroadcast(new Intent("com.motorola.intent.action.SHOW_USB_CABLE_ATTACH_TOAST"));
			setUsbConnectionNotificationVisibility(true, true);
			ReadCurrentUsbMode();

			if (mCurrentUsbMode != USB_MODE_MODEM)
				enableInternalDataConnectivity(true);
			else
				enableInternalDataConnectivity(false);

			sendBroadcast(new Intent("com.motorola.intent.action.USB_CABLE_ATTACHED"));
			emitReconfigurationIntent(true);
		}
		catch (IllegalStateException ex)
		{
			Log.d("UsbService", "handleGetDescriptor(), show toast exception");
			SystemClock.sleep(500);
		}
	}

	public void handleStartService(String event)
	{
		Log.d("UsbService", "handleStartService(), received event " + event);

		if (mUsbState == USB_STATE_SERVICE_STARTUP)
		{
			ReadCurrentUsbMode();

			boolean processEvent = false;
			switch(mCurrentUsbMode) {
			case USB_MODE_NGP:
				processEvent = event.equals("usbd_start_ngp");
				break;
			case USB_MODE_MTP:
				processEvent = event.equals("usbd_start_mtp");
				break;
			case USB_MODE_MSC:
				processEvent = event.equals("usbd_start_msc_mount");
				break;
			case USB_MODE_RNDIS:
				processEvent = event.equals("usbd_start_rndis");
				break;
			case USB_MODE_MODEM:
				processEvent = ( event.equals("usbd_start_modem") || event.equals("usbd_start_acm") );
				break;
			}
			if (processEvent) {
				mUsbEvent = "usb_start_service";
				UsbEventHandler(mUsbEvent);
			}
		}
	}

	public void handleUsbCableAttachment()
	{
		Log.d("UsbService", "handleUsbCableAttachment()");
		System.putInt(getContentResolver(), "USB_MODE_FROM_PC", -1);
		mUsbEvent = "usb_cable_inserted";
		UsbEventHandler(mUsbEvent);
	}

	public void handleUsbCableDetachment()
	{
		Log.d("UsbService", "handleUsbCableDetachment()");

		if (mUsbCableAttached)
		{
			mUsbCableAttached = false;
			setUsbConnectionNotificationVisibility(false, false);
			enableInternalDataConnectivity(true);
			sendBroadcast(new Intent("com.motorola.intent.action.USB_CABLE_DETACHED"));
			emitReconfigurationIntent(false);
		}

		mUsbEvent = "usb_cable_removed";
		UsbEventHandler(mUsbEvent);
	}

	public void handleUsbCableEnumerate()
	{
		Log.d("UsbService", "handleUsbCableEnumerate()");
		mUsbCableAttached = true;
		ReadCurrentUsbMode();

		UsbEventHandler("usb_cable_enumerated");
	}

	public void handleUsbModeSwitchComplete(String event)
	{
		Log.d("UsbService", "handleUsbModeSwitchComplete(), received enum event: " + event);

		if ( event.indexOf(":ok") >= 0 )
		{
			mUsbEvent = "usb_device_enum_ok";
			UsbEventHandler(mUsbEvent);
		}
		else if ( event.indexOf(":fail") >= 0 )
		{
			mUsbEvent = "usb_device_enum_fail";
			UsbEventHandler(mUsbEvent);
		}
		else
			Log.d("UsbService", "handleUsbModeSwitchComplete(), but not processed");
	}

	public void handleUsbModeSwitchFromUsbd(String message)
	{
		Log.d("UsbService", "handleUsbModeSwitchFromUsbd(), received auto switch msg:" + message);
		int newUsbMode = checkUsbMode(mCurrentUsbMode);

		if (message.equals("usbd_req_switch_ngp"))
		{
			newUsbMode = USB_MODE_NGP;
			if (mUsbState != USB_STATE_SERVICE_STARTUP)
				return;

			ReadCurrentUsbMode();

			if ( (!isNGPAvailable && (newUsbMode == USB_MODE_NGP))
			  || (!isModemAvailable && (newUsbMode == USB_MODE_MODEM)) )
				return;
		}
		else if (message.equals("usbd_req_switch_mtp"))
		{
			if (!isMtpAvailable)
				return;
			newUsbMode = USB_MODE_MTP;
		}
		else if (message.equals("usbd_req_switch_msc"))
			newUsbMode = USB_MODE_MSC;
		else if (message.equals("usbd_req_switch_modem") || message.equals("usbd_req_switch_acm"))
			newUsbMode = USB_MODE_MODEM;
		else if (message.equals("usbd_req_switch_rndis"))
			newUsbMode = USB_MODE_RNDIS;
		else if (message.equals("usbd_req_switch_none"))
			newUsbMode = USB_MODE_NONE;

		mIsSwitchFrom = USB_SWITCH_FROM_USBD;
		mNewUsbMode = newUsbMode;
		mUsbEvent = "usb_switch_from_usbd";
		UsbEventHandler(mUsbEvent);
	}

	public IBinder onBind(Intent paramIntent)
	{
		return null;
	}

	public void onCreate()
	{
		Log.d("UsbService", "onCreate()");
		super.onCreate();

		// This is not really required in Service, Hided in Layer
		isNGPAvailable = true; //getNGPAvailableFlex();
		isMtpAvailable = true; //getMtpAvailableFlex();
		isModemAvailable = true; //getModemAvailableFlex();

		System.putInt(getContentResolver(), "USB_MODE_FROM_PC", -1);

		IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction("com.motorola.intent.action.USB_ATCMD_DEV_CLOSED");
		intentFilter.addAction("com.motorola.intent.action.USB_MTP_EXIT_OK");
		intentFilter.addAction("android.keyguard.intent.SHOW");
		intentFilter.addAction("android.keyguard.intent.HIDE");
		intentFilter.addAction("com.motorola.intent.action.SHOW_USB_CABLE_ATTACH_TOAST");
		intentFilter.addAction("com.motorola.intent.action.SHOW_USB_MODE_SWITCH_TOAST");
		intentFilter.addAction("com.motorola.intent.action.USB_MODE_SWITCH_FROM_UI");
		intentFilter.addAction("com.motorola.intent.action.USB_MODE_SWITCH_FROM_ATCMD");
		registerReceiver(mUsbServiceReceiver, intentFilter);

		IntentFilter mediaIntentFilter = new IntentFilter();
		mediaIntentFilter.addAction("android.intent.action.MEDIA_UNMOUNTED");
		mediaIntentFilter.addAction("android.intent.action.MEDIA_MOUNTED");
		mediaIntentFilter.addAction("android.intent.action.MEDIA_SHARED");
		mediaIntentFilter.addAction("android.intent.action.MEDIA_UNMOUNTABLE");
		mediaIntentFilter.addDataScheme("file");
		registerReceiver(mUsbServiceReceiver, mediaIntentFilter);

		ReadCurrentUsbMode();

		mUsbListener = new UsbListener(this);
		new Thread(mUsbListener, UsbListener.class.getName()).start();
		mUEventObserver.startObserving("DEVPATH=/devices/virtual/misc/usbnet_enable");
	}

	public void onDestroy()
	{
		onDestroy();
		unregisterReceiver(mUsbServiceReceiver);
	}
}
