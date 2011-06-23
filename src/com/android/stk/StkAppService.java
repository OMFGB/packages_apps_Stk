/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.stk;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.telephony.cat.AppInterface;
import com.android.internal.telephony.cat.LaunchBrowserMode;
import com.android.internal.telephony.cat.Menu;
import com.android.internal.telephony.cat.Item;
import com.android.internal.telephony.cat.ResultCode;
import com.android.internal.telephony.cat.CatCmdMessage;
import com.android.internal.telephony.cat.CatCmdMessage.BrowserSettings;
import com.android.internal.telephony.cat.CatCmdMessage.SetupEventListSettings;
import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.cat.CatResponseMessage;
import com.android.internal.telephony.cat.TextMessage;
import com.android.internal.telephony.GsmAlphabet;

import java.util.LinkedList;

import static com.android.internal.telephony.cat.CatCmdMessage.SetupEventListConstants.*;

/**
 * SIM toolkit application level service. Interacts with Telephopny messages,
 * application's launch and user input from STK UI elements.
 *
 */
public class StkAppService extends Service implements Runnable {

    // members
    private volatile Looper mServiceLooper;
    private volatile ServiceHandler mServiceHandler;
    private AppInterface mStkService;
    private Context mContext = null;
    private CatCmdMessage mMainCmd = null;
    private CatCmdMessage mCurrentCmd = null;
    private Menu mCurrentMenu = null;
    private String lastSelectedItem = null;
    private boolean mMenuIsVisibile = false;
    private boolean responseNeeded = true;
    private boolean mCmdInProgress = false;
    private NotificationManager mNotificationManager = null;
    private LinkedList<DelayedCmd> mCmdsQ = null;
    private boolean launchBrowser = false;
    private BrowserSettings mBrowserSettings = null;
    static StkAppService sInstance = null;
    private SetupEventListSettings mSetupEventListSettings = null;
    private boolean mClearSelectItem = false;
    private boolean mDisplayTextDlgIsVisibile = false;
    private CatCmdMessage mCurrentSetupEventCmd = null;

    // Used for setting FLAG_ACTIVITY_NO_USER_ACTION when
    // creating an intent.
    private enum InitiatedByUserAction {
        yes,            // The action was started via a user initiated action
        unknown,        // Not known for sure if user initated the action
    }

    // constants
    static final String OPCODE = "op";
    static final String CMD_MSG = "cmd message";
    static final String RES_ID = "response id";
    static final String MENU_SELECTION = "menu selection";
    static final String INPUT = "input";
    static final String HELP = "help";
    static final String CONFIRMATION = "confirm";
    static final String SCREEN_STATUS = "screen status";

    // These below constants are used for SETUP_EVENT_LIST
    static final String SETUP_EVENT_TYPE = "event";
    static final String SETUP_EVENT_CAUSE = "cause";

    // operations ids for different service functionality.
    static final int OP_CMD = 1;
    static final int OP_RESPONSE = 2;
    static final int OP_LAUNCH_APP = 3;
    static final int OP_END_SESSION = 4;
    static final int OP_BOOT_COMPLETED = 5;
    private static final int OP_DELAYED_MSG = 6;
    static final int OP_IDLE_SCREEN = 7;
    static final int OP_BROWSER_TERMINATION = 8;
    static final int OP_LOCALE_CHANGED = 9;

    //Invalid SetupEvent
    static final int INVALID_SETUP_EVENT = 0xFF;

    // Response ids
    static final int RES_ID_MENU_SELECTION = 11;
    static final int RES_ID_INPUT = 12;
    static final int RES_ID_CONFIRM = 13;
    static final int RES_ID_DONE = 14;

    static final int RES_ID_TIMEOUT = 20;
    static final int RES_ID_BACKWARD = 21;
    static final int RES_ID_END_SESSION = 22;
    static final int RES_ID_EXIT = 23;

    private static final String PACKAGE_NAME = "com.android.stk";
    private static final String MENU_ACTIVITY_NAME =
                                        PACKAGE_NAME + ".CatMenuActivity";
    private static final String INPUT_ACTIVITY_NAME =
                                        PACKAGE_NAME + ".CatInputActivity";

