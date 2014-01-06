/*
 * Kontalk Android client
 * Copyright (C) 2014 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.service;

import static org.kontalk.ui.MessagingNotification.NOTIFICATION_ID_UPLOADING;
import static org.kontalk.ui.MessagingNotification.NOTIFICATION_ID_UPLOAD_ERROR;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import org.kontalk.GCMIntentService;
import org.kontalk.R;
import org.kontalk.authenticator.Authenticator;
import org.kontalk.client.ClientConnection;
import org.kontalk.client.ClientListener;
import org.kontalk.client.EndpointServer;
import org.kontalk.client.MessageSender;
import org.kontalk.client.Protocol;
import org.kontalk.client.Protocol.AuthenticateResponse;
import org.kontalk.client.Protocol.LoginResponse;
import org.kontalk.client.Protocol.LoginResponse.LoginStatus;
import org.kontalk.client.Protocol.Mailbox;
import org.kontalk.client.Protocol.ServerInfoResponse;
import org.kontalk.client.Protocol.UserInfoUpdateRequest;
import org.kontalk.client.Protocol.UserInfoUpdateResponse;
import org.kontalk.client.Protocol.UserInfoUpdateResponse.UserInfoUpdateStatus;
import org.kontalk.client.ReceivedJob;
import org.kontalk.client.RevalidateJob;
import org.kontalk.client.ServerinfoJob;
import org.kontalk.client.TxListener;
import org.kontalk.client.UserPresenceRequestJob;
import org.kontalk.data.Contact;
import org.kontalk.message.AbstractMessage;
import org.kontalk.message.ImageMessage;
import org.kontalk.message.ReceiptEntry;
import org.kontalk.message.ReceiptEntry.ReceiptEntryList;
import org.kontalk.message.ReceiptMessage;
import org.kontalk.message.UserPresenceData;
import org.kontalk.message.UserPresenceMessage;
import org.kontalk.message.VCardMessage;
import org.kontalk.provider.MessagesProvider;
import org.kontalk.provider.MyMessages.Messages;
import org.kontalk.provider.UsersProvider;
import org.kontalk.sync.SyncAdapter;
import org.kontalk.ui.ComposeMessageFragment;
import org.kontalk.ui.ConversationList;
import org.kontalk.ui.MessagingNotification;
import org.kontalk.ui.MessagingPreferences;
import org.kontalk.ui.ProgressNotificationBuilder;
import org.kontalk.util.MediaStorage;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gcm.GCMRegistrar;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;


/**
 * The Message Center Service.
 * This service manages the polling of incoming messages - broadcasting them
 * using broadcast intents - and also the outgoing messages.
 * @author Daniele Ricci
 * @version 1.0
 */
