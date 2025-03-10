/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.telephony;

import static com.android.internal.telephony.TelephonyStatsLog.CELLULAR_SERVICE_STATE__FOLD_STATE__STATE_UNKNOWN;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.app.PropertyInvalidatedCache;
import android.app.admin.DevicePolicyManager;
import android.app.usage.NetworkStatsManager;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkPolicyManager;
import android.net.vcn.VcnManager;
import android.net.vcn.VcnNetworkPolicyResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.RegistrantList;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.UserManager;
import android.permission.LegacyPermissionManager;
import android.provider.BlockedNumberContract;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.CellIdentity;
import android.telephony.CellLocation;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyRegistryManager;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.euicc.EuiccManager;
import android.telephony.ims.ImsCallProfile;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.testing.TestableLooper;
import android.util.Log;
import android.util.Singleton;

import com.android.ims.ImsCall;
import com.android.ims.ImsEcbm;
import com.android.ims.ImsManager;
import com.android.internal.telephony.analytics.TelephonyAnalytics;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;
import com.android.internal.telephony.cdma.EriManager;
import com.android.internal.telephony.data.AccessNetworksManager;
import com.android.internal.telephony.data.CellularNetworkValidator;
import com.android.internal.telephony.data.DataConfigManager;
import com.android.internal.telephony.data.DataNetworkController;
import com.android.internal.telephony.data.DataProfileManager;
import com.android.internal.telephony.data.DataRetryManager;
import com.android.internal.telephony.data.DataServiceManager;
import com.android.internal.telephony.data.DataSettingsManager;
import com.android.internal.telephony.data.LinkBandwidthEstimator;
import com.android.internal.telephony.data.PhoneSwitcher;
import com.android.internal.telephony.domainselection.DomainSelectionResolver;
import com.android.internal.telephony.emergency.EmergencyNumberTracker;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.imsphone.ImsExternalCallTracker;
import com.android.internal.telephony.imsphone.ImsNrSaModeHandler;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.imsphone.ImsPhoneCallTracker;
import com.android.internal.telephony.metrics.DefaultNetworkMonitor;
import com.android.internal.telephony.metrics.DeviceStateHelper;
import com.android.internal.telephony.metrics.ImsStats;
import com.android.internal.telephony.metrics.MetricsCollector;
import com.android.internal.telephony.metrics.PersistAtomsStorage;
import com.android.internal.telephony.metrics.ServiceStateStats;
import com.android.internal.telephony.metrics.SmsStats;
import com.android.internal.telephony.metrics.VoiceCallSessionStats;
import com.android.internal.telephony.satellite.SatelliteController;
import com.android.internal.telephony.security.CellularIdentifierDisclosureNotifier;
import com.android.internal.telephony.security.CellularNetworkSecuritySafetySource;
import com.android.internal.telephony.security.NullCipherNotifier;
import com.android.internal.telephony.subscription.SubscriptionManagerService;
import com.android.internal.telephony.test.SimulatedCommands;
import com.android.internal.telephony.test.SimulatedCommandsVerifier;
import com.android.internal.telephony.uicc.IccCardStatus;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IsimUiccRecords;
import com.android.internal.telephony.uicc.PinStorage;
import com.android.internal.telephony.uicc.RuimRecords;
import com.android.internal.telephony.uicc.SIMRecords;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccPort;
import com.android.internal.telephony.uicc.UiccProfile;
import com.android.internal.telephony.uicc.UiccSlot;
import com.android.server.pm.permission.LegacyPermissionManagerService;