    // Notification id used to display Idle Mode text in NotificationManager.
    private static final int STK_NOTIFICATION_ID = 333;
    private CatCmdMessage mIdleModeTextCmd = null;
    private boolean mDisplayText = false;
    private boolean mScreenIdle = true;

    // Inner class used for queuing telephony messages (proactive commands,
    // session end) while the service is busy processing a previous message.
    private class DelayedCmd {
        // members
        int id;
        CatCmdMessage msg;

        DelayedCmd(int id, CatCmdMessage msg) {
            this.id = id;
            this.msg = msg;
        }
    }

    @Override
    public void onCreate() {
        // Initialize members
        mStkService = com.android.internal.telephony.cat.CatService
                .getInstance();

        // NOTE mStkService is a singleton and continues to exist even if the GSMPhone is disposed
        //   after the radio technology change from GSM to CDMA so the PHONE_TYPE_CDMA check is
        //   needed. In case of switching back from CDMA to GSM the GSMPhone constructor updates
        //   the instance. (TODO: test).
        if ((mStkService == null)
                && (TelephonyManager.getDefault().getPhoneType()
                                != TelephonyManager.PHONE_TYPE_CDMA)) {
            CatLog.d(this, " Unable to get Service handle");
            return;
        }

        mCmdsQ = new LinkedList<DelayedCmd>();
        Thread serviceThread = new Thread(null, this, "Stk App Service");
        serviceThread.start();
        mContext = getBaseContext();
        mNotificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        sInstance = this;
    }

    @Override
    public void onStart(Intent intent, int startId) {
        waitForLooper();

        // onStart() method can be passed a null intent
        // TODO: replace onStart() with onStartCommand()
        if (intent == null) {
            return;
        }

        Bundle args = intent.getExtras();

        if (args == null) {
            return;
        }

        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = args.getInt(OPCODE);
        switch(msg.arg1) {
        case OP_CMD:
            msg.obj = args.getParcelable(CMD_MSG);
            break;
        case OP_RESPONSE:
        case OP_BROWSER_TERMINATION:
        case OP_LOCALE_CHANGED:
            msg.obj = args;
            /* falls through */
        case OP_LAUNCH_APP:
        case OP_END_SESSION:
        case OP_BOOT_COMPLETED:
            break;
        default:
            return;
        }
        mServiceHandler.sendMessage(msg);
    }

