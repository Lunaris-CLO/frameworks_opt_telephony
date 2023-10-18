/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.telephony.satellite;

import static com.android.internal.telephony.satellite.DatagramController.SATELLITE_ALIGN_TIMEOUT;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Looper;
import android.os.Message;
import android.telephony.SubscriptionManager;
import android.telephony.satellite.SatelliteDatagram;
import android.telephony.satellite.SatelliteManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.satellite.metrics.ControllerMetricsStats;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DatagramDispatcherTest extends TelephonyTest {
    private static final String TAG = "DatagramDispatcherTest";
    private static final int SUB_ID = 0;
    private static final int DATAGRAM_TYPE1 = SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE;
    private static final int DATAGRAM_TYPE2 = SatelliteManager.DATAGRAM_TYPE_LOCATION_SHARING;
    private static final String TEST_MESSAGE = "This is a test datagram message";
    private static final long TEST_EXPIRE_TIMER_SATELLITE_ALIGN = TimeUnit.SECONDS.toMillis(1);

    private DatagramDispatcher mDatagramDispatcherUT;
    private TestDatagramDispatcher mTestDemoModeDatagramDispatcher;

    @Mock private DatagramController mMockDatagramController;
    @Mock private DatagramReceiver mMockDatagramReceiver;
    @Mock private SatelliteModemInterface mMockSatelliteModemInterface;
    @Mock private ControllerMetricsStats mMockControllerMetricsStats;
    @Mock private SatelliteSessionController mMockSatelliteSessionController;

    /** Variables required to send datagram in the unit tests. */
    LinkedBlockingQueue<Integer> mResultListener;
    SatelliteDatagram mDatagram;
    InOrder mInOrder;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        MockitoAnnotations.initMocks(this);
        logd(TAG + " Setup!");

        replaceInstance(DatagramController.class, "sInstance", null,
                mMockDatagramController);
        replaceInstance(DatagramReceiver.class, "sInstance", null,
                mMockDatagramReceiver);
        replaceInstance(SatelliteModemInterface.class, "sInstance", null,
                mMockSatelliteModemInterface);
        replaceInstance(ControllerMetricsStats.class, "sInstance", null,
                mMockControllerMetricsStats);
        replaceInstance(SatelliteSessionController.class, "sInstance", null,
                mMockSatelliteSessionController);

        mDatagramDispatcherUT = DatagramDispatcher.make(mContext, Looper.myLooper(),
                mMockDatagramController);
        mTestDemoModeDatagramDispatcher = new TestDatagramDispatcher(mContext, Looper.myLooper(),
                mMockDatagramController);

        mResultListener = new LinkedBlockingQueue<>(1);
        mDatagram = new SatelliteDatagram(TEST_MESSAGE.getBytes());
        mInOrder = inOrder(mMockDatagramController);
        when(mMockDatagramController.isPollingInIdleState()).thenReturn(true);
    }

    @After
    public void tearDown() throws Exception {
        logd(TAG + " tearDown");
        mDatagramDispatcherUT.destroy();
        mDatagramDispatcherUT = null;
        mTestDemoModeDatagramDispatcher = null;
        mResultListener = null;
        mDatagram = null;
        mInOrder = null;
        super.tearDown();
    }

    @Test
    public void testSendSatelliteDatagram_usingSatelliteModemInterface_success() throws  Exception {
        doReturn(true).when(mMockSatelliteModemInterface).isSatelliteServiceSupported();
        doAnswer(invocation -> {
            Message message = (Message) invocation.getArguments()[3];

            mDatagramDispatcherUT.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                    new AsyncResult(message.obj, null, null))
                    .sendToTarget();
            return null;
        }).when(mMockSatelliteModemInterface).sendSatelliteDatagram(any(SatelliteDatagram.class),
                anyBoolean(), anyBoolean(), any(Message.class));
        doReturn(true).when(mMockDatagramController)
                .needsWaitingForSatelliteConnected();
        when(mMockDatagramController.getDatagramWaitTimeForConnectedState())
                .thenReturn(DatagramController.DATAGRAM_WAIT_FOR_CONNECTED_STATE_TIMEOUT);
        mResultListener.clear();

        mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, DATAGRAM_TYPE1, mDatagram,
                true, mResultListener::offer);
        processAllMessages();
        mInOrder.verify(mMockDatagramController).needsWaitingForSatelliteConnected();
        mInOrder.verify(mMockDatagramController).updateSendStatus(eq(SUB_ID),
                eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_WAITING_TO_CONNECT), eq(1),
                eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        mInOrder.verify(mMockDatagramController).getDatagramWaitTimeForConnectedState();
        verifyZeroInteractions(mMockSatelliteModemInterface);
        assertTrue(mDatagramDispatcherUT.isDatagramWaitForConnectedStateTimerStarted());

        mDatagramDispatcherUT.onSatelliteModemStateChanged(
                SatelliteManager.SATELLITE_MODEM_STATE_CONNECTED);
        processAllMessages();

        mInOrder.verify(mMockDatagramController).isPollingInIdleState();
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        verifyNoMoreInteractions(mMockDatagramController);
        verify(mMockSatelliteModemInterface, times(1)).sendSatelliteDatagram(
                any(SatelliteDatagram.class), anyBoolean(), anyBoolean(), any(Message.class));
        assertFalse(mDatagramDispatcherUT.isDatagramWaitForConnectedStateTimerStarted());

        assertThat(mResultListener.peek()).isEqualTo(SatelliteManager.SATELLITE_RESULT_SUCCESS);

        clearInvocations(mMockSatelliteModemInterface);
        clearInvocations(mMockDatagramController);
        mResultListener.clear();

        clearInvocations(mMockSatelliteModemInterface);
        clearInvocations(mMockDatagramController);
        mResultListener.clear();
        mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, DATAGRAM_TYPE1, mDatagram,
                true, mResultListener::offer);
        processAllMessages();
        verifyZeroInteractions(mMockSatelliteModemInterface);
        mInOrder.verify(mMockDatagramController).needsWaitingForSatelliteConnected();
        mInOrder.verify(mMockDatagramController).getDatagramWaitTimeForConnectedState();
        assertTrue(mDatagramDispatcherUT.isDatagramWaitForConnectedStateTimerStarted());

        moveTimeForward(DatagramController.DATAGRAM_WAIT_FOR_CONNECTED_STATE_TIMEOUT);
        processAllMessages();
        verifyZeroInteractions(mMockSatelliteModemInterface);
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED), eq(1),
                        eq(SatelliteManager.SATELLITE_RESULT_NOT_REACHABLE));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        assertEquals(1, mResultListener.size());
        assertThat(mResultListener.peek()).isEqualTo(
                SatelliteManager.SATELLITE_RESULT_NOT_REACHABLE);
        assertFalse(mDatagramDispatcherUT.isDatagramWaitForConnectedStateTimerStarted());

        mResultListener.clear();
        mDatagramDispatcherUT.onSatelliteModemStateChanged(
                SatelliteManager.SATELLITE_MODEM_STATE_CONNECTED);
        processAllMessages();
        verifyZeroInteractions(mMockSatelliteModemInterface);
        assertEquals(0, mResultListener.size());

        clearInvocations(mMockSatelliteModemInterface);
        clearInvocations(mMockDatagramController);
        mResultListener.clear();
        mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, DATAGRAM_TYPE1, mDatagram,
                true, mResultListener::offer);
        processAllMessages();
        verifyZeroInteractions(mMockSatelliteModemInterface);
        mInOrder.verify(mMockDatagramController).needsWaitingForSatelliteConnected();
        mInOrder.verify(mMockDatagramController).getDatagramWaitTimeForConnectedState();
        assertTrue(mDatagramDispatcherUT.isDatagramWaitForConnectedStateTimerStarted());
        assertEquals(0, mResultListener.size());

        mDatagramDispatcherUT.onSatelliteModemStateChanged(
                SatelliteManager.SATELLITE_MODEM_STATE_OFF);
        processAllMessages();
        verifyZeroInteractions(mMockSatelliteModemInterface);
        assertEquals(1, mResultListener.size());
        assertThat(mResultListener.peek()).isEqualTo(
                SatelliteManager.SATELLITE_RESULT_REQUEST_ABORTED);
        assertFalse(mDatagramDispatcherUT.isDatagramWaitForConnectedStateTimerStarted());
    }

    @Test
    public void testSendSatelliteDatagram_usingSatelliteModemInterface_failure() throws  Exception {
        doReturn(true).when(mMockSatelliteModemInterface).isSatelliteServiceSupported();
        doAnswer(invocation -> {
            Message message = (Message) invocation.getArguments()[3];

            mDatagramDispatcherUT.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                            new AsyncResult(message.obj, null,
                                    new SatelliteManager.SatelliteException(
                                            SatelliteManager.SATELLITE_RESULT_SERVICE_ERROR)))
                    .sendToTarget();
            return null;
        }).when(mMockSatelliteModemInterface).sendSatelliteDatagram(any(SatelliteDatagram.class),
                anyBoolean(), anyBoolean(), any(Message.class));

        mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, DATAGRAM_TYPE2, mDatagram,
                true, mResultListener::offer);

        processAllMessages();

        mInOrder.verify(mMockDatagramController).needsWaitingForSatelliteConnected();
        mInOrder.verify(mMockDatagramController).isPollingInIdleState();
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SERVICE_ERROR));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        verifyNoMoreInteractions(mMockDatagramController);

        assertThat(mResultListener.peek()).isEqualTo(
                SatelliteManager.SATELLITE_RESULT_SERVICE_ERROR);
    }

    @Test
    public void testSendSatelliteDatagram_usingCommandsInterface_phoneNull() throws Exception {
        doReturn(false).when(mMockSatelliteModemInterface).isSatelliteServiceSupported();
        replaceInstance(PhoneFactory.class, "sPhones", null, new Phone[] {null});

        mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, DATAGRAM_TYPE1, mDatagram,
                true, mResultListener::offer);

        processAllMessages();

        mInOrder.verify(mMockDatagramController).needsWaitingForSatelliteConnected();
        mInOrder.verify(mMockDatagramController).isPollingInIdleState();
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        verifyNoMoreInteractions(mMockDatagramController);

        assertThat(mResultListener.peek())
                .isEqualTo(SatelliteManager.SATELLITE_RESULT_INVALID_TELEPHONY_STATE);
    }

    @Test
    public void testSendSatelliteDatagram_usingCommandsInterface_success() throws  Exception {
        doReturn(false).when(mMockSatelliteModemInterface).isSatelliteServiceSupported();
        replaceInstance(PhoneFactory.class, "sPhones", null, new Phone[] {mPhone});
        doAnswer(invocation -> {
            Message message = (Message) invocation.getArguments()[0];

            mDatagramDispatcherUT.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                            new AsyncResult(message.obj, null, null))
                    .sendToTarget();
            return null;
        }).when(mPhone).sendSatelliteDatagram(any(Message.class), any(SatelliteDatagram.class),
                anyBoolean());

        mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, DATAGRAM_TYPE2, mDatagram,
                true, mResultListener::offer);

        processAllMessages();

        mInOrder.verify(mMockDatagramController).needsWaitingForSatelliteConnected();
        mInOrder.verify(mMockDatagramController).isPollingInIdleState();
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        verifyNoMoreInteractions(mMockDatagramController);

        assertThat(mResultListener.peek()).isEqualTo(SatelliteManager.SATELLITE_RESULT_SUCCESS);
    }

    @Test
    public void testSendSatelliteDatagram_usingCommandsInterface_failure() throws  Exception {
        doReturn(false).when(mMockSatelliteModemInterface).isSatelliteServiceSupported();
        replaceInstance(PhoneFactory.class, "sPhones", null, new Phone[] {mPhone});
        doAnswer(invocation -> {
            Message message = (Message) invocation.getArguments()[0];

            mDatagramDispatcherUT.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                            new AsyncResult(message.obj, null,
                                    new SatelliteManager.SatelliteException(
                                            SatelliteManager.SATELLITE_RESULT_SERVICE_ERROR)))
                    .sendToTarget();
            return null;
        }).when(mPhone).sendSatelliteDatagram(any(Message.class), any(SatelliteDatagram.class),
                anyBoolean());

        mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, DATAGRAM_TYPE1, mDatagram,
                true, mResultListener::offer);

        processAllMessages();

        mInOrder.verify(mMockDatagramController).needsWaitingForSatelliteConnected();
        mInOrder.verify(mMockDatagramController).isPollingInIdleState();
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SERVICE_ERROR));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        verifyNoMoreInteractions(mMockDatagramController);

        assertThat(mResultListener.peek()).isEqualTo(
                SatelliteManager.SATELLITE_RESULT_SERVICE_ERROR);
    }

    @Test
    public void testSendSatelliteDatagram_DemoMode_Align_Success() throws Exception {
        mTestDemoModeDatagramDispatcher.setDemoMode(true);
        mTestDemoModeDatagramDispatcher.setDeviceAlignedWithSatellite(true);
        doReturn(false).when(mMockSatelliteModemInterface).isSatelliteServiceSupported();
        replaceInstance(PhoneFactory.class, "sPhones", null, new Phone[] {mPhone});
        doAnswer(invocation -> {
            Message message = (Message) invocation.getArguments()[0];

            mDatagramDispatcherUT.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                            new AsyncResult(message.obj, null, null))
                    .sendToTarget();
            return null;
        }).when(mPhone).sendSatelliteDatagram(any(Message.class), any(SatelliteDatagram.class),
                anyBoolean());

        mTestDemoModeDatagramDispatcher.sendSatelliteDatagram(SUB_ID, DATAGRAM_TYPE1, mDatagram,
                true, mResultListener::offer);

        processAllMessages();

        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        assertThat(mResultListener.peek()).isEqualTo(SatelliteManager.SATELLITE_RESULT_SUCCESS);
        mTestDemoModeDatagramDispatcher.setDemoMode(false);
        mTestDemoModeDatagramDispatcher.setDeviceAlignedWithSatellite(false);
    }

    @Test
    public void testSendSatelliteDatagram_DemoMode_Align_failed() throws Exception {
        long previousTimer = mTestDemoModeDatagramDispatcher.getSatelliteAlignedTimeoutDuration();
        mTestDemoModeDatagramDispatcher.setDemoMode(true);
        mTestDemoModeDatagramDispatcher.setDuration(TEST_EXPIRE_TIMER_SATELLITE_ALIGN);
        mTestDemoModeDatagramDispatcher.setDeviceAlignedWithSatellite(false);

        doReturn(false).when(mMockSatelliteModemInterface).isSatelliteServiceSupported();
        replaceInstance(PhoneFactory.class, "sPhones", null, new Phone[] {mPhone});
        doAnswer(invocation -> {
            Message message = (Message) invocation.getArguments()[0];

            mTestDemoModeDatagramDispatcher.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                            new AsyncResult(message.obj, null, null))
                    .sendToTarget();
            return null;
        }).when(mPhone).sendSatelliteDatagram(any(Message.class), any(SatelliteDatagram.class),
                anyBoolean());

        mTestDemoModeDatagramDispatcher.sendSatelliteDatagram(SUB_ID, DATAGRAM_TYPE1, mDatagram,
                true, mResultListener::offer);

        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        processAllFutureMessages();
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED),
                        anyInt(), eq(SatelliteManager.SATELLITE_RESULT_NOT_REACHABLE));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
        assertThat(mResultListener.peek()).isEqualTo(
                SatelliteManager.SATELLITE_RESULT_NOT_REACHABLE);
        mTestDemoModeDatagramDispatcher.setDemoMode(false);
        mTestDemoModeDatagramDispatcher.setDeviceAlignedWithSatellite(false);
        mTestDemoModeDatagramDispatcher.setDuration(previousTimer);
    }

    @Test
    public void testSendSatelliteDatagram_DemoMode_data_type_location_sharing() throws Exception {
        mTestDemoModeDatagramDispatcher.setDemoMode(true);
        doReturn(false).when(mMockSatelliteModemInterface).isSatelliteServiceSupported();
        replaceInstance(PhoneFactory.class, "sPhones", null, new Phone[] {mPhone});
        doAnswer(invocation -> {
            Message message = (Message) invocation.getArguments()[0];

            mDatagramDispatcherUT.obtainMessage(2 /*EVENT_SEND_SATELLITE_DATAGRAM_DONE*/,
                            new AsyncResult(message.obj, null, null))
                    .sendToTarget();
            return null;
        }).when(mPhone).sendSatelliteDatagram(any(Message.class), any(SatelliteDatagram.class),
                anyBoolean());

        mTestDemoModeDatagramDispatcher.sendSatelliteDatagram(SUB_ID, DATAGRAM_TYPE2, mDatagram,
                true, mResultListener::offer);

        processAllMessages();

        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING), eq(1),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));

        assertThat(mResultListener.peek()).isEqualTo(SatelliteManager.SATELLITE_RESULT_SUCCESS);

        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));

        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(eq(SUB_ID),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE), eq(0),
                        eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));

        mTestDemoModeDatagramDispatcher.setDemoMode(false);
        mTestDemoModeDatagramDispatcher.setDeviceAlignedWithSatellite(false);
    }

    @Test
    public void testSatelliteModemBusy_modemPollingDatagram_sendingDelayed() {
        when(mMockDatagramController.isPollingInIdleState()).thenReturn(false);

        mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, DATAGRAM_TYPE1, mDatagram,
                true, mResultListener::offer);
        processAllMessages();
        // As modem is busy receiving datagrams, sending datagram did not proceed further.
        mInOrder.verify(mMockDatagramController).needsWaitingForSatelliteConnected();
        mInOrder.verify(mMockDatagramController).isPollingInIdleState();
        verifyNoMoreInteractions(mMockDatagramController);
    }

    @Test
    public void testOnSatelliteModemStateChanged_modemStateListening() {
        mDatagramDispatcherUT.onSatelliteModemStateChanged(
                SatelliteManager.SATELLITE_MODEM_STATE_LISTENING);
        processAllMessages();
        verifyNoMoreInteractions(mMockDatagramController);
    }

    @Test
    public void testOnSatelliteModemStateChanged_modemStateOff_modemSendingDatagrams() {
        mDatagramDispatcherUT.sendSatelliteDatagram(SUB_ID, DATAGRAM_TYPE1, mDatagram,
                true, mResultListener::offer);

        mDatagramDispatcherUT.onSatelliteModemStateChanged(
                SatelliteManager.SATELLITE_MODEM_STATE_OFF);

        processAllMessages();

        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(anyInt(),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED),
                        eq(1), eq(SatelliteManager.SATELLITE_RESULT_REQUEST_ABORTED));
        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(anyInt(),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE),
                        eq(0), eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
    }

    @Test
    public void testOnSatelliteModemStateChanged_modemStateOff_modemNotSendingDatagrams() {
        mDatagramDispatcherUT.onSatelliteModemStateChanged(
                SatelliteManager.SATELLITE_MODEM_STATE_OFF);

        processAllMessages();

        mInOrder.verify(mMockDatagramController)
                .updateSendStatus(anyInt(),
                        eq(SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE),
                        eq(0), eq(SatelliteManager.SATELLITE_RESULT_SUCCESS));
    }

    private static class TestDatagramDispatcher extends DatagramDispatcher {
        private long mLong = SATELLITE_ALIGN_TIMEOUT;

        TestDatagramDispatcher(@NonNull Context context, @NonNull Looper looper,
                @NonNull DatagramController datagramController) {
            super(context, looper, datagramController);
        }

        @Override
        protected void setDemoMode(boolean isDemoMode) {
            super.setDemoMode(isDemoMode);
        }

        @Override
        protected  void setDeviceAlignedWithSatellite(boolean isAligned) {
            super.setDeviceAlignedWithSatellite(isAligned);
        }

        @Override
        protected long getSatelliteAlignedTimeoutDuration() {
            return mLong;
        }

        public void setDuration(long duration) {
            mLong = duration;
        }
    }
}
