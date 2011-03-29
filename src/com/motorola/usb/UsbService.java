//Motorola USB.apk decompiled by Skrilax_CZ

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
	private static final int[] mUsbModeClassMapping = new int[]{ 3, 1, 2, 4, 4 };
	private static final String[] mUsbModeString;
	private static final String[] mUsbModeSwitchCmdADBMapping;
	private static final String[] mUsbModeSwitchCmdMapping;
	private static final String[] mUsbStateString;
	private boolean atcmd_service_stopped = false;
	private boolean mADBEnabled = false;
	private int mADBStatusChangeMissedNumber = 0;
	private int mCurrentUsbMode = 2;
	private boolean mIsHIDMode = false;
	private int mIsSwitchFrom = -1;
	private BroadcastReceiver mMediaMountedReceiver;
	private boolean mMediaMountedReceiverRegistered = false;
	private int mNewUsbMode = 2;
	private ITelephony mPhoneService = null;
	private UEventObserver mUEventObserver;
	private boolean mUsbCableAttached = false;
	private Notification mUsbConnectionNotification;
	private String mUsbEvent;
	private UsbListener mUsbListener;
	private BroadcastReceiver mUsbServiceReceiver;
	private int mUsbState = 0;
	private Timer mWait4DevCloseTimer = null;
	private boolean mtp_service_stopped = false;

	static
	{
		mUsbModeSwitchCmdMapping = new String[5];
		mUsbModeSwitchCmdMapping[0] = "usb_mode_ngp";
		mUsbModeSwitchCmdMapping[1] = "usb_mode_mtp";
		mUsbModeSwitchCmdMapping[2] = "usb_mode_msc";
		mUsbModeSwitchCmdMapping[3] = "usb_mode_ngp";
		mUsbModeSwitchCmdMapping[4] = "usb_mode_msc";

		mUsbModeSwitchCmdADBMapping = new String[5];
		mUsbModeSwitchCmdADBMapping[0] = "usb_mode_ngp_adb";
		mUsbModeSwitchCmdADBMapping[1] = "usb_mode_mtp_adb";
		mUsbModeSwitchCmdADBMapping[2] = "usb_mode_msc_adb";
		mUsbModeSwitchCmdADBMapping[3] = "usb_mode_ngp_adb";
		mUsbModeSwitchCmdADBMapping[4] = "usb_mode_msc_adb";

		mUsbModeString = new String[5];
		mUsbModeString[0] = "Motorola Phone Tools";
		mUsbModeString[1] = "Windows Media Sync";
		mUsbModeString[2] = "Memory Card Management";
		mUsbModeString[3] = "Phone as Modem";
		mUsbModeString[4] = "None";

		mUsbStateString = new String[6];
		mUsbStateString[0] = "USB Idle State";
		mUsbStateString[1] = "USB Wait Device Enum State";
		mUsbStateString[2] = "USB Service Startup State";
		mUsbStateString[3] = "USB In-Switch Wait DevNod Close State";
		mUsbStateString[4] = "USB Detach Wait DevNod Close State";
		mUsbStateString[5] = "USB Attach Wait DevNod Close State";
	}

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
					atcmd_service_stopped = true;
					handleAtCmdMtpDevClosed();
				}
				else if (action.equals("com.motorola.intent.action.USB_MTP_EXIT_OK"))
				{
					mtp_service_stopped = true;
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
					
					try
					{
						index = Integer.valueOf(indexStr);
						if (index < 0 || index > 4)
							index = 2;
					}
					catch(Exception ex)
					{
						index = 2;
					}
					
					setUsbModeFromUI(index);
				}
				else if(action.equals("com.motorola.intent.action.USB_MODE_SWITCH_FROM_ATCMD"))
				{
					String indexStr = intent.getStringExtra("USB_MODE_INDEX");
					int index;
					
					try
					{
						index = Integer.valueOf(indexStr);
						if (index < 0 || index > 4)
							index = 2;
					}
					catch(Exception ex)
					{
						index = 2;
					}
					
					setUsbModeFromAtCmd(index);
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
					
					if(mUsbState == 2)
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
		
		switch (mCurrentUsbMode)
		{
			case 0:
				StartAtCmdService();
				break;
				
			case 1:
				StartMtpService();
				break;
			
			case 2:
				EnterMassStorageMode();
				break;
				
			case 3:
				StartAtCmdService();
				StartMtpService();
				break;
		}
	}

	private void DeviceEnumPreAction()
	{
		Log.d("UsbService", "DeviceEnumPreAction()");
		ReadCurrentUsbMode();
		
		switch (mCurrentUsbMode)
		{
			case 0:
				StartWaitDevNodeClosedTimer();
				StopAtCmdService();
				break;
				
			case 1:
				StartWaitDevNodeClosedTimer();
				StopMtpService();
				break;
				
			case 2:
				ExitMassStorageMode();
				mUsbEvent = "usb_devnode_closed";
				UsbEventHandler(mUsbEvent);
				break;
			
			case 3:
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
			catch (RemoteException localRemoteException)
			{
				// ???????
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
			try
			{
				mountService.setUsbMassStorageEnabled(false);      
			}
			catch (RemoteException localRemoteException)
			{
				// ???????
			}
			
			sendBroadcast(new Intent("com.motorola.intent.action.USB_EXIT_MSC_MODE"));
		}
	}

	private void ReadCurrentUsbMode()
	{
		Log.d("UsbService", "ReadCurrentUsbMode()");
		
		try
		{
			int modeFromPC = System.getInt(getContentResolver(), "USB_MODE_FROM_PC");
			
			if (modeFromPC == -1)
			{
				try
				{
					mCurrentUsbMode = System.getInt(getContentResolver(), "USB_SETTING");
					Log.d("UsbService", "Current Usb Mode: " + mUsbModeString[mCurrentUsbMode]);
				}
				catch (SettingNotFoundException ex)
				{
					mCurrentUsbMode = SystemProperties.getInt("ro.default_usb_mode", 0);
					Log.d("UsbService", "read usb setting exception");
					System.putInt(getContentResolver(), "USB_SETTING", mCurrentUsbMode);
				}
			}
			else
			{
				mCurrentUsbMode = modeFromPC;
				Log.d("UsbService", "Current Usb Mode: " + mUsbModeString[mCurrentUsbMode]); 
			}
		}
		catch (SettingNotFoundException ex)
		{
			try
			{
				mCurrentUsbMode = System.getInt(getContentResolver(), "USB_SETTING");
				Log.d("UsbService", "Current Usb Mode: " + mUsbModeString[mCurrentUsbMode]);
			}
			catch (SettingNotFoundException ex2)
			{
				mCurrentUsbMode = SystemProperties.getInt("ro.default_usb_mode", 0);
				Log.d("UsbService", "read usb setting exception");
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
			startService(new Intent("com.motorola.intent.action.USB_MTP_CONNECTION"));
		}
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
			case 0:
				if (!event.equals("usb_cable_inserted"))
				{
					if (event.equals("usb_cable_enumerated"))
					{
						Log.d("UsbService", "Idle state, receive USB_CABLE_ENUMERATE_EVENT.");
						mUsbState = 2;
						mIsSwitchFrom = -1;
					}
				}
				else
				{
					mUsbState = 1;
					
					if (!mIsHIDMode)
					{
						if (mADBEnabled)
							mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmdADB(mCurrentUsbMode));
						else
							mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmd(mCurrentUsbMode));
					}
					else 
					{
						if (mADBEnabled)
							mUsbListener.sendUsbModeSwitchCmd("usb_mode_adb");
						else
							mUsbListener.sendUsbModeSwitchCmd("usb_mode_hid");
					}
				}
				break;
				
			case 1:
				if (event.equals("usb_cable_removed"))
				{
					if (mIsSwitchFrom == 0)
						UsbModeSwitchFail();
						
					mUsbListener.sendUsbModeSwitchCmd("usb_unload_driver");
					mUsbState = 0;
					mIsSwitchFrom = -1;
				}
				else if (event.equals("usb_device_enum_ok"))
				{
					if (mIsSwitchFrom == 0)
					{		
						WriteNewUsbMode(mNewUsbMode);
						UsbModeSwitchSuccess();
						System.putInt(getContentResolver(), "USB_MODE_FROM_PC", -1);			
					}
					else if(mIsSwitchFrom == 3 || mIsSwitchFrom == 1)
						System.putInt(getContentResolver(), "USB_MODE_FROM_PC", mNewUsbMode);
					
					ReadCurrentUsbMode();
					
					if(mIsSwitchFrom != -1)
					{
						setUsbConnectionNotificationVisibility(true, false);
						
						if (mCurrentUsbMode == 3)
							enableInternalDataConnectivity(false);
						else
							enableInternalDataConnectivity(true);

										
						if (mADBStatusChangeMissedNumber != 0)
						{
							mADBStatusChangeMissedNumber = mADBStatusChangeMissedNumber - 1;
							mADBEnabled = !mADBEnabled;
							mIsSwitchFrom = 2;
							mUsbEvent = "usb_switch_from_adb";
							mUsbState = 2;
							UsbEventHandler(mUsbEvent);
						}
						else
						{
							mUsbState = 2;
							mIsSwitchFrom = -1;
						}	
					}
				}
				break;
				
			case 2:
				if(event.equals("usb_start_service"))
				{
					DeviceEnumPostAction();
					mUsbState = 2;
				} 
				else if(event.equals("usb_cable_removed"))
				{
					mUsbState = 4;
					DeviceEnumPreAction();
				} 
				else if(event.equals("usb_switch_from_ui") || event.equals("usb_switch_from_atcmd") 
							|| event.equals("usb_switch_from_adb") || event.equals("usb_switch_from_usbd") 
							|| event.equals("usb_switch_from_phone_unlock"))
				{
					mUsbState = 3;
					DeviceEnumPreAction();
				}
				break;
				
			case 3:
				if(event.equals("usb_cable_removed"))
				{
					if(mIsSwitchFrom == 0)
						UsbModeSwitchFail();
						
					mIsSwitchFrom = -1;
					mUsbState = 4;
				} 
				else if(event.equals("usb_devnode_closed"))
				{
					mUsbState = 1;
					
					if(mIsSwitchFrom == 0 || mIsSwitchFrom == 1 || mIsSwitchFrom == 3)
					{
						if(mADBEnabled)
							mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmdADB(mNewUsbMode));
						else
							mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmd(mNewUsbMode));
					}
					else if (mIsSwitchFrom == 2 || mIsSwitchFrom == 4)
					{
						if(mADBEnabled)
							mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmdADB(mCurrentUsbMode));
						else
							mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmd(mCurrentUsbMode));
					} 
				}
				break;
			
			case 4:
				if(event.equals("usb_cable_inserted"))
					mUsbState = 5;
				else if(event.equals("usb_devnode_closed"))
				{
					mUsbListener.sendUsbModeSwitchCmd("usb_unload_driver");
					mUsbState = 0;
					mIsSwitchFrom = -1;
				}
				break;
				
			case 5:
				if(event.equals("usb_cable_removed"))
					mUsbState = 4;
				else if(event.equals("usb_devnode_closed"))
				{
					mUsbState = 1;
					
					if(mIsHIDMode)
					{
						if(mADBEnabled)
							mUsbListener.sendUsbModeSwitchCmd("usb_mode_adb");
						else
							mUsbListener.sendUsbModeSwitchCmd("usb_mode_hid");
					} 
					else
					{
						if(mADBEnabled)
							mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmdADB(mCurrentUsbMode));
						else
							mUsbListener.sendUsbModeSwitchCmd(getUsbModeSwitchCmd(mCurrentUsbMode));
					}
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
			case 0:
				myIntent.putExtra("USB_MODE_STRING", getString(R.string.usb_mode_ngp));
				break;
				
			case 1:
				myIntent.putExtra("USB_MODE_STRING", getString(R.string.usb_mode_mtp));
				break;
				
			case 2:
				myIntent.putExtra("USB_MODE_STRING", getString(R.string.usb_mode_msc));
				break;
				
			case 3:
				myIntent.putExtra("USB_MODE_STRING", getString(R.string.usb_mode_modem));
				break;
			
			case 4:
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
		Log.d("UsbService", "enableInternalDataConnectivity()" + String.valueOf(enable));
		getPhoneService();
		
		if (mPhoneService != null)
		{
			if (enable)
			{
				try
				{
					Log.d("UsbService", "enableDataConnectivity()");
					mPhoneService.enableDataConnectivity();
					return;
				}
				catch (RemoteException localRemoteException1)
				{
					Log.d("UsbService", "enableDataConnectivity() failed");
				}
			}
			else
			{
				try
				{
					Log.d("UsbService", "disableDataConnectivity()");
					mPhoneService.disableDataConnectivity();
				}
				catch (RemoteException localRemoteException2)
				{
					Log.d("UsbService", "disableDataConnectivity() failed");
				}
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
			case 0:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_ngp);
				
			case 1:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_mtp);
				
			case 2:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_msc);
				
			case 3:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_modem) + 
					getString(R.string.usb_period) + " " + getString(R.string.usb_toast_phone_data_disabled);
					
			case 4:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_none);
		}
		
		return null;
	}

	private String getToastStringForModeSwitch()
	{
		int i = Log.d("UsbService", "getToastStringForModeSwitch()");
		
		switch (mNewUsbMode)
		{
			case 0:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_ngp);
				
			case 1:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_mtp);
				
			case 2:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_msc);
				
			case 3:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_modem) + 
					getString(R.string.usb_period) + " " + getString(R.string.usb_toast_phone_data_disabled);
					
			case 4:
				return getString(R.string.usb_toast_connecting_to) + " " + getString(R.string.usb_mode_none);
		}
		
		return null;
	}

	private int getUsbModeClass(int value)
	{
		return mUsbModeClassMapping[value];
	}

	private String getUsbModeSwitchCmd(int value)
	{
		return mUsbModeSwitchCmdMapping[value];
	}

	private String getUsbModeSwitchCmdADB(int value)
	{
		return mUsbModeSwitchCmdADBMapping[value];
	}

	private void handleAtCmdMtpDevClosed()
	{
		Log.d("UsbService", "handleAtCmdMtpDevClosed()");
		
		if ((mUsbState != 3) && (mUsbState != 4) && (mUsbState != 5))
		{
			atcmd_service_stopped = false;
			mtp_service_stopped = false;
		}
		
		ReadCurrentUsbMode();
		int usbModeClass = getUsbModeClass(mCurrentUsbMode);
		
		if (usbModeClass == 0)
		{
			if (!atcmd_service_stopped)
				return;
					
			StopWaitDevNodeClosedTimer();
			atcmd_service_stopped = false;
			mUsbEvent = "usb_devnode_closed";
			UsbEventHandler(mUsbEvent);
		}
		else if (usbModeClass == 1)
		{
			if (!mtp_service_stopped)
				return;
				
			StopWaitDevNodeClosedTimer();
			mtp_service_stopped = false;
			mUsbEvent = "usb_devnode_closed";
			UsbEventHandler(mUsbEvent);
		}
		else if ((usbModeClass == 3) && atcmd_service_stopped && mtp_service_stopped)
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
		if (mIsHIDMode && (mUsbState == 2))
		{
			mIsHIDMode = false;
			mIsSwitchFrom = 4;
			mUsbEvent = "usb_switch_from_phone_unlock";
			UsbEventHandler(mUsbEvent);
		}
	}

	private void sendUsblanDownIntent()
	{
		Log.d("UsbService", "sendUsblanDownIntent()");
		Intent intent = new Intent();
		intent.setAction("com.motorola.intent.action.USBLANDOWN");
		sendBroadcast(intent);
	}

	private void sendUsblanUpIntent()
	{
		Log.d("UsbService", "sendUsblanDownIntent()");
		Intent intent = new Intent();
		intent.setAction("com.motorola.intent.action.USBLANUP");
		sendBroadcast(intent);
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
				
			if (mCurrentUsbMode == 3)
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

	private void setUsbModeFromAtCmd(int mode)
	{
		Log.d("UsbService", "setUsbModeFromAtCmd()");
		Log.d("UsbService", "New mode: " + mUsbModeString[mode]);
		
		if (mUsbState == 2)
		{
			ReadCurrentUsbMode();
			if ((mode != mCurrentUsbMode) && ((getNGPAvailableFlex()) || (mode != 0)) && ((getModemAvailableFlex()) || (mode != 3)))
			{
				mIsSwitchFrom = 1;
				mNewUsbMode = mode;
				mUsbEvent = "usb_switch_from_atcmd";
				UsbEventHandler(mUsbEvent);
			}
		}
	}

	private void setUsbModeFromUI(int mode)
	{
		Log.d("UsbService", "setUsbModeFromUI()");
		Log.d("UsbService", "New mode: " + mUsbModeString[mode]);
		
		mNewUsbMode = mode;
		
		if (mUsbState == 2)
		{
			mIsSwitchFrom = 0;
			sendBroadcast(new Intent("com.motorola.intent.action.SHOW_USB_MODE_SWITCH_TOAST"));
			mUsbEvent = "usb_switch_from_ui";
			UsbEventHandler(mUsbEvent);
		}
		else
		{
			Log.d("UsbService", "not in USB_SERVICE_STARTUP_STATE state");
			Log.d("UsbService", "will show error dialog");
			UsbModeSwitchFail();
		}
	}

	public void handleADBOnOff(boolean enable)
	{
		Log.d("UsbService", "handleADBOnOff()");

		if (mADBEnabled != enable)
		{
			if (mUsbState != 0)
			{
				if ((mUsbState == 2) && (mADBStatusChangeMissedNumber == 0))
				{
					mADBEnabled = enable;
					mIsSwitchFrom = 2;
					mUsbEvent = "usb_switch_from_adb";
					UsbEventHandler(mUsbEvent);
				}
				else
					mADBStatusChangeMissedNumber = mADBStatusChangeMissedNumber + 1;   	
			}
		}
		else
			mADBEnabled = enable;

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
			
			if (mCurrentUsbMode != 3)
				enableInternalDataConnectivity(true);
			else   
				enableInternalDataConnectivity(false);
				
			sendBroadcast(new Intent("com.motorola.intent.action.USB_CABLE_ATTACHED"));
		}
		catch (IllegalStateException ex)
		{
			Log.d("UsbService", "handleGetDescriptor(), show toast exception");
			SystemClock.sleep(500);
		}
	}

	public void handleStartService(String event)
	{
		Log.d("UsbService", "handleStartService(), received event" + event);
		
		if (mUsbState == 2)
		{
			ReadCurrentUsbMode();
			if (((event.equals("usbd_start_ngp")) && (mCurrentUsbMode == 0)) || ((event.equals("usbd_start_mtp")) && (mCurrentUsbMode == 1)) || ((event.equals("usbd_start_msc_mount")) && (mCurrentUsbMode == 2)) || ((event.equals("usbd_start_acm")) && (mCurrentUsbMode == 3)))
			{
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
		mIsHIDMode = false;
		
		if (mUsbCableAttached)
		{
			mUsbCableAttached = false;
			setUsbConnectionNotificationVisibility(false, false);
			enableInternalDataConnectivity(true);
			sendBroadcast(new Intent("com.motorola.intent.action.USB_CABLE_DETACHED"));
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
		
		if (event.equals("usb_mode_ngp:ok") || event.equals("usb_mode_mtp:ok")
				|| event.equals("usb_mode_msc:ok") || event.equals("usb_mode_modem:ok")
				|| event.equals("usb_mode_hid:ok"))
		{
			mUsbEvent = "usb_device_enum_ok";
			UsbEventHandler(mUsbEvent);
		}
		else if (event.equals("usb_mode_ngp:fail") || event.equals("usb_mode_mtp:fail")
				|| event.equals("usb_mode_msc:fail") || event.equals("usb_mode_modem:fail")
				|| event.equals("usb_mode_hid:fail"))
		{
			mUsbEvent = "usb_device_enum_fail";
			UsbEventHandler(mUsbEvent);
		}
		else  
			Log.d("UsbService", "handleUsbModeSwitchComplete(), Received upexpected event: " + event);
	}

	public void handleUsbModeSwitchFromUsbd(String message)
	{
		Log.d("UsbService", "handleUsbModeSwitchFromUsbd(), received auto switch msg:" + message);
		int newUsbMode = 2;
		
		if (message.equals("usbd_req_switch_ngp"))
		{
			newUsbMode = 0;
			if (mUsbState != 2)
				return;
			
			ReadCurrentUsbMode();
			
			if ((!getNGPAvailableFlex() && (newUsbMode == 0)) 
					|| (!getModemAvailableFlex() && (newUsbMode == 3)))
				return;
		}
		else if (message.equals("usbd_req_switch_mtp"))
			newUsbMode = 1;
		else if (message.equals("usbd_req_switch_msc"))
			newUsbMode = 2;
		else if (message.equals("usbd_req_switch_modem"))
			newUsbMode = 3;
		else if (message.equals("usbd_req_switch_none"))
			newUsbMode = 4;
		

		mIsSwitchFrom = 3;
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