    @Override
    public void onDestroy() {
        waitForLooper();
        mServiceLooper.quit();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void run() {
        Looper.prepare();

        mServiceLooper = Looper.myLooper();
        mServiceHandler = new ServiceHandler();

        Looper.loop();
    }

    /*
     * Package api used by StkMenuActivity to indicate if its on the foreground.
     */
    void indicateMenuVisibility(boolean visibility) {
        mMenuIsVisibile = visibility;
    }

    /*
     * Package api used by StkMenuActivity to get its Menu parameter.
     */
    Menu getMenu() {
        return mCurrentMenu;
    }

    /*
     * Package api used by UI Activities and Dialogs to communicate directly
     * with the service to deliver state information and parameters.
     */
    static StkAppService getInstance() {
        return sInstance;
    }

    private void waitForLooper() {
        while (mServiceHandler == null) {
            synchronized (this) {
                try {
                    wait(100);
                } catch (InterruptedException e) {
                }
            }
        }
    }

    private final class ServiceHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            int opcode = msg.arg1;

            switch (opcode) {
            case OP_LAUNCH_APP:
                if (mMainCmd == null) {
                    // nothing todo when no SET UP MENU command didn't arrive.
                    return;
                }
                launchMenuActivity(null);
                break;
            case OP_CMD:
                CatCmdMessage cmdMsg = (CatCmdMessage) msg.obj;
                // There are two types of commands:
                // 1. Interactive - user's response is required.
                // 2. Informative - display a message, no interaction with the user.
                //
                // Informative commands can be handled immediately without any delay.
                // Interactive commands can't override each other. So if a command
                // is already in progress, we need to queue the next command until
                // the user has responded or a timeout expired.
                if (!isCmdInteractive(cmdMsg)) {
                    handleCmd(cmdMsg);
                } else {
                    if (!mCmdInProgress) {
                        mCmdInProgress = true;
                        handleCmd((CatCmdMessage) msg.obj);
                    } else {
                        mCmdsQ.addLast(new DelayedCmd(OP_CMD,
                                (CatCmdMessage) msg.obj));
                    }
                }
                break;
            case OP_RESPONSE:
                if (responseNeeded) {
                    handleCmdResponse((Bundle) msg.obj);
                }
                // call delayed commands if needed.
                if (mCmdsQ.size() != 0) {
                    callDelayedMsg();
                } else {
                    mCmdInProgress = false;
                }
                // reset response needed state var to its original value.
                responseNeeded = true;
                break;
            case OP_END_SESSION:
                if (!mCmdInProgress) {
                    mCmdInProgress = true;
                    handleSessionEnd();
                } else {
                    mCmdsQ.addLast(new DelayedCmd(OP_END_SESSION, null));
                }
                break;
            case OP_BOOT_COMPLETED:
                CatLog.d(this, "OP_BOOT_COMPLETED");
                if (mMainCmd == null) {
                    StkAppInstaller.unInstall(mContext);
                }
                break;
            case OP_DELAYED_MSG:
                handleDelayedCmd();
                break;
            case OP_BROWSER_TERMINATION:
                CatLog.d(this, "Browser Closed");
                checkForSetupEvent(BROWSER_TERMINATION_EVENT,(Bundle) msg.obj);
                break;
            case OP_IDLE_SCREEN:
                handleScreenStatus((Bundle) msg.obj);
                break;
            case OP_LOCALE_CHANGED:
                CatLog.d(this, "Locale Changed");
                checkForSetupEvent(LANGUAGE_SELECTION_EVENT,(Bundle) msg.obj);
                break;
            }
        }
    }

    private void handleScreenStatus(Bundle args) {
        mScreenIdle = args.getBoolean(SCREEN_STATUS);

        // If the idle screen event is present in the list need to send the
        // response to SIM.
        if (mScreenIdle) {
            CatLog.d(this, "Need to send IDLE SCREEN Available event to SIM");
            checkForSetupEvent(IDLE_SCREEN_AVAILABLE_EVENT, null);
        }
        if (mIdleModeTextCmd != null) {
           launchIdleText();
        }
        if (mDisplayText) {
            if (!mScreenIdle) {
                sendScreenBusyResponse();
            } else {
                launchTextDialog();
            }
            mDisplayText = false;
            // If an idle text proactive command is set then the
            // request for getting screen status still holds true.
            if (mIdleModeTextCmd == null) {
                Intent StkIntent = new Intent(AppInterface.CHECK_SCREEN_IDLE_ACTION);
                StkIntent.putExtra("SCREEN_STATUS_REQUEST", false);
                sendBroadcast(StkIntent);
            }
        }
    }

    private void sendScreenBusyResponse() {
        if (mCurrentCmd == null) {
            return;
        }
        CatResponseMessage resMsg = new CatResponseMessage(mCurrentCmd);
        CatLog.d(this, "SCREEN_BUSY");
        resMsg.setResultCode(ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS);
        mStkService.onCmdResponse(resMsg);
        // reset response needed state var to its original value.
        responseNeeded = true;
        if (mCmdsQ.size() != 0) {
            callDelayedMsg();
        } else {
            mCmdInProgress = false;
        }
    }

    private void sendResponse(int resId) {
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = OP_RESPONSE;
        Bundle args = new Bundle();
        args.putInt(StkAppService.RES_ID, resId);
        msg.obj = args;
        mServiceHandler.sendMessage(msg);
    }

    private boolean isCmdInteractive(CatCmdMessage cmd) {
        switch (cmd.getCmdType()) {
        case SEND_DTMF:
        case SEND_SMS:
        case SEND_SS:
        case SEND_USSD:
        case SET_UP_IDLE_MODE_TEXT:
        case SET_UP_MENU:
        case SET_UP_EVENT_LIST:
            return false;
        }

        return true;
    }

    private void handleDelayedCmd() {
        if (mCmdsQ.size() != 0) {
            DelayedCmd cmd = mCmdsQ.poll();
            switch (cmd.id) {
            case OP_CMD:
                handleCmd(cmd.msg);
                break;
            case OP_END_SESSION:
                handleSessionEnd();
                break;
            }
        }
    }

    private void callDelayedMsg() {
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = OP_DELAYED_MSG;
        mServiceHandler.sendMessage(msg);
    }

    private void handleSessionEnd() {
        mCurrentCmd = mMainCmd;
        lastSelectedItem = null;
        // In case of SET UP MENU command which removed the app, don't
        // update the current menu member.
        if (mCurrentMenu != null && mMainCmd != null) {
            mCurrentMenu = mMainCmd.getMenu();
        }
        if (mMenuIsVisibile) {
            launchMenuActivity(null);
        }
        if (mCmdsQ.size() != 0) {
            callDelayedMsg();
        } else {
            mCmdInProgress = false;
        }
        // In case a launch browser command was just confirmed, launch that url.
        if (launchBrowser) {
            launchBrowser = false;
            launchBrowser(mBrowserSettings);
        }
    }

    private void handleCmd(CatCmdMessage cmdMsg) {
        if (cmdMsg == null) {
            return;
        }
        // save local reference for state tracking.
        mCurrentCmd = cmdMsg;
        boolean waitForUsersResponse = true;

        CatLog.d(this, cmdMsg.getCmdType().name());
        switch (cmdMsg.getCmdType()) {
        case DISPLAY_TEXT:
            TextMessage msg = cmdMsg.geTextMessage();
            responseNeeded = msg.responseNeeded;
            if (lastSelectedItem != null) {
                msg.title = lastSelectedItem;
            } else if (mMainCmd != null){
                msg.title = mMainCmd.getMenu().title;
            } else {
                // TODO: get the carrier name from the SIM
                msg.title = "";
            }
            launchTextDialog();
            break;
        case SELECT_ITEM:
            mCurrentMenu = cmdMsg.getMenu();
            launchMenuActivity(cmdMsg.getMenu());
            break;
        case SET_UP_MENU:
            mMainCmd = mCurrentCmd;
            mCurrentMenu = cmdMsg.getMenu();
            if (removeMenu()) {
                CatLog.d(this, "Uninstall App");
                mCurrentMenu = null;
                StkAppInstaller.unInstall(mContext);
            } else {
                CatLog.d(this, "Install App");
                StkAppInstaller.install(mContext);
            }
            if (mMenuIsVisibile) {
                launchMenuActivity(null);
            }
            break;
        case GET_INPUT:
        case GET_INKEY:
            launchInputActivity();
            break;
        case SET_UP_IDLE_MODE_TEXT:
            waitForUsersResponse = false;
            launchIdleText();
            mIdleModeTextCmd = mCurrentCmd;
            TextMessage idleModeText = mCurrentCmd.geTextMessage();
            // Send intent to ActivityManagerService to get the screen status
            Intent idleStkIntent  = new Intent(AppInterface.CHECK_SCREEN_IDLE_ACTION);
            if (idleModeText != null) {
                if (idleModeText.text != null) {
                    idleStkIntent.putExtra("SCREEN_STATUS_REQUEST",true);
                } else {
                    idleStkIntent.putExtra("SCREEN_STATUS_REQUEST",false);
                    launchIdleText();
                    mIdleModeTextCmd = null;
                }
            }
            CatLog.d(this, "set up idle mode");
            mCurrentCmd = mMainCmd;
            sendBroadcast(idleStkIntent);
            break;
        case SEND_DTMF:
        case SEND_SMS:
        case SEND_SS:
        case SEND_USSD:
            waitForUsersResponse = false;
            launchEventMessage();
            break;
        case LAUNCH_BROWSER:
            launchConfirmationDialog(mCurrentCmd.geTextMessage());
            break;
        case CLOSE_CHANNEL:
        case RECEIVE_DATA:
        case SEND_DATA:
        case GET_CHANNEL_STATUS:
            waitForUsersResponse = false;
            launchEventMessage();
            break;
        case OPEN_CHANNEL:
            launchConfirmationDialog(mCurrentCmd.getCallSettings().confirmMsg);
            break;
        case SET_UP_CALL:
            launchConfirmationDialog(mCurrentCmd.getCallSettings().confirmMsg);
            break;
        case PLAY_TONE:
            launchToneDialog();
            break;
        case SET_UP_EVENT_LIST:
            mSetupEventListSettings = mCurrentCmd.getSetEventList();
            mCurrentSetupEventCmd = mCurrentCmd;
            mCurrentCmd = mMainCmd;
            if ((mIdleModeTextCmd == null) && (!mDisplayText)) {

                for (int i = 0; i < mSetupEventListSettings.eventList.length; i++) {
                    if (mSetupEventListSettings.eventList[i] == IDLE_SCREEN_AVAILABLE_EVENT) {
                        CatLog.d(this," IDLE_SCREEN_AVAILABLE_EVENT present in List");
                        // Request ActivityManagerService to get the screen status
                        Intent StkIntent = new Intent(AppInterface.CHECK_SCREEN_IDLE_ACTION);
                        StkIntent.putExtra("SCREEN_STATUS_REQUEST", true);
                        sendBroadcast(StkIntent);
                        break;
                    }
                }
            }
            break;
        }

        if (!waitForUsersResponse) {
            if (mCmdsQ.size() != 0) {
                callDelayedMsg();
            } else {
                mCmdInProgress = false;
            }
        }
    }

    private void handleCmdResponse(Bundle args) {
        if (mCurrentCmd == null) {
            return;
        }
        CatResponseMessage resMsg = new CatResponseMessage(mCurrentCmd);

        // set result code
        boolean helpRequired = args.getBoolean(HELP, false);

        switch(args.getInt(RES_ID)) {
        case RES_ID_MENU_SELECTION:
            CatLog.d(this, "RES_ID_MENU_SELECTION");
            int menuSelection = args.getInt(MENU_SELECTION);
            switch(mCurrentCmd.getCmdType()) {
            case SET_UP_MENU:
            case SELECT_ITEM:
                lastSelectedItem = getItemName(menuSelection);
                if (helpRequired) {
                    resMsg.setResultCode(ResultCode.HELP_INFO_REQUIRED);
                } else {
                    resMsg.setResultCode(mCurrentCmd.hasIconLoadFailed() ?
                            ResultCode.PRFRMD_ICON_NOT_DISPLAYED : ResultCode.OK);
                }
                resMsg.setMenuSelection(menuSelection);
                break;
            }
            break;
        case RES_ID_INPUT:
            CatLog.d(this, "RES_ID_INPUT");
            String input = args.getString(INPUT);
            if (mCurrentCmd.geInput().yesNo) {
                boolean yesNoSelection = input
                        .equals(StkInputActivity.YES_STR_RESPONSE);
                resMsg.setYesNo(yesNoSelection);
            } else {
                if (helpRequired) {
                    resMsg.setResultCode(ResultCode.HELP_INFO_REQUIRED);
                } else {
                    resMsg.setResultCode(mCurrentCmd.hasIconLoadFailed() ?
                            ResultCode.PRFRMD_ICON_NOT_DISPLAYED : ResultCode.OK);
                    resMsg.setInput(input);
                }
            }
            break;
        case RES_ID_CONFIRM:
            CatLog.d(this, "RES_ID_CONFIRM");
            boolean confirmed = args.getBoolean(CONFIRMATION);
            switch (mCurrentCmd.getCmdType()) {
            case DISPLAY_TEXT:
                if (confirmed) {
                    resMsg.setResultCode(mCurrentCmd.hasIconLoadFailed() ?
                            ResultCode.PRFRMD_ICON_NOT_DISPLAYED : ResultCode.OK);
                } else {
                    resMsg.setResultCode(ResultCode.UICC_SESSION_TERM_BY_USER);
                }
                break;
            case LAUNCH_BROWSER:
                resMsg.setResultCode(confirmed ? ResultCode.OK
                        : ResultCode.UICC_SESSION_TERM_BY_USER);
                if (confirmed) {
                    launchBrowser = true;
                    mBrowserSettings = mCurrentCmd.getBrowserSettings();
                }
                break;
            case SET_UP_CALL:
                resMsg.setResultCode(ResultCode.OK);
                resMsg.setConfirmation(confirmed);
                if (confirmed) {
                    launchCallMsg();
                }
                break;
            }
            break;
        case RES_ID_DONE:
            resMsg.setResultCode(ResultCode.OK);
            break;
        case RES_ID_BACKWARD:
            CatLog.d(this, "RES_ID_BACKWARD");
            resMsg.setResultCode(ResultCode.BACKWARD_MOVE_BY_USER);
            break;
        case RES_ID_END_SESSION:
            CatLog.d(this, "RES_ID_END_SESSION");
            resMsg.setResultCode(ResultCode.UICC_SESSION_TERM_BY_USER);
            break;
        case RES_ID_TIMEOUT:
            CatLog.d(this, "RES_ID_TIMEOUT");
            // GCF test-case 27.22.4.1.1 Expected Sequence 1.5 (DISPLAY TEXT,
            // Clear message after delay, successful) expects result code OK.
            // If the command qualifier specifies no user response is required
            // then send OK instead of NO_RESPONSE_FROM_USER
            if ((mCurrentCmd.getCmdType().value() == AppInterface.CommandType.DISPLAY_TEXT
                    .value())
                    && (mCurrentCmd.geTextMessage().userClear == false)) {
                resMsg.setResultCode(ResultCode.OK);
            } else {
                resMsg.setResultCode(ResultCode.NO_RESPONSE_FROM_USER);
            }
            break;
        default:
            CatLog.d(this, "Unknown result id");
            return;
        }
        mStkService.onCmdResponse(resMsg);
    }

    /**
     * Returns 0 or FLAG_ACTIVITY_NO_USER_ACTION, 0 means the user initiated the action.
     *
     * @param userAction If the userAction is yes then we always return 0 otherwise
     * mMenuIsVisible is used to determine what to return. If mMenuIsVisible is true
     * then we are the foreground app and we'll return 0 as from our perspective a
     * user action did cause. If it's false than we aren't the foreground app and
     * FLAG_ACTIVITY_NO_USER_ACTION is returned.
     *
     * @return 0 or FLAG_ACTIVITY_NO_USER_ACTION
     */
    private int getFlagActivityNoUserAction(InitiatedByUserAction userAction) {
        return ((userAction == InitiatedByUserAction.yes) | mMenuIsVisibile) ?
                                                    0 : Intent.FLAG_ACTIVITY_NO_USER_ACTION;
    }

    private void launchMenuActivity(Menu menu) {
        Intent newIntent = new Intent(Intent.ACTION_VIEW);
        newIntent.setClassName(PACKAGE_NAME, MENU_ACTIVITY_NAME);
        int intentFlags = Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP;
        if (menu == null) {
            // We assume this was initiated by the user pressing the tool kit icon
            intentFlags |= getFlagActivityNoUserAction(InitiatedByUserAction.yes);

            newIntent.putExtra("STATE", StkMenuActivity.STATE_MAIN);
        } else {
            // We don't know and we'll let getFlagActivityNoUserAction decide.
            intentFlags |= getFlagActivityNoUserAction(InitiatedByUserAction.unknown);

            newIntent.putExtra("STATE", StkMenuActivity.STATE_SECONDARY);
        }
        newIntent.setFlags(intentFlags);
        mContext.startActivity(newIntent);
    }

    private void launchInputActivity() {
        Intent newIntent = new Intent(Intent.ACTION_VIEW);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | getFlagActivityNoUserAction(InitiatedByUserAction.unknown));
        newIntent.setClassName(PACKAGE_NAME, INPUT_ACTIVITY_NAME);
        newIntent.putExtra("INPUT", mCurrentCmd.geInput());
        mContext.startActivity(newIntent);
    }

    private void launchTextDialog() {
        Intent newIntent = new Intent(this, StkDialogActivity.class);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | getFlagActivityNoUserAction(InitiatedByUserAction.unknown));
        newIntent.putExtra("TEXT", mCurrentCmd.geTextMessage());
        startActivity(newIntent);
    }

    private void sendSetUpEventResponse(int event, byte[] addedInfo) {
        CatLog.d(this, "sendSetUpEventResponse: event : " + event);

        if (mCurrentSetupEventCmd == null){
            return;
        }

        CatResponseMessage resMsg = new CatResponseMessage(mCurrentSetupEventCmd);

        resMsg.setResultCode(ResultCode.OK);
        resMsg.setEventDownload(event, addedInfo);

        mStkService.onCmdResponse(resMsg);
    }

    private void checkForSetupEvent(int event, Bundle args) {
        boolean eventPresent = false;
        byte[] addedInfo = null;
        CatLog.d(this, "Event :" + event);

        if (mSetupEventListSettings != null) {
            /* Checks if the event is present in the EventList updated by last
             * SetupEventList Proactive Command */
            for (int i = 0; i < mSetupEventListSettings.eventList.length; i++) {
                 if (event == mSetupEventListSettings.eventList[i]) {
                     eventPresent =  true;
                     break;
                 }
            }

            /* If Event is present send the response to ICC */
            if (eventPresent == true) {
                CatLog.d(this, " Event " + event + "exists in the EventList");

                switch (event) {
                    case BROWSER_TERMINATION_EVENT:
                        int browserTerminationCause = args.getInt(AppInterface.BROWSER_TERMINATION_CAUSE);
                        CatLog.d(this, "BrowserTerminationCause: "+ browserTerminationCause);
                        //Single byte is sufficient to represent browser termination cause.
                        addedInfo = new byte[1];
                        addedInfo[0] = (byte) browserTerminationCause;
                        sendSetUpEventResponse(event, addedInfo);
                        break;
                    case IDLE_SCREEN_AVAILABLE_EVENT:
                        sendSetUpEventResponse(event, addedInfo);
                        removeSetUpEvent(event);
                        break;
                    case LANGUAGE_SELECTION_EVENT:
                        String language =  mContext.getResources().getConfiguration().locale.getLanguage();
                        CatLog.d(this, "language: " + language);
                        // Each language code is a pair of alpha-numeric characters. Each alpha-numeric
                        // character shall be coded on one byte using the SMS default 7-bit coded alphabet
                        addedInfo = GsmAlphabet.stringToGsm8BitPacked(language);
                        sendSetUpEventResponse(event, addedInfo);
                        break;
                    default:
                        break;
                }
            } else {
                CatLog.d(this, " Event does not exist in the EventList");
            }
        } else {
            CatLog.d(this, "SetupEventList is not received. Ignoring the event: " + event);
        }
    }

    private void  removeSetUpEvent(int event) {
        CatLog.d(this, "Remove Event :" + event);

        if (mSetupEventListSettings != null) {
            /*
             * Make new  Eventlist without the event
             */
            for (int i = 0; i < mSetupEventListSettings.eventList.length; i++) {
                if (event == mSetupEventListSettings.eventList[i]) {
                    mSetupEventListSettings.eventList[i] = INVALID_SETUP_EVENT;
                    break;
                }
            }
        }
    }

    private void launchEventMessage() {
        TextMessage msg = mCurrentCmd.geTextMessage();
        if (msg == null || msg.text == null) {
            return;
        }
        Toast toast = new Toast(mContext.getApplicationContext());
        LayoutInflater inflate = (LayoutInflater) mContext
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflate.inflate(R.layout.stk_event_msg, null);
        TextView tv = (TextView) v
                .findViewById(com.android.internal.R.id.message);
        ImageView iv = (ImageView) v
                .findViewById(com.android.internal.R.id.icon);
        if (msg.icon != null) {
            iv.setImageBitmap(msg.icon);
        } else {
            iv.setVisibility(View.GONE);
        }
        /** In case of 'self explanatory' stkapp should display the specified
         ** icon in proactive command (but not the alpha string).
         ** If icon is non-self explanatory and if the icon could not be displayed
         ** then alpha string or text data should be displayed
         ** Ref: ETSI 102.223,section 6.5.4
         **/
        if (mCurrentCmd.hasIconLoadFailed() || !msg.iconSelfExplanatory) {
            tv.setText(msg.text);
        }

        toast.setView(v);
        toast.setDuration(Toast.LENGTH_LONG);
        toast.setGravity(Gravity.BOTTOM, 0, 0);
        toast.show();
    }

    private void launchConfirmationDialog(TextMessage msg) {
        msg.title = lastSelectedItem;
        Intent newIntent = new Intent(this, StkDialogActivity.class);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | getFlagActivityNoUserAction(InitiatedByUserAction.unknown));
        newIntent.putExtra("TEXT", msg);
        startActivity(newIntent);
    }

    private void launchBrowser(BrowserSettings settings) {
        if (settings == null) {
            return;
        }
        // Set browser launch mode
        Intent intent = new Intent();
        intent.setClassName("com.android.browser",
                "com.android.browser.BrowserActivity");

        // to launch home page, make sure that data Uri is null.
        Uri data = null;
        if (settings.url != null) {
            data = Uri.parse(settings.url);
        }
        intent.setData(data);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        switch (settings.mode) {
        case USE_EXISTING_BROWSER:
            intent.setAction(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            break;
        case LAUNCH_NEW_BROWSER:
            intent.setAction(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            break;
        case LAUNCH_IF_NOT_ALREADY_LAUNCHED:
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            break;
        }
        // start browser activity
        startActivity(intent);
        // a small delay, let the browser start, before processing the next command.
        // this is good for scenarios where a related DISPLAY TEXT command is
        // followed immediately.
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {}
    }

    private void launchCallMsg() {
        TextMessage msg = mCurrentCmd.getCallSettings().callMsg;
        if (msg.text == null || msg.text.length() == 0) {
            return;
        }
        msg.title = lastSelectedItem;

        Toast toast = Toast.makeText(mContext.getApplicationContext(), msg.text,
                Toast.LENGTH_LONG);
        toast.setGravity(Gravity.BOTTOM, 0, 0);
        toast.show();
    }

    private void launchIdleText() {
        TextMessage msg = mIdleModeTextCmd.geTextMessage();
        if (msg.text == null) {
            mNotificationManager.cancel(STK_NOTIFICATION_ID);
        } else {
            Notification notification = new Notification();
            RemoteViews contentView = new RemoteViews(
                    PACKAGE_NAME,
                    com.android.internal.R.layout.status_bar_latest_event_content);

            notification.flags |= Notification.FLAG_NO_CLEAR;
            notification.icon = com.android.internal.R.drawable.stat_notify_sim_toolkit;
            /** In case of 'self explanatory' stkapp should display the specified
             ** icon in proactive command (but not the alpha string).
             ** If icon is non-self explanatory and if the icon could not be displayed
             ** then alpha string or text data should be displayed
             ** Ref: ETSI 102.223,section 6.5.4
             **/
            if (mCurrentCmd.hasIconLoadFailed() || !msg.iconSelfExplanatory) {
                notification.tickerText = msg.text;
                contentView.setTextViewText(com.android.internal.R.id.text,
                        msg.text);
            }
            if (msg.icon != null) {
                contentView.setImageViewBitmap(com.android.internal.R.id.icon,
                        msg.icon);
            } else {
                contentView
                        .setImageViewResource(
                                com.android.internal.R.id.icon,
                                com.android.internal.R.drawable.stat_notify_sim_toolkit);
            }
            notification.contentView = contentView;
            notification.contentIntent = PendingIntent.getService(mContext, 0,
                    new Intent(mContext, StkAppService.class), 0);

            mNotificationManager.notify(STK_NOTIFICATION_ID, notification);
        }
    }

    private void launchToneDialog() {
        Intent newIntent = new Intent(this, ToneDialog.class);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | getFlagActivityNoUserAction(InitiatedByUserAction.unknown));
        newIntent.putExtra("TEXT", mCurrentCmd.geTextMessage());
        newIntent.putExtra("TONE", mCurrentCmd.getToneSettings());
        startActivity(newIntent);
    }

    private String getItemName(int itemId) {
        Menu menu = mCurrentCmd.getMenu();
        if (menu == null) {
            return null;
        }
        for (Item item : menu.items) {
            if (item.id == itemId) {
                return item.text;
            }
        }
        return null;
    }

    private boolean removeMenu() {
        try {
            if (mCurrentMenu.items.size() == 1 &&
                mCurrentMenu.items.get(0) == null) {
                return true;
            }
        } catch (NullPointerException e) {
            CatLog.d(this, "Unable to get Menu's items size");
            return true;
        }
        return false;
    }
}
