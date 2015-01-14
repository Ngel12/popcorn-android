package pct.droid.base;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

import com.bugsnag.android.Bugsnag;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.picasso.OkHttpDownloader;
import com.squareup.picasso.Picasso;

import org.videolan.vlc.VLCApplication;

import java.io.File;
import java.io.IOException;

import pct.droid.base.preferences.Prefs;
import pct.droid.base.torrent.TorrentService;
import pct.droid.base.updater.PopcornUpdater;
import pct.droid.base.utils.FileUtils;
import pct.droid.base.utils.LocaleUtils;
import pct.droid.base.utils.LogUtils;
import pct.droid.base.utils.PrefUtils;
import pct.droid.base.utils.StorageUtils;
import timber.log.Timber;

public class PopcornApplication extends VLCApplication {

    private static OkHttpClient sHttpClient;
    private static Picasso sPicasso;
    private static String sDefSystemLanguage;

    @Override
    public void onCreate() {
        super.onCreate();
        sDefSystemLanguage = LocaleUtils.getCurrent();

        Bugsnag.register(this, Constants.BUGSNAG_KEY);
        PopcornUpdater.getInstance(this).checkUpdates(false);


        Constants.DEBUG_ENABLED = false;
        int versionCode = 0;
        try {
            String packageName = getPackageName();
            PackageInfo packageInfo = getPackageManager().getPackageInfo(packageName, 0);
            int flags = packageInfo.applicationInfo.flags;
            versionCode = packageInfo.versionCode;
            Constants.DEBUG_ENABLED = (flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

		//initialise logging
		if (Constants.DEBUG_ENABLED) {
			Timber.plant(new Timber.DebugTree());
		}

        TorrentService.start(this);

        File path = new File(PrefUtils.get(this, Prefs.STORAGE_LOCATION, StorageUtils.getIdealCacheDirectory(this).toString()));
        File directory = new File(path, "/torrents/");
        if (PrefUtils.get(this, Prefs.REMOVE_CACHE, true)) {
            FileUtils.recursiveDelete(directory);
            FileUtils.recursiveDelete(new File(path + "/subs"));
        } else {
            File statusFile = new File(directory, "status.json");
            statusFile.delete();
        }

        LogUtils.d("StorageLocations: " + StorageUtils.getAllStorageLocations());
        LogUtils.i("Chosen cache location: " + directory);


        if (PrefUtils.get(this, Prefs.INSTALLED_VERSION, 0) < versionCode) {
            PrefUtils.save(this, Prefs.INSTALLED_VERSION, versionCode);
            FileUtils.recursiveDelete(new File(StorageUtils.getIdealCacheDirectory(this) + "/backend"));
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        sDefSystemLanguage = LocaleUtils.getCurrent();
    }

    public static String getSystemLanguage() {
        return sDefSystemLanguage;
    }

    public static OkHttpClient getHttpClient() {
        if (sHttpClient == null) {
            sHttpClient = new OkHttpClient();

            int cacheSize = 10 * 1024 * 1024;
            try {
                File cacheLocation = new File(PrefUtils.get(PopcornApplication.getAppContext(), Prefs.STORAGE_LOCATION, StorageUtils.getIdealCacheDirectory(PopcornApplication.getAppContext()).toString()));
                cacheLocation.mkdirs();
                com.squareup.okhttp.Cache cache = new com.squareup.okhttp.Cache(cacheLocation, cacheSize);
                sHttpClient.setCache(cache);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return sHttpClient;
    }

    public static Picasso getPicasso() {
        if (sPicasso == null) {
            Picasso.Builder builder = new Picasso.Builder(getAppContext());
            OkHttpDownloader downloader = new OkHttpDownloader(getHttpClient());
            builder.downloader(downloader);
            sPicasso = builder.build();
        }
        return sPicasso;
    }

    public static String getStreamDir() {
        File path = new File(PrefUtils.get(getAppContext(), Prefs.STORAGE_LOCATION, StorageUtils.getIdealCacheDirectory(getAppContext()).toString()));
        File directory = new File(path, "/torrents/");
        return directory.toString();
    }

}