public class MessageCenterService extends Service
        implements MessageListener, TxListener, RequestListener, ClientListener {

    private static final String TAG = MessageCenterService.class.getSimpleName();

    /** How much time before a wakeup alarm triggers. */
    private final static int DEFAULT_WAKEUP_TIME = 900000;

    public static final String ACTION_RESTART = "org.kontalk.RESTART";
    public static final String ACTION_IDLE = "org.kontalk.IDLE";
    public static final String ACTION_HOLD = "org.kontalk.HOLD";
    public static final String ACTION_RELEASE = "org.kontalk.RELEASE";
    public static final String ACTION_C2DM_START = "org.kontalk.CD2M_START";
    public static final String ACTION_C2DM_STOP = "org.kontalk.CD2M_STOP";
    public static final String ACTION_C2DM_REGISTERED = "org.kontalk.C2DM_REGISTERED";
    public static final String ACTION_UPDATE_STATUS = "org.kontalk.UPDATE_STATUS";

    // broadcasted intents
    public static final String ACTION_CONNECTED = "org.kontalk.connected";
    public static final String ACTION_USER_PRESENCE = "org.kontalk.USER_PRESENCE";

    public static final String MESSAGE_RECEIVED = "org.kontalk.MESSAGE_RECEIVED";

    public static final String GCM_REGISTRATION_ID = "org.kontalk.GCM_REGISTRATION_ID";

    private ProgressNotificationBuilder mNotificationBuilder;
    private NotificationManager mNotificationManager;

    private Notification mCurrentNotification;
    private long mTotalBytes;

    private RequestWorker mRequestWorker;
    private Account mAccount;

    private Map<String, Byte> mPresenceListeners = new HashMap<String, Byte>();

    /** Push notifications enabled flag. */
    private boolean mPushNotifications;
    /** Server push sender id. This is static so {@link GCMIntentService} can see it. */
    private static String mPushSenderId;
    /** GCM registration id. */
    private String mPushRegistrationId;
    /** txId used in the UserInfoUpdate request. */
    protected String mPushRequestTxId;
    /** {@link RequestJob} for sending push registration id to server. */
    protected RequestJob mPushRequestJob;
    /** Flag marking a currently ongoing GCM registration cycle (unregister/register) */
    private boolean mPushRegistrationCycle;

    /** Used in case ClientThread is down. */
    private int mRefCount;

    /** Private received job instance for message confirmation queueing. */
    private ReceivedJob mReceivedJob;

    private AccountManager mAccountManager;
    private final OnAccountsUpdateListener mAccountsListener = new OnAccountsUpdateListener() {
        @Override
        public void onAccountsUpdated(Account[] accounts) {
            // restart workers
            Account my = null;
            for (int i = 0; i < accounts.length; i++) {
                if (accounts[i].type.equals(Authenticator.ACCOUNT_TYPE)) {
                    my = accounts[i];
                    break;
                }
            }

            // account removed!!! Shutdown everything.
            if (my == null) {
                Log.w(TAG, "my account has been removed, shutting down");
                // delete all messages
                MessagesProvider.deleteDatabase(MessageCenterService.this);
                stopSelf();
            }
        }
    };

    private WakeLock mWakeLock;	// created in onCreate
    private LocalBroadcastManager mLocalBroadcastManager;   // created in onCreate
    private MessageRequestListener mMessageRequestListener; // created in onCreate

    private final IBinder mBinder = new MessageCenterInterface();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Compatibility with Android 1.6
     * FIXME this should probably go away since we are using 2.2 features...
     */
    @Override
    public void onStart(Intent intent, int startId) {
        onStartCommand(intent, 0, startId);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mNotificationManager == null)
            mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        //Log.d(TAG, "Message Center starting - " + intent);
        boolean execStart = false;

        if (intent != null) {
            String action = intent.getAction();

            // C2DM hash registered!
            if (ACTION_C2DM_REGISTERED.equals(action)) {
                String regId = intent.getStringExtra(GCM_REGISTRATION_ID);
                // registration cycle under way
                if (regId == null && mPushRegistrationCycle) {
                    mPushRegistrationCycle = false;
                    gcmRegister();
                }
                else
                    setPushRegistrationId(regId);
            }

            // start C2DM registration
            else if (ACTION_C2DM_START.equals(action)) {
                setPushNotifications(true);
            }

            // unregister from C2DM
            else if (ACTION_C2DM_STOP.equals(action)) {
                setPushNotifications(false);
            }

            // idle - schedule shutdown
            else if (ACTION_IDLE.equals(action)) {
                // send idle signals to worker threads
                if (mRequestWorker != null)
                    mRequestWorker.idle();
            }

            // hold - increment reference count
            else if (ACTION_HOLD.equals(action)) {
                mRefCount++;
                if (mRequestWorker != null)
                    mRequestWorker.hold();

                // proceed to start only if network is available
                execStart = isNetworkConnectionAvailable(this) && !isOfflineMode(this);
            }

            // release - decrement reference count
            else if (ACTION_RELEASE.equals(action)) {
                mRefCount--;
                if (mRequestWorker != null)
                    mRequestWorker.release();
            }

            // normal start
            else {
                execStart = true;
            }

            // normal start
            if (execStart) {
                Bundle extras = intent.getExtras();
                String serverUrl = (String) extras.get(EndpointServer.class.getName());

                mPushNotifications = MessagingPreferences.getPushNotificationsEnabled(this);
                mAccount = Authenticator.getDefaultAccount(this);
                if (mAccount == null) {
                    stopSelf();
                }
                else {
                    // stop first
                    if (ACTION_RESTART.equals(action)) {
                        stop();
                    }
                    else if (ACTION_UPDATE_STATUS.equals(action)) {
                        if (mRequestWorker != null && mRequestWorker.getClient() != null && mRequestWorker.getClient().isConnected())
                            updateStatus();
                    }

                    // check changing accounts
                    if (mAccountManager == null) {
                        mAccountManager = AccountManager.get(this);
                        mAccountManager.addOnAccountsUpdatedListener(mAccountsListener, null, true);
                    }

                    // activate request worker
                    if (mRequestWorker == null || mRequestWorker.isInterrupted()) {
                        EndpointServer server = new EndpointServer(serverUrl);
                        mRequestWorker = new RequestWorker(this, server, mRefCount);
                        // we must be in control! SHUASHUASHUASHAUSHAUSHA!
                        mRequestWorker.addListener(this, true);
                        mRequestWorker.addListener(this, false);

                        ClientThread client = mRequestWorker.getClient();
                        client.setClientListener(this);
                        client.setDefaultTxListener(this);
                        client.setMessageListener(this);

                        mRequestWorker.start();
                        // rest will be done in connected()
                    }

                    /*
                     * FIXME c2dm stays on since in onDestroy() we commented
                     * the unregistration call, and here we do nothing about it
                     */
                }
            }
        }

        return START_STICKY;
    }

    /**
     * Shuts down the request worker.
     * @return true if the thread has been stopped, false if it wasn't running.
     */
    private synchronized boolean shutdownRequestWorker() {
        // Be sure to clear the pending jobs queue.
        // Since we are stopping the message center, any pending request would
        // be lost anyway.
        RequestWorker.pendingJobs.clear();

        if (mRequestWorker != null) {
            RequestWorker tmp = mRequestWorker;
            // discard the reference to the thread immediately
            mRequestWorker = null;
            tmp.shutdown();
            return true;
        }
        return false;
    }

    private void requestServerinfo() {
        pushRequest(new ServerinfoJob());
    }

    /**
     * Requests subscription to presence notification, looking into the map of
     * listeners.
     */
    private void restorePresenceSubscriptions() {
        Set<String> keys = mPresenceListeners.keySet();
        for (String userId : keys) {
            Byte _eventMask = mPresenceListeners.get(userId);
            pushRequest(new UserPresenceRequestJob(userId, _eventMask.intValue()));
        }
    }

    /**
     * Searches for messages with error or pending status and pushes them
     * through the request queue to re-send them.
     */
    private void requeuePendingMessages() {
        Cursor c = getContentResolver().query(Messages.CONTENT_URI,
                new String[] {
                    Messages._ID,
                    Messages.PEER,
                    Messages.CONTENT,
                    Messages.MIME,
                    Messages.LOCAL_URI,
                    Messages.ENCRYPT_KEY
                },
                Messages.DIRECTION + " = " + Messages.DIRECTION_OUT + " AND " +
                Messages.STATUS + " <> " + Messages.STATUS_SENT + " AND " +
                Messages.STATUS + " <> " + Messages.STATUS_RECEIVED + " AND " +
                Messages.STATUS + " <> " + Messages.STATUS_NOTDELIVERED,
                null, Messages._ID);

        while (c.moveToNext()) {
            long id = c.getLong(0);
            String userId = c.getString(1);
            byte[] text = c.getBlob(2);
            String mime = c.getString(3);
            String _fileUri = c.getString(4);
            String key = c.getString(5);
            Uri uri = ContentUris.withAppendedId(Messages.CONTENT_URI, id);

            MessageSender m;

            // check if the message contains some large file to be sent
            if (_fileUri != null) {
                Uri fileUri = Uri.parse(_fileUri);
                // FIXME do not encrypt binary messages for now
                m = new MessageSender(userId, fileUri, mime, uri, null);
            }
            // we have a simple boring plain text message :(
            else {
                m = new MessageSender(userId, text, mime, uri, key, false);
            }

            m.setListener(mMessageRequestListener);
            Log.d(TAG, "resending failed message " + id);
            sendMessage(m);
        }

        c.close();
    }

    /** Sends a {@link UserInfoUpdateRequest} to update our status. */
    private void updateStatus() {
        pushRequest(new RequestJob() {
            @Override
            public String execute(ClientThread client, RequestListener listener, Context context)
                    throws IOException {
                String status = MessagingPreferences.getStatusMessageInternal(MessageCenterService.this);
                UserInfoUpdateRequest.Builder b = UserInfoUpdateRequest.newBuilder();
                b.setStatusMessage(status != null ? status : "");
                b.setFlags(MessagingPreferences.getUserFlags(MessageCenterService.this));
                return client.getConnection().send(b.build());
            }
        });
    }

    @Override
    public void onCreate() {

        // create the global wake lock...
        PowerManager pwr = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pwr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kontalk");
        // ...and acquire it!
        mWakeLock.acquire();

        mMessageRequestListener = new MessageRequestListener(this, this);
        mLocalBroadcastManager = LocalBroadcastManager.getInstance(this);
    }

    private void stop() {
        // invalidate push sender id
        try {
            GCMRegistrar.onDestroy(this);
        }
        catch (IllegalArgumentException e) {
            // ignore "unable to unregister receiver"
        }
        mPushSenderId = null;

        if (mAccountManager != null) {
            mAccountManager.removeOnAccountsUpdatedListener(mAccountsListener);
            mAccountManager = null;
        }

        // stop request worker
        shutdownRequestWorker();
    }

    @Override
    public void onDestroy() {
        stop();
        // release the wake lock
        mWakeLock.release();
    }

    @Override
    public synchronized void connected(ClientThread client) {
        // reset received messages accumulator
        mReceivedJob = null;
    }

    @Override
    public boolean tx(ClientConnection connection, String txId, MessageLite pack) {
        // deprecated login method
        if (pack instanceof AuthenticateResponse) {
            AuthenticateResponse res = (AuthenticateResponse) pack;
            if (res.getValid()) {
                authenticated();
            }
            else {
                // TODO WTF ??
                Log.w(TAG, "authentication failed!");
            }
        }

        // login
        else if (pack instanceof LoginResponse) {
            LoginResponse res = (LoginResponse) pack;
            int status = res.getStatus().getNumber();
            switch (status) {
                case LoginStatus.STATUS_LOGGED_IN_VALUE:
                    authenticated();
                    break;
                default:
                    // TODO WTF ??
                    Log.w(TAG, "authentication failed! (" + status + ")");
            }
        }

        // server info
        else if (pack instanceof ServerInfoResponse) {
            ServerInfoResponse res = (ServerInfoResponse) pack;
            for (int i = 0; i < res.getSupportsCount(); i++) {
                String data = res.getSupports(i);
                if (data.startsWith("google_gcm=")) {
                    mPushSenderId = data.substring("google_gcm=".length());
                    if (mPushNotifications) {
                        String oldSender = MessagingPreferences.getPushSenderId(MessageCenterService.this);

                        // store the new sender id
                        MessagingPreferences.setPushSenderId(MessageCenterService.this, mPushSenderId);

                        // begin a registration cycle if senderId is different
                        if (oldSender != null && !oldSender.equals(mPushSenderId)) {
                            GCMRegistrar.unregister(MessageCenterService.this);
                            // unregister will see this as an attempt to register again
                            mPushRegistrationCycle = true;
                        }
                        else {
                            // begin registration immediately
                            gcmRegister();
                        }
                    }
                }
            }
        }

        // user info update
        else if (pack instanceof UserInfoUpdateResponse && txId.equals(mPushRequestTxId)) {
            UserInfoUpdateResponse res = (UserInfoUpdateResponse) pack;
            boolean success = (res.getStatus().getNumber() == UserInfoUpdateStatus.STATUS_SUCCESS_VALUE);
            GCMRegistrar.setRegisteredOnServer(this, (success && mPushRegistrationId != null));
        }

        // unsolecited packet
        else {
            Log.v(TAG, "tx=" + txId + ", pack=" + pack);
        }
        return true;
    }

    /** Called when authentication is successful. */
    private void authenticated() {
        // request serverinfo
        requestServerinfo();
        // update status message
        updateStatus();
        // subscribe to presence notifications
        restorePresenceSubscriptions();
        // lookup for messages with error status and try to re-send them
        requeuePendingMessages();
        // receipts will be sent while consuming

        // broadcast connected event
        mLocalBroadcastManager.sendBroadcast(new Intent(ACTION_CONNECTED));
    }

    @Override
    public void mailbox(List<AbstractMessage<?>> mbox) {
        ReceivedJob job = new ReceivedJob();
        String confirmId;
        int c = mbox.size();
        for (int i = 0; i < c; i++) {
            AbstractMessage<?> msg = mbox.get(i);
            confirmId = incoming(msg, true, (i == (c - 1)));
            if (confirmId != null) job.add(confirmId);
        }

        // ack all messages
        if (job.size() > 0)
            pushRequest(job);
    }

    @Override
    public void incoming(AbstractMessage<?> msg) {
        incoming(msg, false, true);
    }

    /**
     * Process an incoming message.
     * @param msg the message
     * @param bulk true if we are processing a {@link Mailbox}.
     * @return message confirmation id
     */
    public String incoming(AbstractMessage<?> msg, boolean bulk, boolean allowNotify) {
        String confirmId = null;
        boolean doNotify = false;

        // TODO check for null (unsupported) messages to be notified

        // check if the message needs to be confirmed
        if (msg.isNeedAck())
            confirmId = msg.getRealId();

        if (msg instanceof UserPresenceMessage) {
            UserPresenceMessage pres = (UserPresenceMessage) msg;

            // broadcast :)
            LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
            Intent i = new Intent(ACTION_USER_PRESENCE);
            Uri.Builder b = new Uri.Builder();
            b.scheme("user");
            b.authority(UsersProvider.AUTHORITY);
            b.path(pres.getSender(true));
            i.setDataAndType(b.build(), "internal/presence");

            UserPresenceData data = pres.getContent();

            // get own number for status message decryption
            String statusMsg = data.statusMessage;
            if (!TextUtils.isEmpty(data.statusMessage)) {
                Contact c = Contact.findByUserId(this, msg.getSender(true));
                if (c != null)
                    statusMsg = MessagingPreferences.decryptUserdata(this, data.statusMessage, c.getNumber());
            }

            i.putExtra("org.kontalk.presence.event", data.event);
            i.putExtra("org.kontalk.presence.status", statusMsg);
            lbm.sendBroadcast(i);
        }

        // do not store receipts...
        else if (!(msg instanceof ReceiptMessage)) {
            // store to file if it's an image message
            byte[] content = msg.getBinaryContent();

            // message has a fetch url - store preview in cache (if any)
            // TODO abstract somehow
            if (msg.getFetchUrl() != null) {
                if (msg instanceof ImageMessage) {
                    String filename = AbstractMessage.buildMediaFilename(msg);
                    File file = null;
                    try {
                        file = MediaStorage.writeInternalMedia(this, filename, content);
                    }
                    catch (IOException e) {
                        Log.e(TAG, "unable to write to media storage", e);
                    }
                    // update uri
                    msg.setPreviewFile(file);
                }

                // use text content for database table
                content = msg.getTextContent().getBytes();
            }

            // TODO abstract somehow
            if (msg.getFetchUrl() == null && msg instanceof VCardMessage) {
                String filename = VCardMessage.buildMediaFilename(msg.getId(), msg.getMime());
                File file = null;
                try {
                    file = MediaStorage.writeMedia(filename, content);
                }
                catch (IOException e) {
                    Log.e(TAG, "unable to write to media storage", e);
                }
                // update uri
                if (file != null)
                	msg.setLocalUri(Uri.fromFile(file));

                // use text content for database table
                content = msg.getTextContent().getBytes();
            }

            // save to local storage
            ContentValues values = new ContentValues();
            values.put(Messages.MESSAGE_ID, msg.getId());
            values.put(Messages.REAL_ID, msg.getRealId());
            values.put(Messages.PEER, msg.getSender(true));
            values.put(Messages.MIME, msg.getMime());
            values.put(Messages.CONTENT, content);
            values.put(Messages.ENCRYPTED, msg.isEncrypted());
            values.put(Messages.ENCRYPT_KEY, (msg.wasEncrypted()) ? "" : null);

            String fetchUrl = msg.getFetchUrl();
            if (fetchUrl != null)
                values.put(Messages.FETCH_URL, fetchUrl);

            Uri localUri = msg.getLocalUri();
            if (localUri != null)
                values.put(Messages.LOCAL_URI, localUri.toString());

            File previewFile = msg.getPreviewFile();
            if (previewFile != null)
                values.put(Messages.PREVIEW_PATH, previewFile.getAbsolutePath());

            values.put(Messages.UNREAD, true);
            values.put(Messages.DIRECTION, Messages.DIRECTION_IN);
            values.put(Messages.TIMESTAMP, msg.getTimestamp());
            values.put(Messages.SERVER_TIMESTAMP, msg.getRawServerTimestamp());
            values.put(Messages.LENGTH, msg.getLength());
            try {
                Uri newMsg = getContentResolver().insert(Messages.CONTENT_URI, values);
                msg.setDatabaseId(ContentUris.parseId(newMsg));

                // we will have to notify the user
                doNotify = true;
            }
            catch (SQLiteConstraintException econstr) {
                // duplicated message, skip it
            }
        }

        // we have a receipt, update the corresponding message
        else {
            ReceiptMessage msg2 = (ReceiptMessage) msg;
            ReceiptEntryList rlist = msg2.getContent();
            for (ReceiptEntry rentry : rlist) {
                int status = rentry.status;
                int code = (status == Protocol.ReceiptMessage.Entry.ReceiptStatus.STATUS_SUCCESS_VALUE) ?
                        Messages.STATUS_RECEIVED : Messages.STATUS_NOTDELIVERED;

                Date ts;
                try {
                    ts = rentry.getTimestamp();
                    //Log.v(TAG, "using receipt timestamp: " + ts);
                }
                catch (Exception e) {
                    ts = msg.getServerTimestamp();
                    //Log.v(TAG, "using message timestamp: " + ts);
                }

                MessagesProvider.changeMessageStatusWhere(this,
                        true, Messages.STATUS_RECEIVED,
                        rentry.messageId, false, code,
                        -1, ts.getTime());
            }
        }

        // mark sender as registered in the users database
        final String userId = msg.getSender(true);
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                UsersProvider.markRegistered(context, userId);
            }
        }).start();

        // broadcast message
        broadcastMessage(msg);

        if (allowNotify) {
            if (doNotify && !msg.getSender(true).equalsIgnoreCase(MessagingNotification.getPaused()))
                // update notifications (delayed)
                MessagingNotification.delayedUpdateMessagesNotification(getApplicationContext(), true);
        }

        if (!bulk) {
            if (confirmId != null)
                pushReceived(confirmId);
        }

        return confirmId;
    }

    /**
     * Holds a received command for a while to let the message center process
     * multiple incoming messages.
     */
    private synchronized void pushReceived(String msgId) {
        if (mReceivedJob == null || mReceivedJob.isDone()) {
            mReceivedJob = new ReceivedJob(msgId);
            // delay message so we give time to the next message
            pushRequest(mReceivedJob, 500);
        }
        else {
            mReceivedJob.add(msgId);
        }
    }

    private synchronized void pushRequest(final RequestJob job) {
        pushRequest(job, 0);
    }

    private synchronized void pushRequest(final RequestJob job, long delayMillis) {
        if (mRequestWorker != null && (mRequestWorker.isRunning() || mRequestWorker.isAlive()))
            mRequestWorker.push(job, delayMillis);
        else {
            if (job instanceof ReceivedJob || job instanceof MessageSender) {
                Log.d(TAG, "not queueing message job");
            }
            else {
                Log.d(TAG, "request worker is down, queueing job");
                RequestWorker.pendingJobs.add(job);
            }

            Log.d(TAG, "trying to start message center");
            startMessageCenter(getApplicationContext());
        }
    }

    /** Sends a message using the request worker. */
    public void sendMessage(final MessageSender job) {
        // global listener
        job.setListener(mMessageRequestListener);
        pushRequest(job);
    }

    public void subscribePresence(String userId, int events) {
        mPresenceListeners.put(userId, Byte.valueOf((byte) events));
        pushRequest(new UserPresenceRequestJob(userId, events));
    }

    public void unsubscribePresence(String userId) {
        mPresenceListeners.remove(userId);
        pushRequest(new UserPresenceRequestJob(userId, 0));
    }

    public void startForeground(String userId, long totalBytes) {
        Intent ni = new Intent(getApplicationContext(), ConversationList.class);
        // FIXME this intent should actually open the ComposeMessage activity
        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
                NOTIFICATION_ID_UPLOADING, ni, Intent.FLAG_ACTIVITY_NEW_TASK);

        if (mNotificationBuilder == null) {
            mNotificationBuilder = new ProgressNotificationBuilder(getApplicationContext(),
                R.layout.progress_notification,
                getString(R.string.sending_message),
                R.drawable.stat_notify,
                pi);
        }

        // if we don't know the content length yet, start an interminate progress
        foregroundNotification(totalBytes > 0 ? 0 : -1);
        startForeground(NOTIFICATION_ID_UPLOADING, mCurrentNotification);
    }

    private void foregroundNotification(int progress) {
        mCurrentNotification = mNotificationBuilder
            .progress(progress,
                R.string.attachment_upload,
                R.string.sending_message)
            .build();
    }

    public void publishProgress(long bytes) {
        if (mCurrentNotification != null) {
            mNotificationManager.cancel(NOTIFICATION_ID_UPLOAD_ERROR);

            int progress = (int)((100 * bytes) / mTotalBytes);
            foregroundNotification(progress);
            // send the updates to the notification manager
            mNotificationManager.notify(NOTIFICATION_ID_UPLOADING, mCurrentNotification);
        }
    }

    public void stopForeground() {
        stopForeground(true);
        mCurrentNotification = null;
        mTotalBytes = 0;
    }

    /** Used by the {@link SyncAdapter}. */
    public UserLookupJob lookupUsers(List<String> hashList) {
        UserLookupJob job = new UserLookupJob(hashList);
        pushRequest(job);
        return job;
    }

    /** Used by the {@link ComposeMessageFragment}. */
    public UserLookupJob lookupUser(String userId) {
        UserLookupJob job = new UserLookupJob(userId);
        pushRequest(job);
        return job;
    }

    public RevalidateJob revalidate() {
        RevalidateJob job = new RevalidateJob();
        pushRequest(job);
        return job;
    }

    private void broadcastMessage(AbstractMessage<?> message) {
        // TODO this will work when AbstractMessage will become Parcelable
        /*
        Intent msg = new Intent(MESSAGE_RECEIVED);
        msg.putExtras(message.toBundle());
        sendBroadcast(msg);
        */
    }

    /** Checks for network availability. */
    public static boolean isNetworkConnectionAvailable(Context context) {
        final ConnectivityManager cm = (ConnectivityManager) context
            .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm.getBackgroundDataSetting()) {
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null && info.getState() == NetworkInfo.State.CONNECTED)
                return true;
        }

        return false;
    }

    private static boolean isOfflineMode(Context context) {
        return MessagingPreferences.getOfflineMode(context);
    }

    /** Starts the message center. */
    public static void startMessageCenter(final Context context) {
        // check for offline mode
        if (isOfflineMode(context)) {
            Log.d(TAG, "offline mode enable - abort service start");
            return;
        }

        // check for network state
        if (isNetworkConnectionAvailable(context)) {
            Log.d(TAG, "starting message center");
            context.startService(getStartIntent(context));
        }
        else
            Log.d(TAG, "network not available or background data disabled - abort service start");
    }

    private static Intent getStartIntent(Context context) {
        final Intent intent = new Intent(context, MessageCenterService.class);

        // get the URI from the preferences
        EndpointServer server = MessagingPreferences.getEndpointServer(context);
        intent.putExtra(EndpointServer.class.getName(), server.toString());

        return intent;
    }

    public static void updateStatus(final Context context) {
        Intent i = new Intent(context, MessageCenterService.class);
        EndpointServer server = MessagingPreferences.getEndpointServer(context);
        i.putExtra(EndpointServer.class.getName(), server.toString());
        i.setAction(MessageCenterService.ACTION_UPDATE_STATUS);
        context.startService(i);
    }

    /** Stops the message center. */
    public static void stopMessageCenter(final Context context) {
        Log.d(TAG, "shutting down message center");
        context.stopService(new Intent(context, MessageCenterService.class));
    }

    /** Triggers a managed message center restart. */
    public static void restartMessageCenter(final Context context) {
        Log.d(TAG, "restarting message center");
        Intent i = new Intent(context, MessageCenterService.class);
        EndpointServer server = MessagingPreferences.getEndpointServer(context);
        i.putExtra(EndpointServer.class.getName(), server.toString());
        i.setAction(MessageCenterService.ACTION_RESTART);
        context.startService(i);
    }

    /** Tells the message center we are idle, taking necessary actions. */
    public static void idleMessageCenter(final Context context) {
        Intent i = new Intent(context, MessageCenterService.class);
        i.setAction(MessageCenterService.ACTION_IDLE);
        context.startService(i);
    }

    /**
     * Tells the message center we are holding on to it, preventing any
     * shutdown for inactivity.
     */
    public static void holdMessageCenter(final Context context) {
        Intent i = new Intent(context, MessageCenterService.class);
        i.setAction(MessageCenterService.ACTION_HOLD);
        // include server uri if server needs to be started
        EndpointServer server = MessagingPreferences.getEndpointServer(context);
        i.putExtra(EndpointServer.class.getName(), server.toString());
        context.startService(i);
    }

    /**
     * Tells the message center we are releasing it, allowing any shutdown
     * for inactivity.
     */
    public static void releaseMessageCenter(final Context context) {
        Intent i = new Intent(context, MessageCenterService.class);
        i.setAction(MessageCenterService.ACTION_RELEASE);
        context.startService(i);
    }

    /** Starts the push notifications registration process. */
    public static void enablePushNotifications(Context context) {
        Intent i = new Intent(context, MessageCenterService.class);
        i.setAction(MessageCenterService.ACTION_C2DM_START);
        context.startService(i);
    }

    /** Starts the push notifications unregistration process. */
    public static void disablePushNotifications(Context context) {
        Intent i = new Intent(context, MessageCenterService.class);
        i.setAction(MessageCenterService.ACTION_C2DM_STOP);
        context.startService(i);
    }

    /** Caches the given registration Id for use with push notifications. */
    public static void registerPushNotifications(Context context, String registrationId) {
        Intent i = new Intent(context, MessageCenterService.class);
        i.setAction(MessageCenterService.ACTION_C2DM_REGISTERED);
        i.putExtra(MessageCenterService.GCM_REGISTRATION_ID, registrationId);
        context.startService(i);
    }

    public void setPushNotifications(boolean enabled) {
        mPushNotifications = enabled;
        if (mPushNotifications) {
            if (mPushRegistrationId == null)
                gcmRegister();
        }
        else {
            gcmUnregister();
        }
    }

    private void gcmRegister() {
        if (mPushSenderId != null) {
            try {
                GCMRegistrar.checkDevice(this);
                //GCMRegistrar.checkManifest(this);
                // senderId will be given by serverinfo if any
                mPushRegistrationId = GCMRegistrar.getRegistrationId(this);
                if (TextUtils.isEmpty(mPushRegistrationId))
                    // start registration
                    GCMRegistrar.register(this, mPushSenderId);
                else
                    // already registered - send registration id to server
                    setPushRegistrationId(mPushRegistrationId);
            }
            catch (Exception e) {
                // nothing happens...
            }

        }
    }

    private void gcmUnregister() {
        if (GCMRegistrar.isRegistered(this))
            // start unregistration
            GCMRegistrar.unregister(this);
        else
            // force unregistration
            setPushRegistrationId(null);
    }

    private void setPushRegistrationId(String regId) {
        mPushRegistrationId = regId;
        if (mRequestWorker != null)
            mRequestWorker.setPushRegistrationId(regId);

        // notify the server about the change
        mPushRequestTxId = null;
        mPushRequestJob = new RequestJob() {
            @Override
            public String execute(ClientThread client, RequestListener listener, Context context)
                    throws IOException {
                UserInfoUpdateRequest.Builder b = UserInfoUpdateRequest.newBuilder();
                b.setGoogleRegistrationId(mPushRegistrationId != null ? mPushRegistrationId: "");
                return client.getConnection().send(b.build());
            }
        };
        pushRequest(mPushRequestJob);
    }

    public final class MessageCenterInterface extends Binder {
        public MessageCenterService getService() {
            return MessageCenterService.this;
        }
    }

    @Override
    public void starting(ClientThread client, RequestJob job) {
        Log.d(TAG, "starting foreground progress notification");

        // not a plain text message - use progress notification
        if (job instanceof MessageSender) {
            MessageSender msg = (MessageSender) job;
            if (msg.isAsync(this)) {
                try {
                    mTotalBytes = msg.getContentLength(this);
                    startForeground(msg.getUserId(), 0);
                }
                catch (IOException e) {
                    Log.e(TAG, "error reading message data to send", e);
                    MessagesProvider.changeMessageStatus(this,
                            msg.getMessageUri(), Messages.DIRECTION_OUT, Messages.STATUS_ERROR,
                            -1, System.currentTimeMillis());
                    // just don't send for now
                    return;
                }
            }
        }
    }

    @Override
    public void uploadProgress(ClientThread client, RequestJob job, long bytes) {
        //Log.v(TAG, "bytes sent: " + bytes);
        if (job instanceof MessageSender) {
            boolean cancel = ((MessageSender)job).isCanceled(this);
            if (cancel)
                throw new CancellationException("job has been canceled.");
        }
        publishProgress(bytes);
        Thread.yield();
    }

    @Override
    public void downloadProgress(ClientThread client, RequestJob job, long bytes) {
        // TODO
    }

    @Override
    public void done(ClientThread client, RequestJob job, String txId) {
        if (job instanceof MessageSender) {
            // we are sending a message, check if it's a binary content
            MessageSender msg = (MessageSender) job;
            if (msg.isAsync(this)) {
                // stop any foreground notification
                stopForeground();
                // queue an attachment MessageSender (txId is the fileid)
                ByteString bf = ByteString.copyFromUtf8(txId);
                MessageSender inc = new MessageSender(msg.getUserId(), bf.toByteArray(),
                        msg.getMime(), msg.getMessageUri(), msg.getEncryptKey(), true);
                sendMessage(inc);
            }
        }

        // the push request job! :)
        else if (job == mPushRequestJob) {
            mPushRequestTxId = txId;
            client.setTxListener(mPushRequestTxId, MessageCenterService.this);
        }
    }

    @Override
    public boolean error(ClientThread client, RequestJob job, Throwable exc) {
        // stop any foreground if the job is a message
        if (job instanceof MessageSender) {
            MessageSender job2 = (MessageSender) job;
            if (job2.isAsync(this))
                stopForeground();

            if (job.isCanceled(this))
                return false;

            if (job2.isAsync(this)) {
                // create intent for upload error notification
                // TODO this Intent should bring the user to the actual conversation
                Intent i = new Intent(this, ConversationList.class);
                PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
                        NOTIFICATION_ID_UPLOAD_ERROR, i, Intent.FLAG_ACTIVITY_NEW_TASK);

                // create notification
                NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext())
                    .setSmallIcon(R.drawable.stat_notify)
                    .setContentTitle(getString(R.string.notify_title_upload_error))
                    .setContentText(getString(R.string.notify_ticker_upload_error))
                    .setTicker(getString(R.string.notify_ticker_upload_error))
                    .setContentIntent(pi)
                    .setAutoCancel(true);


                // notify!!
                mNotificationManager.notify(NOTIFICATION_ID_UPLOAD_ERROR, builder.build());
            }
        }

        // the push request job! :)
        else if (job == mPushRequestJob) {
            // mark as not registered on server
            GCMRegistrar.setRegisteredOnServer(this, false);
            mPushRequestTxId = null;
            mPushRequestJob = null;
        }

        return true;
    }

    public static String getPushSenderId() {
        return mPushSenderId;
    }

    public static void setWakeupAlarm(Context context) {
    	AlarmManager am = (AlarmManager) context
    			.getSystemService(Context.ALARM_SERVICE);

    	long delay = MessagingPreferences.getWakeupTimeMillis(context,
    			DEFAULT_WAKEUP_TIME);

    	// start message center pending intent
    	PendingIntent pi = PendingIntent.getService(context
    			.getApplicationContext(), 0, getStartIntent(context),
    			PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT);

    	am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
    			SystemClock.elapsedRealtime() + delay, pi);
    }

}
