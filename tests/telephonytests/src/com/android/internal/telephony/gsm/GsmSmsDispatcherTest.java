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

package com.android.internal.telephony.gsm;

import static android.telephony.SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED;
import static android.telephony.SmsManager.SMS_CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE;

import static com.android.internal.telephony.SmsUsageMonitor.PREMIUM_SMS_PERMISSION_NEVER_ALLOW;
import static com.android.internal.telephony.TelephonyTestUtils.waitForMs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.Country;
import android.location.CountryDetector;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.service.carrier.CarrierMessagingService;
import android.service.carrier.ICarrierMessagingCallback;
import android.service.carrier.ICarrierMessagingService;
import android.telephony.SmsManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Singleton;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;

import com.android.internal.telephony.ContextFixture;
import com.android.internal.telephony.ISub;
import com.android.internal.telephony.SMSDispatcher;
import com.android.internal.telephony.SmsDispatchersController;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.TelephonyTestUtils;
import com.android.internal.telephony.TestApplication;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.uicc.IsimUiccRecords;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class GsmSmsDispatcherTest extends TelephonyTest {
    private static final String CARRIER_APP_PACKAGE_NAME = "com.android.carrier";

    // Mocked classes
    private SmsDispatchersController mSmsDispatchersController;
    private GsmInboundSmsHandler mGsmInboundSmsHandler;
    private CountryDetector mCountryDetector;
    private SMSDispatcher.SmsTracker mSmsTracker;
    private ISub.Stub mISubStub;
    private ICarrierMessagingService.Stub mICarrierAppMessagingService;

    private Object mLock = new Object();
    private boolean mReceivedTestIntent;
    private static final String TEST_INTENT = "com.android.internal.telephony.TEST_INTENT";
    private final BroadcastReceiver mTestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            logd("onReceive");
            synchronized (mLock) {
                mReceivedTestIntent = true;
                mLock.notifyAll();
            }
        }
    };

    private GsmSMSDispatcher mGsmSmsDispatcher;
    private GsmSmsDispatcherTestHandler mGsmSmsDispatcherTestHandler;
    private int mCallingUserId;

    private class GsmSmsDispatcherTestHandler extends HandlerThread {

        private GsmSmsDispatcherTestHandler(String name) {
            super(name);
        }

        @Override
        public void onLooperPrepared() {
            mGsmSmsDispatcher = new GsmSMSDispatcher(mPhone, mSmsDispatchersController,
                    mGsmInboundSmsHandler);
            setReady(true);
        }
    }

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        mSmsDispatchersController = mock(SmsDispatchersController.class);
        mGsmInboundSmsHandler = mock(GsmInboundSmsHandler.class);
        mCountryDetector = mock(CountryDetector.class);
        mSmsTracker = mock(SMSDispatcher.SmsTracker.class);
        mISubStub = mock(ISub.Stub.class);
        mICarrierAppMessagingService = mock(ICarrierMessagingService.Stub.class);

        // Note that this replaces only cached services in ServiceManager. If a service is not found
        // in the cache, a real instance is used.
        mServiceManagerMockedServices.put("isub", mISubStub);

        doReturn(mSmsUsageMonitor).when(mSmsDispatchersController).getUsageMonitor();
        mGsmSmsDispatcherTestHandler = new GsmSmsDispatcherTestHandler(getClass().getSimpleName());
        mGsmSmsDispatcherTestHandler.start();
        waitUntilReady();
        mGsmSmsDispatcher = new GsmSMSDispatcher(mPhone, mSmsDispatchersController,
                mGsmInboundSmsHandler);
        mCallingUserId = Binder.getCallingUserHandle().getIdentifier();
        processAllMessages();
    }

    @After
    public void tearDown() throws Exception {
        mGsmSmsDispatcherTestHandler.quit();
        mGsmSmsDispatcherTestHandler.join();
        mGsmSmsDispatcherTestHandler = null;
        mGsmSmsDispatcher = null;
        super.tearDown();
    }

    @Test @SmallTest
    public void testSmsStatus() {
        mSimulatedCommands.notifySmsStatus(new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF});
        processAllMessages();
        verify(mSimulatedCommandsVerifier).acknowledgeLastIncomingGsmSms(true, 0, null);
    }

    @Test @MediumTest
    public void testSendSmsToRegularNumber_doesNotNotifyblockedNumberProvider() throws Exception {
        setupMockPackagePermissionChecks();

        mContextFixture.setSystemService(Context.COUNTRY_DETECTOR, mCountryDetector);
        when(mCountryDetector.detectCountry())
                .thenReturn(new Country("US", Country.COUNTRY_SOURCE_SIM));

        mGsmSmsDispatcher.sendText("6501002000", "121" /*scAddr*/, "test sms",
                null, null, null, null, mCallingUserId, false, -1, false, -1, false, 0L);

        verify(mSimulatedCommandsVerifier).sendSMS(anyString(), anyString(), any(Message.class));
        // Blocked number provider is notified about the emergency contact asynchronously.
        TelephonyTestUtils.waitForMs(50);
        assertEquals(0, mFakeBlockedNumberContentProvider.mNumEmergencyContactNotifications);
    }

    @FlakyTest
    @Ignore
    @Test @MediumTest
    public void testSendSmsToEmergencyNumber_notifiesBlockedNumberProvider() throws Exception {
        setupMockPackagePermissionChecks();

        mContextFixture.setSystemService(Context.COUNTRY_DETECTOR, mCountryDetector);
        when(mCountryDetector.detectCountry())
                .thenReturn(new Country("US", Country.COUNTRY_SOURCE_SIM));

        mGsmSmsDispatcher.sendText(
                getEmergencyNumberFromSystemPropertiesOrDefault(), "121" /*scAddr*/, "test sms",
                null, null, null, null, mCallingUserId, false, -1, false, -1, false, 0L);

        verify(mSimulatedCommandsVerifier).sendSMS(anyString(), anyString(), any(Message.class));
        // Blocked number provider is notified about the emergency contact asynchronously.
        TelephonyTestUtils.waitForMs(50);
        assertEquals(1, mFakeBlockedNumberContentProvider.mNumEmergencyContactNotifications);
    }

    @Test @SmallTest
    public void testSmsMessageValidityPeriod() throws Exception {
        int vp;
        vp = SmsMessage.getRelativeValidityPeriod(-5);
        assertEquals(-1, vp);

        vp = SmsMessage.getRelativeValidityPeriod(100);
        assertEquals(100 / 5 - 1, vp);
    }

    private String getEmergencyNumberFromSystemPropertiesOrDefault() {
        String systemEmergencyNumbers = SystemProperties.get("ril.ecclist");
        if (systemEmergencyNumbers == null) {
            return "911";
        } else {
            return systemEmergencyNumbers.split(",")[0];
        }
    }

    private void registerTestIntentReceiver() throws Exception {
        // unmock ActivityManager to be able to register receiver, create real PendingIntent and
        // receive TEST_INTENT
        restoreInstance(Singleton.class, "mInstance", mIActivityManagerSingleton);
        restoreInstance(ActivityManager.class, "IActivityManagerSingleton", null);
        Context realContext = TestApplication.getAppContext();
        realContext.registerReceiver(mTestReceiver, new IntentFilter(TEST_INTENT),
                Context.RECEIVER_EXPORTED);
    }

    @Test
    @SmallTest
    @FlakyTest
    @Ignore
    public void testSendTextWithInvalidDestAddr() throws Exception {
        registerTestIntentReceiver();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(TestApplication.getAppContext(), 0,
                new Intent(TEST_INTENT), 0);
        // send invalid dest address: +
        mReceivedTestIntent = false;
        mGsmSmsDispatcher.sendText("+", "222" /*scAddr*/, TAG,
                pendingIntent, null, null, null, mCallingUserId, false, -1, false, -1, false, 0L);
        waitForMs(500);
        verify(mSimulatedCommandsVerifier, times(0)).sendSMS(anyString(), anyString(),
                any(Message.class));
        synchronized (mLock) {
            assertEquals(true, mReceivedTestIntent);
            assertEquals(SmsManager.RESULT_ERROR_NULL_PDU, mTestReceiver.getResultCode());
        }
    }

    @Test
    public void testSendRawPduWithEventStopSending() throws Exception {
        setupMockPackagePermissionChecks();
        mContextFixture.removeCallingOrSelfPermission(ContextFixture.PERMISSION_ENABLE_ALL);

        // return a fake value to pass getData()
        HashMap data = new HashMap<String, String>();
        data.put("pdu", new byte[1]);
        when(mSmsTracker.getData()).thenReturn(data);

        // Set values to return to simulate EVENT_STOP_SENDING
        when(mSmsUsageMonitor.checkDestination(any(), any()))
                .thenReturn(SMS_CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE);
        when(mSmsUsageMonitor.getPremiumSmsPermission(any()))
                .thenReturn(PREMIUM_SMS_PERMISSION_NEVER_ALLOW);
        when(mSmsTracker.getAppPackageName()).thenReturn("");

        // Settings.Global.DEVICE_PROVISIONED to 1
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 1);

        mGsmSmsDispatcher.sendRawPdu(new SMSDispatcher.SmsTracker[] {mSmsTracker});
        //waitForHandlerAction(mGsmSmsDispatcher, TIMEOUT_MS);
        processAllMessages();

        verify(mSmsUsageMonitor, times(1)).checkDestination(any(), any());
        verify(mSmsUsageMonitor, times(1)).getPremiumSmsPermission(any());
        ArgumentCaptor<Integer> argumentCaptor = ArgumentCaptor
                .forClass(Integer.class);
        verify(mSmsTracker, times(1)).onFailed(any(), argumentCaptor.capture(), anyInt());
        assertEquals(RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED, (int) argumentCaptor.getValue());
    }

    @Test @SmallTest
    @FlakyTest
    @Ignore
    public void testSendMultipartTextWithInvalidText() throws Exception {
        registerTestIntentReceiver();

        // initiate parameters for an invalid text MO SMS (the 2nd segmeant has 161 characters)
        ArrayList<String> parts = new ArrayList<>();
        parts.add("valid segment1");
        parts.add("too long segment2 12345678912345678912345678912345678912345678912345678912345678"
                + "91234567891234567891234567891234567891234567891234567891234567891234567891234567"
                + "8");

        ArrayList<PendingIntent> sentIntents = new ArrayList<>();
        PendingIntent sentIntent = PendingIntent.getBroadcast(TestApplication.getAppContext(), 0,
                new Intent(TEST_INTENT), PendingIntent.FLAG_IMMUTABLE);
        sentIntents.add(sentIntent);
        sentIntents.add(sentIntent);

        // send SMS and check sentIntent
        mReceivedTestIntent = false;
        mGsmSmsDispatcher.sendMultipartText("+123" /*destAddr*/, "222" /*scAddr*/, parts,
                sentIntents, null, null, null, mCallingUserId, false, -1, false, -1, 0L, 0L);

        waitForMs(500);
        synchronized (mLock) {
            assertEquals(true, mReceivedTestIntent);
            assertEquals(SmsManager.RESULT_ERROR_GENERIC_FAILURE, mTestReceiver.getResultCode());
        }
    }

    private void mockCarrierApp()
            throws RemoteException {
        mContextFixture.addService(
                CarrierMessagingService.SERVICE_INTERFACE,
                new ComponentName(CARRIER_APP_PACKAGE_NAME, "CarrierAppFilterClass"),
                CARRIER_APP_PACKAGE_NAME,
                mICarrierAppMessagingService,
                new ServiceInfo());
        when(mICarrierAppMessagingService.asBinder()).thenReturn(mICarrierAppMessagingService);
        mockUiccWithCarrierApp();
    }

    private void mockUiccWithCarrierApp() {
        List<String> carrierPackages = new ArrayList<>();
        carrierPackages.add(CARRIER_APP_PACKAGE_NAME);
        when(mCarrierPrivilegesTracker.getCarrierPackageNamesForIntent(any()))
                .thenReturn(carrierPackages);
    }

    private void mockCarrierAppStubResults(final int result, ICarrierMessagingService.Stub stub,
            boolean callOnFilterComplete)
            throws RemoteException {
        when(stub.queryLocalInterface(anyString())).thenReturn(stub);
        when(stub.asBinder()).thenReturn(stub);
        // for single part
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                ICarrierMessagingCallback callback = (ICarrierMessagingCallback) args[4];
                if (callOnFilterComplete) {
                    callback.onSendSmsComplete(result, 0);
                }
                return null;
            }
        }).when(stub).sendTextSms(
                anyString(), anyInt(), anyString(), anyInt(),
                any(ICarrierMessagingCallback.class));

        // for multi part
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                ICarrierMessagingCallback callback = (ICarrierMessagingCallback) args[4];
                if (callOnFilterComplete) {
                    callback.onSendMultipartSmsComplete(result, null);
                }
                return null;
            }
        }).when(stub).sendMultipartTextSms(
                any(), anyInt(), anyString(), anyInt(),
                any(ICarrierMessagingCallback.class));
    }

    @Test
    @SmallTest
    @Ignore("b/256282780")
    public void testSendSmsByCarrierApp() throws Exception {
        mockCarrierApp();
        mockCarrierAppStubResults(CarrierMessagingService.SEND_STATUS_OK,
                mICarrierAppMessagingService, true);
        registerTestIntentReceiver();

        PendingIntent pendingIntent = PendingIntent.getBroadcast(TestApplication.getAppContext(), 0,
                new Intent(TEST_INTENT)
                        .setPackage(TestApplication.getAppContext().getPackageName()),
                PendingIntent.FLAG_MUTABLE);
        mReceivedTestIntent = false;

        mGsmSmsDispatcher.sendText("6501002000", "121" /*scAddr*/, "test sms",
                pendingIntent, null, null, null, mCallingUserId, false, -1, false, -1, false, 0L);
        processAllMessages();
        synchronized (mLock) {
            if (!mReceivedTestIntent) {
                // long wait since sometimes broadcasts can take a long time if the system is loaded
                mLock.wait(60000);
            }
            assertEquals(true, mReceivedTestIntent);
            int resultCode = mTestReceiver.getResultCode();
            assertTrue("Unexpected result code: " + resultCode,
                    resultCode == SmsManager.RESULT_ERROR_NONE || resultCode == Activity.RESULT_OK);
            verify(mSimulatedCommandsVerifier, times(0)).sendSMS(anyString(), anyString(),
                    any(Message.class));
        }
    }

    @Test
    @SmallTest
    public void testSendSmsByCarrierAppNoResponse() throws Exception {
        mockCarrierApp();
        // do not mock result, instead reduce the timeout for test
        mGsmSmsDispatcher.mCarrierMessagingTimeout = 100;

        mGsmSmsDispatcher.sendText("6501002000", "121" /*scAddr*/, "test sms",
                null, null, null, null, mCallingUserId, false, -1, false, -1, false, 0L);
        // wait for timeout
        waitForMs(150);
        verify(mSimulatedCommandsVerifier).sendSMS(anyString(), anyString(), any(Message.class));
    }

    @Test
    @SmallTest
    public void testSendSmsByCarrierAppBindingFailed() throws Exception {
        mContextFixture.mockBindingFailureForPackage(CARRIER_APP_PACKAGE_NAME);
        // mock presence of carrier app, but do not create a mock service to make binding fail
        mockUiccWithCarrierApp();

        mGsmSmsDispatcher.sendText("6501002000", "121" /*scAddr*/, "test sms",
                null, null, null, null, mCallingUserId, false, -1, false, -1, false, 0L);
        processAllMessages();
        verify(mSimulatedCommandsVerifier).sendSMS(anyString(), anyString(), any(Message.class));
    }

    private void sendMultipartTextSms(boolean withSentIntents) {
        // initiate parameters for a multipart sms
        ArrayList<String> parts = new ArrayList<>();
        parts.add("segment1");
        parts.add("segment2");

        ArrayList<PendingIntent> sentIntents = new ArrayList<>();
        PendingIntent sentIntent1 = PendingIntent.getBroadcast(TestApplication.getAppContext(), 0,
                new Intent(TEST_INTENT)
                        .setPackage(TestApplication.getAppContext().getPackageName()),
                PendingIntent.FLAG_MUTABLE);
        PendingIntent sentIntent2 = PendingIntent.getBroadcast(TestApplication.getAppContext(), 0,
                new Intent(TEST_INTENT)
                        .setPackage(TestApplication.getAppContext().getPackageName()),
                PendingIntent.FLAG_MUTABLE);
        sentIntents.add(sentIntent1);
        sentIntents.add(sentIntent2);

        mGsmSmsDispatcher.sendMultipartText("6501002000" /*destAddr*/, "222" /*scAddr*/, parts,
                withSentIntents ? sentIntents : null, null, null, null, mCallingUserId,
                false, -1, false, -1, 0L, 0L);
    }

    @Test
    @SmallTest
    @Ignore("b/256282780")
    public void testSendMultipartSmsByCarrierApp() throws Exception {
        mockCarrierApp();
        mockCarrierAppStubResults(CarrierMessagingService.SEND_STATUS_OK,
                mICarrierAppMessagingService, true);
        registerTestIntentReceiver();

        // send SMS and check sentIntent
        mReceivedTestIntent = false;
        sendMultipartTextSms(true);
        processAllMessages();
        synchronized (mLock) {
            if (!mReceivedTestIntent) {
                // long wait since sometimes broadcasts can take a long time if the system is loaded
                mLock.wait(60000);
            }
            assertEquals(true, mReceivedTestIntent);
            int resultCode = mTestReceiver.getResultCode();
            assertTrue("Unexpected result code: " + resultCode,
                    resultCode == SmsManager.RESULT_ERROR_NONE || resultCode == Activity.RESULT_OK);
            verify(mSimulatedCommandsVerifier, times(0)).sendSMS(anyString(), anyString(),
                    any(Message.class));
        }
    }

    @Test
    @SmallTest
    public void testSendMultipartSmsByCarrierAppNoResponse() throws Exception {
        mockCarrierApp();
        // do not mock result, instead reduce the timeout for test
        mGsmSmsDispatcher.mCarrierMessagingTimeout = 100;

        sendMultipartTextSms(false);

        // wait for timeout
        waitForMs(150);
        verify(mSimulatedCommandsVerifier).sendSMSExpectMore(anyString(), anyString(),
                any(Message.class));
        verify(mSimulatedCommandsVerifier).sendSMS(anyString(), anyString(),
                any(Message.class));
    }

    @Test
    @SmallTest
    public void testSendMultipartSmsByCarrierAppBindingFailed() throws Exception {
        mContextFixture.mockBindingFailureForPackage(CARRIER_APP_PACKAGE_NAME);
        // mock presence of carrier app, but do not create a mock service to make binding fail
        mockUiccWithCarrierApp();

        sendMultipartTextSms(false);

        processAllMessages();
        verify(mSimulatedCommandsVerifier).sendSMSExpectMore(anyString(), anyString(),
                any(Message.class));
        verify(mSimulatedCommandsVerifier).sendSMS(anyString(), anyString(),
                any(Message.class));
    }


    @Test
    public void testSendTextWithMessageRef() throws Exception {
        int messageRef = mGsmSmsDispatcher.nextMessageRef();
        if (mGsmSmsDispatcher.isMessageRefIncrementViaTelephony()) {
            messageRef += 1;
        }

        mGsmSmsDispatcher.sendText("111", "222" /*scAddr*/, TAG,
                null, null, null, null, mCallingUserId, false, -1, false, -1, false, 0L);

        ArgumentCaptor<String> pduCaptor = ArgumentCaptor.forClass(String.class);
        verify(mSimulatedCommandsVerifier).sendSMS(anyString(), pduCaptor.capture(),
                any(Message.class));
        byte[] pdu = IccUtils.hexStringToBytes(pduCaptor.getValue());
        assertEquals(messageRef, pdu[1]);
    }

    @Test
    public void testSendMultipartWithMessageRef() throws Exception {
        ArrayList<String> parts = new ArrayList<>();
        parts.add("segment1");
        parts.add("segment2");
        parts.add("segment3");

        int messageRef = mGsmSmsDispatcher.nextMessageRef();
        if (mGsmSmsDispatcher.isMessageRefIncrementViaTelephony()) {
            messageRef += parts.size();
        }
        mGsmSmsDispatcher.sendMultipartText("6501002000" /*destAddr*/, "222" /*scAddr*/, parts,
                null, null, null, null, mCallingUserId, false, -1, false, -1, 0L, 0L);
        waitForMs(150);
        ArgumentCaptor<String> pduCaptor = ArgumentCaptor.forClass(String.class);

        verify(mSimulatedCommandsVerifier, times(parts.size() - 1)).sendSMSExpectMore(anyString(),
                anyString(),
                any(Message.class));
        verify(mSimulatedCommandsVerifier).sendSMS(anyString(), pduCaptor.capture(),
                any(Message.class));
        byte[] pdu = IccUtils.hexStringToBytes(pduCaptor.getValue());
        assertEquals(messageRef, pdu[1]);
    }

    @Test
    public void testSendTextWithMessageRefNegativeBoundaryCondition() throws Exception {
        mIsimUiccRecords = new IsimUiccRecords(mUiccCardApplication3gpp, mContext,
                mSimulatedCommands);
        doReturn(mIsimUiccRecords).when(mPhone).getIccRecords();
        Message msg = mGsmSmsDispatcher.obtainMessage(17);
        mPhone.getIccRecords().setSmssTpmrValue(-1, msg);
        mSubscriptionManagerService.setLastUsedTPMessageReference(mPhone.getSubId(), -1);

        mGsmSmsDispatcher.sendText("111", "222" /*scAddr*/, TAG,
                null, null, null, null, mCallingUserId, false, -1, false, -1, false, 0L);

        ArgumentCaptor<String> pduCaptor = ArgumentCaptor.forClass(String.class);
        verify(mSimulatedCommandsVerifier).sendSMS(anyString(), pduCaptor.capture(),
                any(Message.class));
        byte[] pdu = IccUtils.hexStringToBytes(pduCaptor.getValue());
        assertEquals(0, pdu[1]);
    }

    @Test
    public void testSendTextWithMessageRefMaxBoundaryCondition() throws Exception {
        mIsimUiccRecords = new IsimUiccRecords(mUiccCardApplication3gpp, mContext,
                mSimulatedCommands);
        doReturn(mIsimUiccRecords).when(mPhone).getIccRecords();
        Message msg = mGsmSmsDispatcher.obtainMessage(17);
        mPhone.getIccRecords().setSmssTpmrValue(255, msg);
        mGsmSmsDispatcher.sendText("111", "222" /*scAddr*/, TAG,
                null, null, null, null, mCallingUserId, false, -1, false, -1, false, 0L);

        ArgumentCaptor<String> pduCaptor = ArgumentCaptor.forClass(String.class);
        verify(mSimulatedCommandsVerifier).sendSMS(anyString(), pduCaptor.capture(),
                any(Message.class));
        byte[] pdu = IccUtils.hexStringToBytes(pduCaptor.getValue());
        assertEquals(0, pdu[1]);
    }
}
