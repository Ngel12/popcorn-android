package pct.droid.base.updater;

import android.annotation.TargetApi;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.support.v4.app.NotificationCompat;

import com.google.gson.Gson;
import com.squareup.okhttp.Callback;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Observable;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import pct.droid.base.BuildConfig;
import pct.droid.base.Constants;
import pct.droid.base.PopcornApplication;
import pct.droid.base.R;
import pct.droid.base.preferences.Prefs;
import pct.droid.base.utils.NetworkUtils;
import pct.droid.base.utils.PrefUtils;

public class PopcornUpdater extends Observable {

    private static PopcornUpdater sThis;

    private static int NOTIFICATION_ID = 0xDEADBEEF;

    public final String STATUS_CHECKING = "checking_updates";
    public final String STATUS_NO_UPDATE = "no_updates";
    public final String STATUS_GOT_UPDATE = "got_update";
    public final String STATUS_HAVE_UPDATE = "have_update";

    private final long MINUTES = 60 * 1000;
    private final long HOURS = 60 * MINUTES;
    private final long DAYS = 24 * HOURS;
    private final long WAKEUP_INTERVAL = 15 * MINUTES;
    private long UPDATE_INTERVAL = 3 * HOURS;

    private final String ANDROID_PACKAGE = "application/vnd.android.package-archive";
    private final String DATA_URL = "http://ci.popcorntime.io/android";

    public static final String LAST_UPDATE_CHECK = "update_check";
    private static final String LAST_UPDATE_KEY = "last_update";
    private static final String UPDATE_FILE = "update_file";
    private static final String SHA1_TIME = "sha1_update_time";
    private static final String SHA1_KEY = "sha1_update";

    private final OkHttpClient mHttpClient = PopcornApplication.getHttpClient();
    private final Gson mGson = new Gson();
    private final Handler mUpdateHandler = new Handler();

    private Context mContext = null;
    private long lastUpdate = 0;
    private String mPackageName;
    private String mVersionName;
    private Integer mVersionCode;