import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public abstract class TelephonyTest {
    protected static String TAG;

    private static final int MAX_INIT_WAIT_MS = 30000; // 30 seconds

    private static final EmergencyNumber SAMPLE_EMERGENCY_NUMBER =
            new EmergencyNumber("911", "us", "30",
                    EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED,
            new ArrayList<String>(), EmergencyNumber.EMERGENCY_NUMBER_SOURCE_NETWORK_SIGNALING,
            EmergencyNumber.EMERGENCY_CALL_ROUTING_NORMAL);

    private static final Field MESSAGE_QUEUE_FIELD;
    private static final Field MESSAGE_WHEN_FIELD;
    private static final Field MESSAGE_NEXT_FIELD;

    static {
        try {
            MESSAGE_QUEUE_FIELD = MessageQueue.class.getDeclaredField("mMessages");
            MESSAGE_QUEUE_FIELD.setAccessible(true);
            MESSAGE_WHEN_FIELD = Message.class.getDeclaredField("when");
            MESSAGE_WHEN_FIELD.setAccessible(true);
            MESSAGE_NEXT_FIELD = Message.class.getDeclaredField("next");
            MESSAGE_NEXT_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Failed to initialize TelephonyTest", e);
        }
    }

    // Mocked classes
    protected FeatureFlags mFeatureFlags;
    protected GsmCdmaPhone mPhone;
    protected GsmCdmaPhone mPhone2;
    protected ImsPhone mImsPhone;
    protected ServiceStateTracker mSST;
    protected EmergencyNumberTracker mEmergencyNumberTracker;
    protected GsmCdmaCallTracker mCT;
    protected ImsPhoneCallTracker mImsCT;
    protected UiccController mUiccController;
    protected UiccProfile mUiccProfile;
    protected UiccSlot mUiccSlot;
    protected CallManager mCallManager;
    protected PhoneNotifier mNotifier;
    protected TelephonyComponentFactory mTelephonyComponentFactory;
    protected CdmaSubscriptionSourceManager mCdmaSSM;
    protected RegistrantList mRegistrantList;
    protected IccPhoneBookInterfaceManager mIccPhoneBookIntManager;
    protected ImsManager mImsManager;
    protected DataNetworkController mDataNetworkController;
    protected DataRetryManager mDataRetryManager;
    protected DataSettingsManager mDataSettingsManager;
    protected DataConfigManager mDataConfigManager;
    protected DataProfileManager mDataProfileManager;
    protected DisplayInfoController mDisplayInfoController;
    protected GsmCdmaCall mGsmCdmaCall;
    protected ImsCall mImsCall;
    protected ImsEcbm mImsEcbm;
    protected SubscriptionManagerService mSubscriptionManagerService;
    protected ServiceState mServiceState;
    protected IPackageManager.Stub mMockPackageManager;
    protected LegacyPermissionManagerService mMockLegacyPermissionManager;
    protected SimulatedCommandsVerifier mSimulatedCommandsVerifier;
    protected InboundSmsHandler mInboundSmsHandler;
    protected WspTypeDecoder mWspTypeDecoder;
    protected UiccCardApplication mUiccCardApplication3gpp;
    protected UiccCardApplication mUiccCardApplication3gpp2;
    protected UiccCardApplication mUiccCardApplicationIms;
    protected SIMRecords mSimRecords;
    protected SignalStrengthController mSignalStrengthController;
    protected RuimRecords mRuimRecords;
    protected IsimUiccRecords mIsimUiccRecords;
    protected ProxyController mProxyController;
    protected PhoneSwitcher mPhoneSwitcher;
    protected Singleton<IActivityManager> mIActivityManagerSingleton;
    protected IActivityManager mIActivityManager;
    protected IIntentSender mIIntentSender;
    protected IBinder mIBinder;
    protected SmsStorageMonitor mSmsStorageMonitor;
    protected SmsUsageMonitor mSmsUsageMonitor;
    protected PackageInfo mPackageInfo;
    protected ApplicationInfo mApplicationInfo;
    protected EriManager mEriManager;
    protected IBinder mConnMetLoggerBinder;
    protected CarrierSignalAgent mCarrierSignalAgent;
    protected CarrierActionAgent mCarrierActionAgent;
    protected ImsExternalCallTracker mImsExternalCallTracker;
    protected ImsNrSaModeHandler mImsNrSaModeHandler;
    protected AppSmsManager mAppSmsManager;
    protected IccSmsInterfaceManager mIccSmsInterfaceManager;
    protected SmsDispatchersController mSmsDispatchersController;
    protected DeviceStateMonitor mDeviceStateMonitor;
    protected AccessNetworksManager mAccessNetworksManager;
    protected IntentBroadcaster mIntentBroadcaster;
    protected NitzStateMachine mNitzStateMachine;
    protected RadioConfig mMockRadioConfig;
    protected RadioConfigProxy mMockRadioConfigProxy;
    protected LocaleTracker mLocaleTracker;
    protected RestrictedState mRestrictedState;
    protected PhoneConfigurationManager mPhoneConfigurationManager;
    protected CellularNetworkValidator mCellularNetworkValidator;
    protected UiccCard mUiccCard;
    protected UiccPort mUiccPort;
    protected MultiSimSettingController mMultiSimSettingController;
    protected IccCard mIccCard;
    protected NetworkStatsManager mStatsManager;
    protected CarrierPrivilegesTracker mCarrierPrivilegesTracker;
    protected VoiceCallSessionStats mVoiceCallSessionStats;
    protected PersistAtomsStorage mPersistAtomsStorage;
    protected DefaultNetworkMonitor mDefaultNetworkMonitor;
    protected MetricsCollector mMetricsCollector;
    protected SmsStats mSmsStats;
    protected TelephonyAnalytics mTelephonyAnalytics;
    protected SignalStrength mSignalStrength;
    protected WifiManager mWifiManager;
    protected WifiInfo mWifiInfo;
    protected ImsStats mImsStats;
    protected LinkBandwidthEstimator mLinkBandwidthEstimator;
    protected PinStorage mPinStorage;
    protected LocationManager mLocationManager;
    protected CellIdentity mCellIdentity;
    protected CellLocation mCellLocation;
    protected DataServiceManager mMockedWwanDataServiceManager;
    protected DataServiceManager mMockedWlanDataServiceManager;
    protected ServiceStateStats mServiceStateStats;
    protected SatelliteController mSatelliteController;
    protected DeviceStateHelper mDeviceStateHelper;
    protected CellularNetworkSecuritySafetySource mSafetySource;
    protected CellularIdentifierDisclosureNotifier mIdentifierDisclosureNotifier;
    protected DomainSelectionResolver mDomainSelectionResolver;
    protected NullCipherNotifier mNullCipherNotifier;

    // Initialized classes
    protected ActivityManager mActivityManager;
    protected ImsCallProfile mImsCallProfile;
    protected TelephonyManager mTelephonyManager;
    protected TelecomManager mTelecomManager;
    protected TelephonyRegistryManager mTelephonyRegistryManager;
    protected SubscriptionManager mSubscriptionManager;
    protected EuiccManager mEuiccManager;
    protected PackageManager mPackageManager;
    protected ConnectivityManager mConnectivityManager;
    protected AppOpsManager mAppOpsManager;
    protected CarrierConfigManager mCarrierConfigManager;
    protected UserManager mUserManager;
    protected DevicePolicyManager mDevicePolicyManager;
    protected KeyguardManager mKeyguardManager;
    protected VcnManager mVcnManager;
    protected NetworkPolicyManager mNetworkPolicyManager;
    protected SimulatedCommands mSimulatedCommands;
    protected ContextFixture mContextFixture;
    protected Context mContext;
    protected FakeBlockedNumberContentProvider mFakeBlockedNumberContentProvider;
    private final ContentProvider mContentProvider = spy(new ContextFixture.FakeContentProvider());
    private final Object mLock = new Object();
    private boolean mReady;
    protected HashMap<String, IBinder> mServiceManagerMockedServices = new HashMap<>();
    protected Phone[] mPhones;
    protected NetworkRegistrationInfo mNetworkRegistrationInfo =
            new NetworkRegistrationInfo.Builder()
                    .setAccessNetworkTechnology(TelephonyManager.NETWORK_TYPE_LTE)
                    .setRegistrationState(NetworkRegistrationInfo.REGISTRATION_STATE_HOME)
                    .build();
    protected List<TestableLooper> mTestableLoopers = new ArrayList<>();
    protected TestableLooper mTestableLooper;

    private final HashMap<InstanceKey, Object> mOldInstances = new HashMap<>();

    private final List<InstanceKey> mInstanceKeys = new ArrayList<>();

    protected int mIntegerConsumerResult;
    protected Semaphore mIntegerConsumerSemaphore = new Semaphore(0);
    protected  Consumer<Integer> mIntegerConsumer = new Consumer<Integer>() {
        @Override
        public void accept(Integer integer) {
            logd("mIIntegerConsumer: result=" + integer);
            mIntegerConsumerResult =  integer;
            try {
                mIntegerConsumerSemaphore.release();
            } catch (Exception ex) {
                logd("mIIntegerConsumer: Got exception in releasing semaphore, ex=" + ex);
            }
        }
    };

    protected boolean waitForIntegerConsumerResponse(int expectedNumberOfEvents) {
        for (int i = 0; i < expectedNumberOfEvents; i++) {
            try {
                if (!mIntegerConsumerSemaphore.tryAcquire(500 /*Timeout*/, TimeUnit.MILLISECONDS)) {
                    logd("Timeout to receive IIntegerConsumer() callback");
                    return false;
                }
            } catch (Exception ex) {
                logd("waitForIIntegerConsumerResult: Got exception=" + ex);
                return false;
            }
        }
        return true;
    }

    private class InstanceKey {
        public final Class mClass;
        public final String mInstName;
        public final Object mObj;
        InstanceKey(final Class c, final String instName, final Object obj) {
            mClass = c;
            mInstName = instName;
            mObj = obj;
        }

        @Override
        public int hashCode() {
            return (mClass.getName().hashCode() * 31 + mInstName.hashCode()) * 31;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || !(obj instanceof InstanceKey)) {
                return false;
            }

            InstanceKey other = (InstanceKey) obj;
            return (other.mClass == mClass && other.mInstName.equals(mInstName)
                    && other.mObj == mObj);
        }
    }

    protected void waitUntilReady() {
        synchronized (mLock) {
            long now = SystemClock.elapsedRealtime();
            long deadline = now + MAX_INIT_WAIT_MS;
            while (!mReady && now < deadline) {
                try {
                    mLock.wait(MAX_INIT_WAIT_MS);
                } catch (Exception e) {
                    fail("Telephony tests failed to initialize: e=" + e);
                }
                now = SystemClock.elapsedRealtime();
            }
            if (!mReady) {
                fail("Telephony tests failed to initialize");
            }
        }
    }

    protected void setReady(boolean ready) {
        synchronized (mLock) {
            mReady = ready;
            mLock.notifyAll();
        }
    }

    protected synchronized void replaceInstance(final Class c, final String instanceName,
                                                final Object obj, final Object newValue)
            throws Exception {
        Field field = c.getDeclaredField(instanceName);
        field.setAccessible(true);

        InstanceKey key = new InstanceKey(c, instanceName, obj);
        if (!mOldInstances.containsKey(key)) {
            mOldInstances.put(key, field.get(obj));
            mInstanceKeys.add(key);
        }
        field.set(obj, newValue);
    }

    protected static <T> T getPrivateField(Object object, String fieldName, Class<T> fieldType)
            throws Exception {

        Class<?> clazz = object.getClass();
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);

        return fieldType.cast(field.get(object));
    }

    protected synchronized void restoreInstance(final Class c, final String instanceName,
                                                final Object obj) throws Exception {
        InstanceKey key = new InstanceKey(c, instanceName, obj);
        if (mOldInstances.containsKey(key)) {
            Field field = c.getDeclaredField(instanceName);
            field.setAccessible(true);
            field.set(obj, mOldInstances.get(key));
            mOldInstances.remove(key);
            mInstanceKeys.remove(key);
        }
    }

    protected synchronized void restoreInstances() throws Exception {
        for (int i = mInstanceKeys.size() - 1; i >= 0; i--) {
            InstanceKey key = mInstanceKeys.get(i);
            Field field = key.mClass.getDeclaredField(key.mInstName);
            field.setAccessible(true);
            field.set(key.mObj, mOldInstances.get(key));
        }

        mInstanceKeys.clear();
        mOldInstances.clear();
    }

    // TODO: Unit tests that do not extend TelephonyTest or ImsTestBase should enable strict mode
    //   by calling this method.
    public static void enableStrictMode() {
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectIncorrectContextUse()
                .detectLeakedRegistrationObjects()
                .detectUnsafeIntentLaunch()
                .detectActivityLeaks()
                .penaltyLog()
                .penaltyDeath()
                .build());
    }

    protected void setUp(String tag) throws Exception {
        TAG = tag;
        enableStrictMode();
        mFeatureFlags = Mockito.mock(FeatureFlags.class);
        mPhone = Mockito.mock(GsmCdmaPhone.class);
        mPhone2 = Mockito.mock(GsmCdmaPhone.class);
        mImsPhone = Mockito.mock(ImsPhone.class);
        mSST = Mockito.mock(ServiceStateTracker.class);
        mEmergencyNumberTracker = Mockito.mock(EmergencyNumberTracker.class);
        mCT = Mockito.mock(GsmCdmaCallTracker.class);
        mImsCT = Mockito.mock(ImsPhoneCallTracker.class);
        mUiccController = Mockito.mock(UiccController.class);
        mUiccProfile = Mockito.mock(UiccProfile.class);
        mUiccSlot = Mockito.mock(UiccSlot.class);
        mCallManager = Mockito.mock(CallManager.class);
        mNotifier = Mockito.mock(PhoneNotifier.class);
        mTelephonyComponentFactory = Mockito.mock(TelephonyComponentFactory.class);
        mCdmaSSM = Mockito.mock(CdmaSubscriptionSourceManager.class);
        mRegistrantList = Mockito.mock(RegistrantList.class);
        mIccPhoneBookIntManager = Mockito.mock(IccPhoneBookInterfaceManager.class);
        mImsManager = Mockito.mock(ImsManager.class);
        mDataNetworkController = Mockito.mock(DataNetworkController.class);
        mDataRetryManager = Mockito.mock(DataRetryManager.class);
        mDataSettingsManager = Mockito.mock(DataSettingsManager.class);
        mDataConfigManager = Mockito.mock(DataConfigManager.class);
        mDataProfileManager = Mockito.mock(DataProfileManager.class);
        mDisplayInfoController = Mockito.mock(DisplayInfoController.class);
        mGsmCdmaCall = Mockito.mock(GsmCdmaCall.class);
        mImsCall = Mockito.mock(ImsCall.class);
        mImsEcbm = Mockito.mock(ImsEcbm.class);
        mSubscriptionManagerService = Mockito.mock(SubscriptionManagerService.class);
        mServiceState = Mockito.mock(ServiceState.class);
        mMockPackageManager = Mockito.mock(IPackageManager.Stub.class);
        mMockLegacyPermissionManager = Mockito.mock(LegacyPermissionManagerService.class);
        mSimulatedCommandsVerifier = Mockito.mock(SimulatedCommandsVerifier.class);
        mInboundSmsHandler = Mockito.mock(InboundSmsHandler.class);
        mWspTypeDecoder = Mockito.mock(WspTypeDecoder.class);
        mUiccCardApplication3gpp = Mockito.mock(UiccCardApplication.class);
        mUiccCardApplication3gpp2 = Mockito.mock(UiccCardApplication.class);
        mUiccCardApplicationIms = Mockito.mock(UiccCardApplication.class);
        mSimRecords = Mockito.mock(SIMRecords.class);
        mSignalStrengthController = Mockito.mock(SignalStrengthController.class);
        mRuimRecords = Mockito.mock(RuimRecords.class);
        mIsimUiccRecords = Mockito.mock(IsimUiccRecords.class);
        mProxyController = Mockito.mock(ProxyController.class);
        mPhoneSwitcher = Mockito.mock(PhoneSwitcher.class);
        mIActivityManagerSingleton = Mockito.mock(Singleton.class);
        mIActivityManager = Mockito.mock(IActivityManager.class);
        mIIntentSender = Mockito.mock(IIntentSender.class);
        mIBinder = Mockito.mock(IBinder.class);
        mSmsStorageMonitor = Mockito.mock(SmsStorageMonitor.class);
        mSmsUsageMonitor = Mockito.mock(SmsUsageMonitor.class);
        mPackageInfo = Mockito.mock(PackageInfo.class);
        mApplicationInfo = Mockito.mock(ApplicationInfo.class);
        mEriManager = Mockito.mock(EriManager.class);
        mConnMetLoggerBinder = Mockito.mock(IBinder.class);
        mCarrierSignalAgent = Mockito.mock(CarrierSignalAgent.class);
        mCarrierActionAgent = Mockito.mock(CarrierActionAgent.class);
        mImsExternalCallTracker = Mockito.mock(ImsExternalCallTracker.class);
        mImsNrSaModeHandler = Mockito.mock(ImsNrSaModeHandler.class);
        mAppSmsManager = Mockito.mock(AppSmsManager.class);
        mIccSmsInterfaceManager = Mockito.mock(IccSmsInterfaceManager.class);
        mSmsDispatchersController = Mockito.mock(SmsDispatchersController.class);
        mDeviceStateMonitor = Mockito.mock(DeviceStateMonitor.class);
        mAccessNetworksManager = Mockito.mock(AccessNetworksManager.class);
        mIntentBroadcaster = Mockito.mock(IntentBroadcaster.class);
        mNitzStateMachine = Mockito.mock(NitzStateMachine.class);
        mMockRadioConfig = Mockito.mock(RadioConfig.class);
        mMockRadioConfigProxy = Mockito.mock(RadioConfigProxy.class);
        mLocaleTracker = Mockito.mock(LocaleTracker.class);
        mRestrictedState = Mockito.mock(RestrictedState.class);
        mPhoneConfigurationManager = Mockito.mock(PhoneConfigurationManager.class);
        mCellularNetworkValidator = Mockito.mock(CellularNetworkValidator.class);
        mUiccCard = Mockito.mock(UiccCard.class);
        mUiccPort = Mockito.mock(UiccPort.class);
        mMultiSimSettingController = Mockito.mock(MultiSimSettingController.class);
        mIccCard = Mockito.mock(IccCard.class);
        mStatsManager = Mockito.mock(NetworkStatsManager.class);
        mCarrierPrivilegesTracker = Mockito.mock(CarrierPrivilegesTracker.class);
        mVoiceCallSessionStats = Mockito.mock(VoiceCallSessionStats.class);
        mPersistAtomsStorage = Mockito.mock(PersistAtomsStorage.class);
        mDefaultNetworkMonitor = Mockito.mock(DefaultNetworkMonitor.class);
        mMetricsCollector = Mockito.mock(MetricsCollector.class);
        mSmsStats = Mockito.mock(SmsStats.class);
        mTelephonyAnalytics = Mockito.mock(TelephonyAnalytics.class);
        mSignalStrength = Mockito.mock(SignalStrength.class);
        mWifiManager = Mockito.mock(WifiManager.class);
        mWifiInfo = Mockito.mock(WifiInfo.class);
        mImsStats = Mockito.mock(ImsStats.class);
        mLinkBandwidthEstimator = Mockito.mock(LinkBandwidthEstimator.class);
        mPinStorage = Mockito.mock(PinStorage.class);
        mLocationManager = Mockito.mock(LocationManager.class);
        mCellIdentity = Mockito.mock(CellIdentity.class);
        mCellLocation = Mockito.mock(CellLocation.class);
        mMockedWwanDataServiceManager = Mockito.mock(DataServiceManager.class);
        mMockedWlanDataServiceManager = Mockito.mock(DataServiceManager.class);
        mServiceStateStats = Mockito.mock(ServiceStateStats.class);
        mSatelliteController = Mockito.mock(SatelliteController.class);
        mDeviceStateHelper = Mockito.mock(DeviceStateHelper.class);
        mSafetySource = Mockito.mock(CellularNetworkSecuritySafetySource.class);
        mIdentifierDisclosureNotifier = Mockito.mock(CellularIdentifierDisclosureNotifier.class);
        mDomainSelectionResolver = Mockito.mock(DomainSelectionResolver.class);
        mNullCipherNotifier = Mockito.mock(NullCipherNotifier.class);

        doReturn(true).when(mFeatureFlags).minimalTelephonyCdmCheck();

        TelephonyManager.disableServiceHandleCaching();
        PropertyInvalidatedCache.disableForTestMode();
        // For testing do not allow Log.WTF as it can cause test process to crash
        Log.setWtfHandler((tagString, what, system) -> Log.d(TAG, "WTF captured, ignoring. Tag: "
                + tagString + ", exception: " + what));

        mPhones = new Phone[] {mPhone};
        mImsCallProfile = new ImsCallProfile();
        mImsCallProfile.setCallerNumberVerificationStatus(
                ImsCallProfile.VERIFICATION_STATUS_PASSED);
        mSimulatedCommands = new SimulatedCommands();
        mContextFixture = new ContextFixture();
        mContext = mContextFixture.getTestDouble();
        mFakeBlockedNumberContentProvider = new FakeBlockedNumberContentProvider();
        ((MockContentResolver)mContext.getContentResolver()).addProvider(
                BlockedNumberContract.AUTHORITY, mFakeBlockedNumberContentProvider);
        ((MockContentResolver) mContext.getContentResolver()).addProvider(
                Settings.AUTHORITY, mContentProvider);
        ((MockContentResolver) mContext.getContentResolver()).addProvider(
                Telephony.ServiceStateTable.AUTHORITY, mContentProvider);
        replaceContentProvider(mContentProvider);

        Settings.Global.getInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0);

        mServiceManagerMockedServices.put("isub", mSubscriptionManagerService);
        doReturn(mSubscriptionManagerService).when(mSubscriptionManagerService)
                .queryLocalInterface(anyString());

        mPhone.mCi = mSimulatedCommands;
        mCT.mCi = mSimulatedCommands;
        doReturn(mUiccCard).when(mPhone).getUiccCard();
        doReturn(mUiccCard).when(mUiccSlot).getUiccCard();
        doReturn(mUiccCard).when(mUiccController).getUiccCardForPhone(anyInt());
        doReturn(mUiccPort).when(mPhone).getUiccPort();
        doReturn(mUiccProfile).when(mUiccPort).getUiccProfile();

        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mTelecomManager = mContext.getSystemService(TelecomManager.class);
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mTelephonyRegistryManager = (TelephonyRegistryManager) mContext.getSystemService(
            Context.TELEPHONY_REGISTRY_SERVICE);
        mSubscriptionManager = (SubscriptionManager) mContext.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        mEuiccManager = (EuiccManager) mContext.getSystemService(Context.EUICC_SERVICE);
        mConnectivityManager = (ConnectivityManager)
                mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mPackageManager = mContext.getPackageManager();
        mAppOpsManager = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mCarrierConfigManager =
                (CarrierConfigManager) mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mDevicePolicyManager = (DevicePolicyManager) mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        mKeyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mVcnManager = mContext.getSystemService(VcnManager.class);
        mNetworkPolicyManager = mContext.getSystemService(NetworkPolicyManager.class);
        mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);

        //mTelephonyComponentFactory
        doReturn(mTelephonyComponentFactory).when(mTelephonyComponentFactory).inject(anyString());
        doReturn(mSST).when(mTelephonyComponentFactory)
                .makeServiceStateTracker(nullable(GsmCdmaPhone.class),
                        nullable(CommandsInterface.class), nullable(FeatureFlags.class));
        doReturn(mEmergencyNumberTracker).when(mTelephonyComponentFactory)
                .makeEmergencyNumberTracker(nullable(Phone.class),
                        nullable(CommandsInterface.class), any(FeatureFlags.class));
        doReturn(getTestEmergencyNumber()).when(mEmergencyNumberTracker)
                .getEmergencyNumber(any());
        doReturn(mUiccProfile).when(mTelephonyComponentFactory)
                .makeUiccProfile(nullable(Context.class), nullable(CommandsInterface.class),
                        nullable(IccCardStatus.class), anyInt(), nullable(UiccCard.class),
                        nullable(Object.class), any(FeatureFlags.class));
        doReturn(mCT).when(mTelephonyComponentFactory)
                .makeGsmCdmaCallTracker(nullable(GsmCdmaPhone.class), any(FeatureFlags.class));
        doReturn(mIccPhoneBookIntManager).when(mTelephonyComponentFactory)
                .makeIccPhoneBookInterfaceManager(nullable(Phone.class));
        doReturn(mDisplayInfoController).when(mTelephonyComponentFactory)
                .makeDisplayInfoController(nullable(Phone.class), any(FeatureFlags.class));
        doReturn(mWspTypeDecoder).when(mTelephonyComponentFactory)
                .makeWspTypeDecoder(nullable(byte[].class));
        doReturn(mImsCT).when(mTelephonyComponentFactory)
                .makeImsPhoneCallTracker(nullable(ImsPhone.class), any(FeatureFlags.class));
        doReturn(mCdmaSSM).when(mTelephonyComponentFactory)
                .getCdmaSubscriptionSourceManagerInstance(nullable(Context.class),
                        nullable(CommandsInterface.class), nullable(Handler.class),
                        anyInt(), nullable(Object.class));
        doReturn(mImsExternalCallTracker).when(mTelephonyComponentFactory)
                .makeImsExternalCallTracker(nullable(ImsPhone.class));
        doReturn(mImsNrSaModeHandler).when(mTelephonyComponentFactory)
                .makeImsNrSaModeHandler(nullable(ImsPhone.class));
        doReturn(mAppSmsManager).when(mTelephonyComponentFactory)
                .makeAppSmsManager(nullable(Context.class));
        doReturn(mCarrierSignalAgent).when(mTelephonyComponentFactory)
                .makeCarrierSignalAgent(nullable(Phone.class));
        doReturn(mCarrierActionAgent).when(mTelephonyComponentFactory)
                .makeCarrierActionAgent(nullable(Phone.class));
        doReturn(mDeviceStateMonitor).when(mTelephonyComponentFactory)
                .makeDeviceStateMonitor(nullable(Phone.class), any(FeatureFlags.class));
        doReturn(mAccessNetworksManager).when(mTelephonyComponentFactory)
                .makeAccessNetworksManager(nullable(Phone.class), any(Looper.class));
        doReturn(mNitzStateMachine).when(mTelephonyComponentFactory)
                .makeNitzStateMachine(nullable(GsmCdmaPhone.class));
        doReturn(mLocaleTracker).when(mTelephonyComponentFactory)
                .makeLocaleTracker(nullable(Phone.class), nullable(NitzStateMachine.class),
                        nullable(Looper.class), any(FeatureFlags.class));
        doReturn(mEriManager).when(mTelephonyComponentFactory)
                .makeEriManager(nullable(Phone.class), anyInt());
        doReturn(mLinkBandwidthEstimator).when(mTelephonyComponentFactory)
                .makeLinkBandwidthEstimator(nullable(Phone.class), any(Looper.class));
        doReturn(mDataProfileManager).when(mTelephonyComponentFactory)
                .makeDataProfileManager(any(Phone.class), any(DataNetworkController.class),
                        any(DataServiceManager.class), any(Looper.class),
                        any(FeatureFlags.class),
                        any(DataProfileManager.DataProfileManagerCallback.class));
        doReturn(mSafetySource).when(mTelephonyComponentFactory)
                .makeCellularNetworkSecuritySafetySource(any(Context.class));
        doReturn(mIdentifierDisclosureNotifier)
                .when(mTelephonyComponentFactory)
                .makeIdentifierDisclosureNotifier(any(CellularNetworkSecuritySafetySource.class));
        doReturn(mNullCipherNotifier)
                .when(mTelephonyComponentFactory)
                .makeNullCipherNotifier(any(CellularNetworkSecuritySafetySource.class));

        //mPhone
        doReturn(mContext).when(mPhone).getContext();
        doReturn(mContext).when(mImsPhone).getContext();
        doReturn(true).when(mPhone).getUnitTestMode();
        doReturn(mUiccProfile).when(mPhone).getIccCard();
        doReturn(mServiceState).when(mPhone).getServiceState();
        doReturn(mServiceState).when(mImsPhone).getServiceState();
        doReturn(mPhone).when(mImsPhone).getDefaultPhone();
        doReturn(true).when(mPhone).isPhoneTypeGsm();
        doReturn(PhoneConstants.PHONE_TYPE_GSM).when(mPhone).getPhoneType();
        doReturn(mCT).when(mPhone).getCallTracker();
        doReturn(mSST).when(mPhone).getServiceStateTracker();
        doReturn(mDeviceStateMonitor).when(mPhone).getDeviceStateMonitor();
        doReturn(mDisplayInfoController).when(mPhone).getDisplayInfoController();
        doReturn(mSignalStrengthController).when(mPhone).getSignalStrengthController();
        doReturn(mEmergencyNumberTracker).when(mPhone).getEmergencyNumberTracker();
        doReturn(mCarrierSignalAgent).when(mPhone).getCarrierSignalAgent();
        doReturn(mCarrierActionAgent).when(mPhone).getCarrierActionAgent();
        doReturn(mAppSmsManager).when(mPhone).getAppSmsManager();
        doReturn(mIccSmsInterfaceManager).when(mPhone).getIccSmsInterfaceManager();
        doReturn(mAccessNetworksManager).when(mPhone).getAccessNetworksManager();
        doReturn(mDataSettingsManager).when(mDataNetworkController).getDataSettingsManager();
        doReturn(mDataNetworkController).when(mPhone).getDataNetworkController();
        doReturn(mDataSettingsManager).when(mPhone).getDataSettingsManager();
        doReturn(mCarrierPrivilegesTracker).when(mPhone).getCarrierPrivilegesTracker();
        doReturn(mSignalStrength).when(mPhone).getSignalStrength();
        doReturn(mVoiceCallSessionStats).when(mPhone).getVoiceCallSessionStats();
        doReturn(mVoiceCallSessionStats).when(mImsPhone).getVoiceCallSessionStats();
        doReturn(mSmsStats).when(mPhone).getSmsStats();
        doReturn(mTelephonyAnalytics).when(mPhone).getTelephonyAnalytics();
        doReturn(mImsStats).when(mImsPhone).getImsStats();
        mIccSmsInterfaceManager.mDispatchersController = mSmsDispatchersController;
        doReturn(mLinkBandwidthEstimator).when(mPhone).getLinkBandwidthEstimator();
        doReturn(mCellIdentity).when(mPhone).getCurrentCellIdentity();
        doReturn(mCellLocation).when(mCellIdentity).asCellLocation();
        doReturn(mDataConfigManager).when(mDataNetworkController).getDataConfigManager();
        doReturn(mDataProfileManager).when(mDataNetworkController).getDataProfileManager();
        doReturn(mDataRetryManager).when(mDataNetworkController).getDataRetryManager();
        doReturn(mCarrierPrivilegesTracker).when(mPhone).getCarrierPrivilegesTracker();
        doReturn(0).when(mPhone).getPhoneId();

        //mUiccController
        doReturn(mUiccCardApplication3gpp).when(mUiccController).getUiccCardApplication(anyInt(),
                eq(UiccController.APP_FAM_3GPP));
        doReturn(mUiccCardApplication3gpp2).when(mUiccController).getUiccCardApplication(anyInt(),
                eq(UiccController.APP_FAM_3GPP2));
        doReturn(mUiccCardApplicationIms).when(mUiccController).getUiccCardApplication(anyInt(),
                eq(UiccController.APP_FAM_IMS));
        doReturn(mUiccCard).when(mUiccController).getUiccCard(anyInt());
        doReturn(mUiccPort).when(mUiccController).getUiccPort(anyInt());

        doAnswer(new Answer<IccRecords>() {
            public IccRecords answer(InvocationOnMock invocation) {
                switch ((Integer) invocation.getArguments()[1]) {
                    case UiccController.APP_FAM_3GPP:
                        return mSimRecords;
                    case UiccController.APP_FAM_3GPP2:
                        return mRuimRecords;
                    case UiccController.APP_FAM_IMS:
                        return mIsimUiccRecords;
                    default:
                        logd("Unrecognized family " + invocation.getArguments()[1]);
                        return null;
                }
            }
        }).when(mUiccController).getIccRecords(anyInt(), anyInt());
        doReturn(new UiccSlot[] {mUiccSlot}).when(mUiccController).getUiccSlots();
        doReturn(mUiccSlot).when(mUiccController).getUiccSlotForPhone(anyInt());
        doReturn(mPinStorage).when(mUiccController).getPinStorage();

        //UiccCardApplication
        doReturn(mSimRecords).when(mUiccCardApplication3gpp).getIccRecords();
        doReturn(mRuimRecords).when(mUiccCardApplication3gpp2).getIccRecords();
        doReturn(mIsimUiccRecords).when(mUiccCardApplicationIms).getIccRecords();

        //mUiccProfile
        doReturn(mSimRecords).when(mUiccProfile).getIccRecords();
        doAnswer(new Answer<IccRecords>() {
            public IccRecords answer(InvocationOnMock invocation) {
                return mSimRecords;
            }
        }).when(mUiccProfile).getIccRecords();

        //mUiccProfile
        doReturn(mUiccCardApplication3gpp).when(mUiccProfile).getApplication(
                eq(UiccController.APP_FAM_3GPP));
        doReturn(mUiccCardApplication3gpp2).when(mUiccProfile).getApplication(
                eq(UiccController.APP_FAM_3GPP2));
        doReturn(mUiccCardApplicationIms).when(mUiccProfile).getApplication(
                eq(UiccController.APP_FAM_IMS));

        //SMS
        doReturn(true).when(mSmsStorageMonitor).isStorageAvailable();
        doReturn(true).when(mSmsUsageMonitor).check(nullable(String.class), anyInt());
        doReturn(true).when(mTelephonyManager).getSmsReceiveCapableForPhone(anyInt(), anyBoolean());
        doReturn(true).when(mTelephonyManager).getSmsSendCapableForPhone(
                anyInt(), anyBoolean());

        //Misc
        doReturn(ServiceState.RIL_RADIO_TECHNOLOGY_LTE).when(mServiceState)
                .getRilDataRadioTechnology();
        doReturn(new TelephonyDisplayInfo(TelephonyManager.NETWORK_TYPE_LTE,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE, false))
                .when(mDisplayInfoController).getTelephonyDisplayInfo();
        doReturn(mPhone).when(mCT).getPhone();
        doReturn(mImsEcbm).when(mImsManager).getEcbmInterface();
        doReturn(mPhone).when(mInboundSmsHandler).getPhone();
        doReturn(mImsCallProfile).when(mImsCall).getCallProfile();
        doReturn(mIBinder).when(mIIntentSender).asBinder();
        doAnswer(invocation -> {
            Intent[] intents = invocation.getArgument(6);
            if (intents != null && intents.length > 0) {
                doReturn(intents[0]).when(mIActivityManager)
                        .getIntentForIntentSender(mIIntentSender);
            }
            return mIIntentSender;
        }).when(mIActivityManager).getIntentSenderWithFeature(anyInt(),
                nullable(String.class), nullable(String.class), nullable(IBinder.class),
                nullable(String.class), anyInt(), nullable(Intent[].class),
                nullable(String[].class), anyInt(), nullable(Bundle.class), anyInt());
        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(anyInt());
        doReturn(true).when(mTelephonyManager).isDataCapable();

        mContextFixture.addSystemFeature(PackageManager.FEATURE_TELECOM);
        mContextFixture.addSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING);
        mContextFixture.addSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA);
        mContextFixture.addSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC);
        mContextFixture.addSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING);

        doReturn(TelephonyManager.PHONE_TYPE_GSM).when(mTelephonyManager).getPhoneType();
        doReturn(mServiceState).when(mSST).getServiceState();
        doReturn(mServiceStateStats).when(mSST).getServiceStateStats();
        mSST.mSS = mServiceState;
        mSST.mRestrictedState = mRestrictedState;
        mServiceManagerMockedServices.put("connectivity_metrics_logger", mConnMetLoggerBinder);
        mServiceManagerMockedServices.put("package", mMockPackageManager);
        mServiceManagerMockedServices.put("legacy_permission", mMockLegacyPermissionManager);
        logd("mMockLegacyPermissionManager replaced");
        doReturn(new int[]{AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN})
                .when(mAccessNetworksManager).getAvailableTransports();
        doReturn(new int[]{AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN})
                .when(mAccessNetworksManager).getAvailableTransports();
        doReturn(true).when(mDataSettingsManager).isDataEnabled();
        doReturn(mNetworkRegistrationInfo).when(mServiceState).getNetworkRegistrationInfo(
                anyInt(), anyInt());
        doReturn(RIL.RADIO_HAL_VERSION_2_0).when(mPhone).getHalVersion(anyInt());
        doReturn(2).when(mSignalStrength).getLevel();
        doReturn(mMockRadioConfigProxy).when(mMockRadioConfig).getRadioConfigProxy(any());

        // WiFi
        doReturn(mWifiInfo).when(mWifiManager).getConnectionInfo();
        doReturn(2).when(mWifiManager).calculateSignalLevel(anyInt());
        doReturn(4).when(mWifiManager).getMaxSignalLevel();

        doAnswer(invocation -> {
            NetworkCapabilities nc = invocation.getArgument(0);
            return new VcnNetworkPolicyResult(
                    false /* isTearDownRequested */, nc);
        }).when(mVcnManager).applyVcnNetworkPolicy(any(), any());

        //SIM
        doReturn(1).when(mTelephonyManager).getSimCount();
        doReturn(1).when(mTelephonyManager).getPhoneCount();
        doReturn(1).when(mTelephonyManager).getActiveModemCount();
        // Have getMaxPhoneCount always return the same value with getPhoneCount by default.
        doAnswer((invocation)->Math.max(mTelephonyManager.getActiveModemCount(),
                mTelephonyManager.getPhoneCount()))
                .when(mTelephonyManager).getSupportedModemCount();
        doReturn(mStatsManager).when(mContext).getSystemService(eq(Context.NETWORK_STATS_SERVICE));

        //Data
        //Initial state is: userData enabled, provisioned.
        ContentResolver resolver = mContext.getContentResolver();
        Settings.Global.putInt(resolver, Settings.Global.MOBILE_DATA, 1);
        Settings.Global.putInt(resolver, Settings.Global.DEVICE_PROVISIONED, 1);
        Settings.Global.putInt(resolver,
                Settings.Global.DEVICE_PROVISIONING_MOBILE_DATA_ENABLED, 1);
        Settings.Global.putInt(resolver, Settings.Global.DATA_ROAMING, 0);

        doReturn(90).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_EIMS));
        doReturn(80).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_SUPL));
        doReturn(70).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_MMS));
        doReturn(70).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_XCAP));
        doReturn(50).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_CBS));
        doReturn(50).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_MCX));
        doReturn(50).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_FOTA));
        doReturn(40).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_IMS));
        doReturn(30).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_DUN));
        doReturn(20).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_ENTERPRISE));
        doReturn(20).when(mDataConfigManager).getNetworkCapabilityPriority(
                eq(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        doReturn(60000).when(mDataConfigManager).getAnomalyNetworkConnectingTimeoutMs();
        doReturn(60000).when(mDataConfigManager)
                .getAnomalyNetworkDisconnectingTimeoutMs();
        doReturn(60000).when(mDataConfigManager).getNetworkHandoverTimeoutMs();
        doReturn(new DataConfigManager.EventFrequency(300000, 12))
                .when(mDataConfigManager).getAnomalySetupDataCallThreshold();
        doReturn(new DataConfigManager.EventFrequency(0, 2))
                .when(mDataConfigManager).getAnomalyImsReleaseRequestThreshold();
        doReturn(new DataConfigManager.EventFrequency(300000, 12))
                .when(mDataConfigManager).getAnomalyNetworkUnwantedThreshold();

        // CellularNetworkValidator
        doReturn(SubscriptionManager.INVALID_PHONE_INDEX)
                .when(mCellularNetworkValidator).getSubIdInValidation();
        doReturn(true).when(mCellularNetworkValidator).isValidationFeatureSupported();

        // Metrics
        doReturn(null).when(mContext).getFileStreamPath(anyString());
        doReturn(mPersistAtomsStorage).when(mMetricsCollector).getAtomsStorage();
        doReturn(mDefaultNetworkMonitor).when(mMetricsCollector).getDefaultNetworkMonitor();
        doReturn(mWifiManager).when(mContext).getSystemService(eq(Context.WIFI_SERVICE));
        doReturn(mDeviceStateHelper).when(mMetricsCollector).getDeviceStateHelper();
        doReturn(CELLULAR_SERVICE_STATE__FOLD_STATE__STATE_UNKNOWN)
                .when(mDeviceStateHelper)
                .getFoldState();
        doReturn(null).when(mContext).getSystemService(eq(Context.DEVICE_STATE_SERVICE));

        doReturn(false).when(mDomainSelectionResolver).isDomainSelectionSupported();
        DomainSelectionResolver.setDomainSelectionResolver(mDomainSelectionResolver);

        //Use reflection to mock singletons
        replaceInstance(CallManager.class, "INSTANCE", null, mCallManager);
        replaceInstance(TelephonyComponentFactory.class, "sInstance", null,
                mTelephonyComponentFactory);
        replaceInstance(UiccController.class, "mInstance", null, mUiccController);
        replaceInstance(CdmaSubscriptionSourceManager.class, "sInstance", null, mCdmaSSM);
        replaceInstance(SubscriptionManagerService.class, "sInstance", null,
                mSubscriptionManagerService);
        replaceInstance(ProxyController.class, "sProxyController", null, mProxyController);
        replaceInstance(PhoneSwitcher.class, "sPhoneSwitcher", null, mPhoneSwitcher);
        replaceInstance(ActivityManager.class, "IActivityManagerSingleton", null,
                mIActivityManagerSingleton);
        replaceInstance(CdmaSubscriptionSourceManager.class,
                "mCdmaSubscriptionSourceChangedRegistrants", mCdmaSSM, mRegistrantList);
        replaceInstance(SimulatedCommandsVerifier.class, "sInstance", null,
                mSimulatedCommandsVerifier);
        replaceInstance(Singleton.class, "mInstance", mIActivityManagerSingleton,
                mIActivityManager);
        replaceInstance(ServiceManager.class, "sCache", null, mServiceManagerMockedServices);
        replaceInstance(IntentBroadcaster.class, "sIntentBroadcaster", null, mIntentBroadcaster);
        replaceInstance(TelephonyManager.class, "sInstance", null,
                mContext.getSystemService(Context.TELEPHONY_SERVICE));
        replaceInstance(TelephonyManager.class, "sServiceHandleCacheEnabled", null, false);
        replaceInstance(PhoneFactory.class, "sMadeDefaults", null, true);
        replaceInstance(PhoneFactory.class, "sPhone", null, mPhone);
        replaceInstance(PhoneFactory.class, "sPhones", null, mPhones);
        replaceInstance(RadioConfig.class, "sRadioConfig", null, mMockRadioConfig);
        replaceInstance(PhoneConfigurationManager.class, "sInstance", null,
                mPhoneConfigurationManager);
        replaceInstance(CellularNetworkValidator.class, "sInstance", null,
                mCellularNetworkValidator);
        replaceInstance(MultiSimSettingController.class, "sInstance", null,
                mMultiSimSettingController);
        replaceInstance(PhoneFactory.class, "sCommandsInterfaces", null,
                new CommandsInterface[] {mSimulatedCommands});
        replaceInstance(PhoneFactory.class, "sMetricsCollector", null, mMetricsCollector);
        replaceInstance(SatelliteController.class, "sInstance", null, mSatelliteController);

        setReady(false);
        // create default TestableLooper for test and add to list of monitored loopers
        mTestableLooper = TestableLooper.get(TelephonyTest.this);
        if (mTestableLooper != null) {
            monitorTestableLooper(mTestableLooper);
        }
    }

    protected void tearDown() throws Exception {
        // Clear all remaining messages
        if (!mTestableLoopers.isEmpty()) {
            for (TestableLooper looper : mTestableLoopers) {
                looper.getLooper().quit();
            }
        }
        // Ensure there are no references to handlers between tests.
        PhoneConfigurationManager.unregisterAllMultiSimConfigChangeRegistrants();
        // unmonitor TestableLooper for TelephonyTest class
        if (mTestableLooper != null) {
            unmonitorTestableLooper(mTestableLooper);
        }
        // destroy all newly created TestableLoopers so they can be reused
        for (TestableLooper looper : mTestableLoopers) {
            looper.destroy();
        }
        TestableLooper.remove(TelephonyTest.this);

        if (mSimulatedCommands != null) {
            mSimulatedCommands.dispose();
        }
        if (mContext != null) {
            SharedPreferences sharedPreferences = mContext.getSharedPreferences((String) null, 0);
            if (sharedPreferences != null) {
                sharedPreferences.edit().clear().commit();
            }
        }
        restoreInstances();
        TelephonyManager.enableServiceHandleCaching();

        mNetworkRegistrationInfo = null;
        mActivityManager = null;
        mImsCallProfile = null;
        mTelephonyManager = null;
        mTelephonyRegistryManager = null;
        mSubscriptionManager = null;
        mEuiccManager = null;
        mPackageManager = null;
        mConnectivityManager = null;
        mAppOpsManager = null;
        mCarrierConfigManager = null;
        mUserManager = null;
        mKeyguardManager = null;
        mVcnManager = null;
        mNetworkPolicyManager = null;
        mSimulatedCommands = null;
        mContextFixture = null;
        mContext = null;
        mFakeBlockedNumberContentProvider = null;
        mServiceManagerMockedServices.clear();
        mServiceManagerMockedServices = null;
        mPhone = null;
        mTestableLoopers.clear();
        mTestableLoopers = null;
        mTestableLooper = null;
        DomainSelectionResolver.setDomainSelectionResolver(null);
    }

    protected static void logd(String s) {
        Log.d(TAG, s);
    }

    public static class FakeBlockedNumberContentProvider extends MockContentProvider {
        public Set<String> mBlockedNumbers = new HashSet<>();
        public int mNumEmergencyContactNotifications = 0;

        @Override
        public Bundle call(String method, String arg, Bundle extras) {
            switch (method) {
                case BlockedNumberContract.SystemContract.METHOD_SHOULD_SYSTEM_BLOCK_NUMBER:
                    Bundle bundle = new Bundle();
                    int blockStatus = mBlockedNumbers.contains(arg)
                            ? BlockedNumberContract.STATUS_BLOCKED_IN_LIST
                            : BlockedNumberContract.STATUS_NOT_BLOCKED;
                    bundle.putInt(BlockedNumberContract.RES_BLOCK_STATUS, blockStatus);
                    return bundle;
                case BlockedNumberContract.SystemContract.METHOD_NOTIFY_EMERGENCY_CONTACT:
                    mNumEmergencyContactNotifications++;
                    return new Bundle();
                default:
                    fail("Method not expected: " + method);
            }
            return null;
        }
    }

    public static class FakeSettingsConfigProvider extends MockContentProvider {
        private static final String PROPERTY_DEVICE_IDENTIFIER_ACCESS_RESTRICTIONS_DISABLED =
                DeviceConfig.NAMESPACE_PRIVACY + "/"
                        + "device_identifier_access_restrictions_disabled";
        private HashMap<String, String> mFlags = new HashMap<>();

        @Override
        public Bundle call(String method, String arg, Bundle extras) {
            logd("FakeSettingsConfigProvider: call called,  method: " + method +
                    " request: " + arg + ", args=" + extras);
            Bundle bundle = new Bundle();
            switch (method) {
                case Settings.CALL_METHOD_GET_CONFIG: {
                    switch (arg) {
                        case PROPERTY_DEVICE_IDENTIFIER_ACCESS_RESTRICTIONS_DISABLED: {
                            bundle.putString(
                                    PROPERTY_DEVICE_IDENTIFIER_ACCESS_RESTRICTIONS_DISABLED,
                                    "0");
                            return bundle;
                        }
                        default: {
                            fail("arg not expected: " + arg);
                        }
                    }
                    break;
                }
                case Settings.CALL_METHOD_LIST_CONFIG:
                    logd("LIST_config: " + mFlags);
                    Bundle result = new Bundle();
                    result.putSerializable(Settings.NameValueTable.VALUE, mFlags);
                    return result;
                case Settings.CALL_METHOD_SET_ALL_CONFIG:
                    mFlags = (extras != null)
                            ? (HashMap) extras.getSerializable(Settings.CALL_METHOD_FLAGS_KEY)
                            : new HashMap<>();
                    bundle.putInt(Settings.KEY_CONFIG_SET_ALL_RETURN,
                            Settings.SET_ALL_RESULT_SUCCESS);
                    return bundle;
                default:
                    fail("Method not expected: " + method);
            }
            return null;
        }
    }

    protected void setupMockPackagePermissionChecks() throws Exception {
        doReturn(new String[]{TAG}).when(mPackageManager).getPackagesForUid(anyInt());
        doReturn(mPackageInfo).when(mPackageManager).getPackageInfo(eq(TAG), anyInt());
    }

    protected void setupMocksForTelephonyPermissions() throws Exception {
        setupMocksForTelephonyPermissions(Build.VERSION_CODES.Q);
    }

    protected void setupMocksForTelephonyPermissions(int targetSdkVersion)
            throws Exception {
        // If the calling package does not meet the new requirements for device identifier access
        // TelephonyPermissions will query the PackageManager for the ApplicationInfo of the package
        // to determine the target SDK. For apps targeting Q a SecurityException is thrown
        // regardless of if the package satisfies the previous requirements for device ID access.

        // Any tests that query for SubscriptionInfo objects will trigger a phone number access
        // check that will first query the ApplicationInfo as apps targeting R+ can no longer
        // access the phone number with the READ_PHONE_STATE permission and instead must meet one of
        // the other requirements. This ApplicationInfo is generalized to any package name since
        // some tests will simulate invocation from other packages.
        mApplicationInfo.targetSdkVersion = targetSdkVersion;
        doReturn(mApplicationInfo).when(mPackageManager).getApplicationInfoAsUser(anyString(),
                anyInt(), any());

        // TelephonyPermissions uses a SystemAPI to check if the calling package meets any of the
        // generic requirements for device identifier access (currently READ_PRIVILEGED_PHONE_STATE,
        // appop, and device / profile owner checks). This sets up the PermissionManager to return
        // that access requirements are met.
        setIdentifierAccess(true);
        LegacyPermissionManager legacyPermissionManager =
                new LegacyPermissionManager(mMockLegacyPermissionManager);
        doReturn(legacyPermissionManager).when(mContext)
                .getSystemService(Context.LEGACY_PERMISSION_SERVICE);
        // Also make sure all appop checks fails, to not interfere tests. Tests should explicitly
        // mock AppOpManager to return allowed/default mode. Note by default a mock returns 0 which
        // is MODE_ALLOWED, hence this setup is necessary.
        doReturn(AppOpsManager.MODE_IGNORED).when(mAppOpsManager).noteOpNoThrow(
                /* op= */ anyString(), /* uid= */ anyInt(),
                /* packageName= */ nullable(String.class),
                /* attributionTag= */ nullable(String.class),
                /* message= */ nullable(String.class));

        // TelephonyPermissions queries DeviceConfig to determine if the identifier access
        // restrictions should be enabled; this results in a NPE when DeviceConfig uses
        // Activity.currentActivity.getContentResolver as the resolver for Settings.Config.getString
        // since the IContentProvider in the NameValueCache's provider holder is null.
        replaceContentProvider(new FakeSettingsConfigProvider());
    }

    private void replaceContentProvider(ContentProvider contentProvider) throws Exception {
        Class c = Class.forName("android.provider.Settings$Config");
        Field field = c.getDeclaredField("sNameValueCache");
        field.setAccessible(true);
        Object cache = field.get(null);

        c = Class.forName("android.provider.Settings$NameValueCache");
        field = c.getDeclaredField("mProviderHolder");
        field.setAccessible(true);
        Object providerHolder = field.get(cache);

        field = MockContentProvider.class.getDeclaredField("mIContentProvider");
        field.setAccessible(true);
        Object iContentProvider = field.get(contentProvider);

        replaceInstance(Class.forName("android.provider.Settings$ContentProviderHolder"),
                "mContentProvider", providerHolder, iContentProvider);
    }

    protected void setIdentifierAccess(boolean hasAccess) {
        doReturn(hasAccess ? PackageManager.PERMISSION_GRANTED
                : PackageManager.PERMISSION_DENIED).when(mMockLegacyPermissionManager)
                .checkDeviceIdentifierAccess(any(), any(), any(), anyInt(), anyInt());
    }

    protected void setPhoneNumberAccess(int value) {
        doReturn(value).when(mMockLegacyPermissionManager).checkPhoneNumberAccess(any(), any(),
                any(), anyInt(), anyInt());
    }

    protected void setCarrierPrivileges(boolean hasCarrierPrivileges) {
        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(anyInt());
        doReturn(hasCarrierPrivileges ? TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS
                : TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS).when(
                mTelephonyManager).getCarrierPrivilegeStatus(anyInt());
    }

    protected void setCarrierPrivilegesForSubId(boolean hasCarrierPrivileges, int subId) {
        TelephonyManager mockTelephonyManager = Mockito.mock(TelephonyManager.class);
        doReturn(mockTelephonyManager).when(mTelephonyManager).createForSubscriptionId(subId);
        doReturn(hasCarrierPrivileges ? TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS
                : TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS).when(
                mockTelephonyManager).getCarrierPrivilegeStatus(anyInt());
    }

    protected final void waitForDelayedHandlerAction(Handler h, long delayMillis,
            long timeoutMillis) {
        final CountDownLatch lock = new CountDownLatch(1);
        h.postDelayed(lock::countDown, delayMillis);
        while (lock.getCount() > 0) {
            try {
                lock.await(delayMillis + timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // do nothing
            }
        }
    }

    protected final void waitForHandlerAction(Handler h, long timeoutMillis) {
        final CountDownLatch lock = new CountDownLatch(1);
        h.post(lock::countDown);
        while (lock.getCount() > 0) {
            try {
                lock.await(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // do nothing
            }
        }
    }

    /**
     * Wait for up to 1 second for the handler message queue to clear.
     */
    protected final void waitForLastHandlerAction(Handler h) {
        CountDownLatch lock = new CountDownLatch(1);
        // Allow the handler to start work on stuff.
        h.postDelayed(lock::countDown, 100);
        int timeoutCount = 0;
        while (timeoutCount < 5) {
            try {
                if (lock.await(200, TimeUnit.MILLISECONDS)) {
                    // no messages in queue, stop waiting.
                    if (!h.hasMessagesOrCallbacks()) break;
                    lock = new CountDownLatch(1);
                    // Delay to allow the handler thread to start work on stuff.
                    h.postDelayed(lock::countDown, 100);
                }

            } catch (InterruptedException e) {
                // do nothing
            }
            timeoutCount++;
        }
        assertTrue("Handler was not empty before timeout elapsed", timeoutCount < 5);
    }

    protected final EmergencyNumber getTestEmergencyNumber() {
        return SAMPLE_EMERGENCY_NUMBER;
    }

    public static Object invokeMethod(
            Object instance, String methodName, Class<?>[] parameterClasses, Object[] parameters) {
        try {
            Method method = instance.getClass().getDeclaredMethod(methodName, parameterClasses);
            method.setAccessible(true);
            return method.invoke(instance, parameters);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            fail(instance.getClass() + " " + methodName + " " + e.getClass().getName());
        }
        return null;
    }

    /**
     * Add a TestableLooper to the list of monitored loopers
     * @param looper added if it doesn't already exist
     */
    public void monitorTestableLooper(TestableLooper looper) {
        if (!mTestableLoopers.contains(looper)) {
            mTestableLoopers.add(looper);
        }
    }

    /**
     * Remove a TestableLooper from the list of monitored loopers
     * @param looper removed if it does exist
     */
    private void unmonitorTestableLooper(TestableLooper looper) {
        if (mTestableLoopers.contains(looper)) {
            mTestableLoopers.remove(looper);
        }
    }

    /**
     * Handle all messages that can be processed at the current time
     * for all monitored TestableLoopers
     */
    public void processAllMessages() {
        if (mTestableLoopers.isEmpty()) {
            fail("mTestableLoopers is empty. Please make sure to add @RunWithLooper annotation");
        }
        while (!areAllTestableLoopersIdle()) {
            for (TestableLooper looper : mTestableLoopers) looper.processAllMessages();
        }
    }

    /**
     * @return The longest delay from all the message queues.
     */
    private long getLongestDelay() {
        long delay = 0;
        for (TestableLooper looper : mTestableLoopers) {
            MessageQueue queue = looper.getLooper().getQueue();
            try {
                Message msg = (Message) MESSAGE_QUEUE_FIELD.get(queue);
                while (msg != null) {
                    delay = Math.max(msg.getWhen(), delay);
                    msg = (Message) MESSAGE_NEXT_FIELD.get(msg);
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Access failed in TelephonyTest", e);
            }
        }
        return delay;
    }

    /**
     * @return {@code true} if there are any messages in the queue.
     */
    private boolean messagesExist() {
        for (TestableLooper looper : mTestableLoopers) {
            MessageQueue queue = looper.getLooper().getQueue();
            try {
                Message msg = (Message) MESSAGE_QUEUE_FIELD.get(queue);
                if (msg != null) return true;
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Access failed in TelephonyTest", e);
            }
        }
        return false;
    }

    /**
     * Handle all messages including the delayed messages.
     */
    public void processAllFutureMessages() {
        while (messagesExist()) {
            moveTimeForward(getLongestDelay());
            processAllMessages();
        }
    }

    /**
     * Check if there are any messages to be processed in any monitored TestableLooper
     * Delayed messages to be handled at a later time will be ignored
     * @return true if there are no messages that can be handled at the current time
     *         across all monitored TestableLoopers
     */
    private boolean areAllTestableLoopersIdle() {
        for (TestableLooper looper : mTestableLoopers) {
            if (!looper.getLooper().getQueue().isIdle()) return false;
        }
        return true;
    }

    /**
     * Effectively moves time forward by reducing the time of all messages
     * for all monitored TestableLoopers
     * @param milliSeconds number of milliseconds to move time forward by
     */
    public void moveTimeForward(long milliSeconds) {
        for (TestableLooper looper : mTestableLoopers) {
            MessageQueue queue = looper.getLooper().getQueue();
            try {
                Message msg = (Message) MESSAGE_QUEUE_FIELD.get(queue);
                while (msg != null) {
                    long updatedWhen = msg.getWhen() - milliSeconds;
                    if (updatedWhen < 0) {
                        updatedWhen = 0;
                    }
                    MESSAGE_WHEN_FIELD.set(msg, updatedWhen);
                    msg = (Message) MESSAGE_NEXT_FIELD.get(msg);
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Access failed in TelephonyTest", e);
            }
        }
    }
}
