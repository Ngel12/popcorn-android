package pct.droid.base.torrent;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.NonNull;

import com.frostwire.jlibtorrent.DHT;
import com.frostwire.jlibtorrent.Downloader;
import com.frostwire.jlibtorrent.FileStorage;
import com.frostwire.jlibtorrent.Session;
import com.frostwire.jlibtorrent.SessionSettings;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.TorrentStatus;
import com.frostwire.jlibtorrent.Utils;
import com.frostwire.jlibtorrent.alerts.BlockFinishedAlert;
import com.frostwire.jlibtorrent.alerts.TorrentFinishedAlert;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.File;
import java.io.IOException;

import pct.droid.base.PopcornApplication;
import pct.droid.base.preferences.Prefs;
import pct.droid.base.utils.FileUtils;
import pct.droid.base.utils.PrefUtils;
import timber.log.Timber;

public class TorrentService extends Service {

    private static final String THREAD_NAME = "TORRENT_SERVICE_THREAD";
    private HandlerThread mThread;
    private Handler mHandler;

    private Session mTorrentSession;
    private DHT mDHT;
    private TorrentHandle mCurrentTorrent;
    private TorrentAlertAdapter mCurrentListener;

    private String mCurrentTorrentUrl = "";
    private File mCurrentVideoLocation;
    private boolean mIsStreaming = false;

    private IBinder mBinder = new ServiceBinder();
    private Listener mListener = null;

    private PowerManager.WakeLock mWakeLock;

    public class ServiceBinder extends Binder {
        public TorrentService getService() {
            return TorrentService.this;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if(mWakeLock.isHeld())
            mWakeLock.release();
        mThread.interrupt();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        start();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        start();
        return mBinder;
    }