    private PopcornUpdater(Context context) {
        if (Constants.DEBUG_ENABLED) {
            UPDATE_INTERVAL = 3 * HOURS;
        } else {
            UPDATE_INTERVAL = 2 * DAYS;
        }

        mContext = context;
        mPackageName = context.getPackageName();

        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(mPackageName, 0);
            mVersionCode = pInfo.versionCode;
            mVersionName = pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        lastUpdate = PrefUtils.get(mContext, LAST_UPDATE_KEY, 0l);
        NOTIFICATION_ID += crc32(mPackageName);

        ApplicationInfo appinfo = context.getApplicationInfo();

        if (new File(appinfo.sourceDir).lastModified() > PrefUtils.get(mContext, SHA1_TIME, 0l)) {
            PrefUtils.save(mContext, SHA1_KEY, SHA1(appinfo.sourceDir));
            PrefUtils.save(mContext, SHA1_TIME, System.currentTimeMillis());

            String update_file = PrefUtils.get(mContext, UPDATE_FILE, "");
            if (update_file.length() > 0) {
                if (new File(context.getFilesDir().getAbsolutePath() + "/" + update_file).delete()) {
                    PrefUtils.remove(mContext, UPDATE_FILE);
                }
            }
        }
        sendNotification();

        context.registerReceiver(mConnectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    public static PopcornUpdater getInstance(Context context) {
        if (sThis == null) {
            sThis = new PopcornUpdater(context);
        }
        sThis.mContext = context;
        return sThis;
    }

    private Runnable periodicUpdate = new Runnable() {
        @Override
        public void run() {
            checkUpdates(false);
            mUpdateHandler.removeCallbacks(periodicUpdate);
            mUpdateHandler.postDelayed(this, WAKEUP_INTERVAL);
        }
    };

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public void checkUpdates(boolean forced) {
        long now = System.currentTimeMillis();

        if (!PrefUtils.get(mContext, Prefs.AUTOMATIC_UPDATES, true) && !forced) {
            return;
        }

        PrefUtils.save(mContext, LAST_UPDATE_CHECK, now);

        if (forced || (lastUpdate + UPDATE_INTERVAL) < now) {
            lastUpdate = System.currentTimeMillis();
            PrefUtils.save(mContext, LAST_UPDATE_KEY, lastUpdate);
            setChanged();

            if (mVersionName.contains("local")) return;

            notifyObservers(STATUS_CHECKING);

            final String abi;
            if(Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                abi = Build.CPU_ABI.toLowerCase(Locale.US);
            } else {
                abi = Build.SUPPORTED_ABIS[0].toLowerCase(Locale.US);
            }
            
            final String variantStr;
            if (mPackageName.contains("tv")) {
                variantStr = "tv";
            } else {
                variantStr = "mobile";
            }

            final String channelStr;
            if(BuildConfig.BUILD_TYPE.equals("release")) {
                channelStr = "release";
            } else {
                channelStr = BuildConfig.GIT_BRANCH;
            }

            Request request = new Request.Builder()
                    .url(DATA_URL + "/" + variantStr + "/" + channelStr)
                    .build();

            mHttpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Request request, IOException e) {
                    setChanged();
                    notifyObservers(STATUS_NO_UPDATE);
                }

                @Override
                public void onResponse(Response response) {
                    try {
                        if (response.isSuccessful()) {
                            UpdaterData data = mGson.fromJson(response.body().string(), UpdaterData.class);
                            Map<String, Map<String, UpdaterData.Arch>> variant;

                            if (variantStr.equals("tv")) {
                                variant = data.tv;
                            } else {
                                variant = data.mobile;
                            }

                            UpdaterData.Arch channel = null;
                            if(variant.containsKey(channelStr) && variant.get(channelStr).containsKey(abi)) {
                                channel = variant.get(channelStr).get(abi);
                            }

                            if (channel == null || channel.checksum.equals(PrefUtils.get(mContext, SHA1_KEY, "0")) || channel.versionCode <= mVersionCode) {
                                setChanged();
                                notifyObservers(STATUS_NO_UPDATE);
                            } else {
                                downloadFile(channel.updateUrl);
                                setChanged();
                                notifyObservers(STATUS_GOT_UPDATE);
                            }
                        } else {
                            setChanged();
                            notifyObservers(STATUS_NO_UPDATE);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    private void downloadFile(final String location) {
        Request request = new Request.Builder()
                .url(location)
                .build();

        mHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Request request, IOException e) {
                setChanged();
                notifyObservers(STATUS_NO_UPDATE);
            }

            @Override
            public void onResponse(Response response) throws IOException {
                if (response.isSuccessful()) {
                    String fileName = location.substring(location.lastIndexOf('/') + 1);
                    FileOutputStream fos = mContext.openFileOutput(fileName, Context.MODE_WORLD_READABLE);
                    fos.write(response.body().bytes());
                    fos.close();

                    PrefUtils.save(mContext, UPDATE_FILE, fileName);

                    String update_file_path = mContext.getFilesDir().getAbsolutePath() + "/" + fileName;
                    PrefUtils.getPrefs(mContext).edit()
                            .putString(SHA1_KEY, SHA1(update_file_path))
                            .putString(UPDATE_FILE, fileName)
                            .putLong(SHA1_TIME, System.currentTimeMillis())
                            .apply();
                    sendNotification();

                    setChanged();
                    notifyObservers(STATUS_HAVE_UPDATE);
                } else {
                    setChanged();
                    notifyObservers(STATUS_NO_UPDATE);
                }
            }
        });
    }

    public void checkUpdatesManually() {
        checkUpdates(true);
    }

    private BroadcastReceiver mConnectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            // do application-specific task(s) based on the current network state, such
            // as enabling queuing of HTTP requests when currentNetworkInfo is connected etc.
            if (NetworkUtils.isWifiConnected(context)) {
                checkUpdates(false);
                mUpdateHandler.postDelayed(periodicUpdate, UPDATE_INTERVAL);
            } else {
                mUpdateHandler.removeCallbacks(periodicUpdate);    // no network anyway
            }
        }
    };

    public void sendNotification() {
        NotificationManager nm = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        String updateFile = PrefUtils.get(mContext, UPDATE_FILE, "");
        if (updateFile.length() > 0) {
            setChanged();
            notifyObservers(STATUS_HAVE_UPDATE);

            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(mContext)
                    .setSmallIcon(R.drawable.ic_notif_logo)
                    .setContentTitle(mContext.getString(R.string.update_available))
                    .setContentText(mContext.getString(R.string.press_install))
                    .setAutoCancel(true)
                    .setDefaults(NotificationCompat.DEFAULT_ALL);

            Intent notificationIntent = new Intent(Intent.ACTION_VIEW);
            notificationIntent.setDataAndType(Uri.parse("file://" + mContext.getFilesDir().getAbsolutePath() + "/" + updateFile), ANDROID_PACKAGE);

            notificationBuilder.setContentIntent(PendingIntent.getActivity(mContext, 0, notificationIntent, 0));

            nm.notify(NOTIFICATION_ID, notificationBuilder.build());
        } else {
            nm.cancel(NOTIFICATION_ID);
        }
    }

    private String SHA1(String filename) {
        final int BUFFER_SIZE = 8192;
        byte[] buf = new byte[BUFFER_SIZE];
        int length;
        try {
            FileInputStream fis = new FileInputStream(filename);
            BufferedInputStream bis = new BufferedInputStream(fis);
            MessageDigest md = MessageDigest.getInstance("SHA1");
            while ((length = bis.read(buf)) != -1) {
                md.update(buf, 0, length);
            }

            byte[] array = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte anArray : array) {
                sb.append(Integer.toHexString((anArray & 0xFF) | 0x100).substring(1, 3));
            }
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return "sha1bad";
    }

    private static int crc32(String str) {
        byte bytes[] = str.getBytes();
        Checksum checksum = new CRC32();
        checksum.update(bytes, 0, bytes.length);
        return (int) checksum.getValue();
    }

}