    private void start() {
        if(mThread != null) return;

        mThread = new HandlerThread(THREAD_NAME);
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                // Start libtorrent session and init DHT
                Timber.d("Starting libtorrent session");
                mTorrentSession = new Session();
                SessionSettings sessionSettings = mTorrentSession.getSettings();
                sessionSettings.setAnonymousMode(true);
                //sessionSettings.setActiveDHTLimit(150);
                //sessionSettings.setConnectionsLimit(100);
                mTorrentSession.setSettings(sessionSettings);
                Timber.d("Init DHT");
                mDHT = new DHT(mTorrentSession);
                mDHT.start();
                mDHT.waitNodes(1);
                Timber.d("Nodes in DHT: %s", mDHT.nodes());
            }
        });
    }

    public void streamTorrent(@NonNull final String torrentUrl) {
        if(mHandler == null || mIsStreaming) return;

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, THREAD_NAME);
        mWakeLock.acquire();

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mIsStreaming = true;
                File saveDirectory = new File(PopcornApplication.getStreamDir());
                saveDirectory.mkdirs();

                File torrentFile = new File(saveDirectory, "files/" + System.currentTimeMillis() + ".torrent");
                try {
                    torrentFile.getParentFile().mkdirs();
                    torrentFile.delete();
                    torrentFile.createNewFile();
                } catch (IOException e) {
                    Timber.e(e, "Error on file create");
                }

                if(!getTorrentFile(torrentUrl, torrentFile) || !torrentFile.exists()) {
                    if(mListener != null) {
                        mListener.onStreamError(new IOException("No such file or directory"));
                    }
                    return;
                }

                mCurrentTorrent = mTorrentSession.addTorrent(torrentFile, saveDirectory);
                mCurrentListener = new TorrentAlertAdapter(mCurrentTorrent);
                mTorrentSession.addListener(mCurrentListener);

                TorrentInfo torrentInfo = mCurrentTorrent.getTorrentInfo();
                FileStorage fileStorage = torrentInfo.getFiles();
                long highestFileSize = 0;
                int selectedFile = -1;
                for (int i = 0; i < fileStorage.geNumFiles(); i++) {
                    long fileSize = fileStorage.getFileSize(i);
                    if (highestFileSize < fileSize) {
                        highestFileSize = fileSize;
                        selectedFile = i;
                    }
                }

                mCurrentTorrent.getSwig().set_sequential_download(true);
                mCurrentTorrent.resume();

                mCurrentVideoLocation = new File(saveDirectory, torrentInfo.getFileAt(selectedFile).getPath());

                if(mListener != null) {
                    mListener.onStreamStarted();
                }
            }
        });
    }

    public void stopStreaming() {
        mWakeLock.release();

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mIsStreaming = false;
                if (mCurrentTorrent != null) {
                    mCurrentTorrent.pause();
                    mTorrentSession.removeListener(mCurrentListener);
                    mTorrentSession.removeTorrent(mCurrentTorrent);
                    mCurrentListener = null;
                    mCurrentTorrent = null;
                }

                File saveDirectory = new File(PopcornApplication.getStreamDir());
                File torrentPath = saveDirectory;
                if (!PrefUtils.get(TorrentService.this, Prefs.REMOVE_CACHE, true)) {
                    torrentPath = new File(saveDirectory, "files");
                }
                FileUtils.recursiveDelete(torrentPath);
                saveDirectory.mkdirs();
            }
        });
    }

    public boolean isStreaming() {
        return mIsStreaming;
    }

    public String getCurrentTorrentUrl() {
        return mCurrentTorrentUrl;
    }

    public void setListener(@NonNull Listener listener) {
        mListener = listener;
    }

    public void removeListener() {
        mListener = null;
    }

    private boolean getTorrentFile(String torrentUrl, File destination) {
        if(torrentUrl.startsWith("magnet")) {
            Downloader d = new Downloader(mTorrentSession);

            Timber.d("Waiting for nodes in DHT");
            if(mDHT.nodes() < 1) {
                mDHT.start();
                mDHT.waitNodes(1);
            }
            Timber.d("Nodes in DHT: %s", mDHT.nodes());

            Timber.d("Fetching the magnet uri, please wait...");
            byte[] data = d.fetchMagnet(torrentUrl, 30000);

            if (data != null) {
                try {
                    Utils.writeByteArrayToFile(destination, data);
                    Timber.d("Torrent data saved to: %s,", destination);
                    return true;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            Timber.d("Failed to retrieve the magnet");
        } else {
            OkHttpClient client = PopcornApplication.getHttpClient();
            Request request = new Request.Builder().url(torrentUrl).build();
            try {
                Response response = client.newCall(request).execute();
                Utils.writeByteArrayToFile(destination, response.body().bytes());
                return true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    public static void bindHere(Context context, ServiceConnection serviceConnection) {
        Intent torrentServiceIntent = new Intent(context, TorrentService.class);
        context.bindService(torrentServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    public static void start(Context context) {
        Intent torrentServiceIntent = new Intent(context, TorrentService.class);
        context.startService(torrentServiceIntent);
    }

    public interface Listener {
        public void onStreamStarted();
        public void onStreamError(Exception e);
        public void onStreamReady(File videoLocation);
        public void onStreamProgress(DownloadStatus status);
    }

    protected class TorrentAlertAdapter extends com.frostwire.jlibtorrent.TorrentAlertAdapter {

        private boolean mReady = false;

        public TorrentAlertAdapter(TorrentHandle th) {
            super(th);
        }

        public void blockFinished(BlockFinishedAlert alert) {
            super.blockFinished(alert);
            TorrentHandle th = alert.getHandle();
            TorrentStatus status = th.getStatus();
            float progress = status.getProgress() * 100;
            if((int) progress % 10 == 0)
            Timber.d("Torrent progress: %s", progress);
            int bufferProgress = (int) Math.floor(progress * 15);
            if(bufferProgress > 100) bufferProgress = 100;
            int seeds = status.getNumSeeds();
            int downloadSpeed = status.getDownloadPayloadRate();

            if (mListener != null) {
                mListener.onStreamProgress(new DownloadStatus(progress, bufferProgress, seeds, downloadSpeed));
            }

            if(bufferProgress == 100 && !mReady) {
                mReady = true;
                Timber.d("onStreamReady");
                if(mListener != null) {
                    mListener.onStreamReady(mCurrentVideoLocation);
                }
            }
        }

        @Override
        public void torrentFinished(TorrentFinishedAlert alert) {
            super.torrentFinished(alert);
            alert.getHandle().pause();
            mTorrentSession.removeListener(mCurrentListener);
            mTorrentSession.removeTorrent(mCurrentTorrent);
            mCurrentListener = null;
            mCurrentTorrent = null;
        }

    }

}
