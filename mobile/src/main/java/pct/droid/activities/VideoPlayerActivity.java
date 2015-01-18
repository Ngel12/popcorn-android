package pct.droid.activities;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.support.v7.widget.Toolbar;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import org.videolan.libvlc.EventHandler;
import org.videolan.libvlc.IVideoPlayer;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.LibVlcException;
import org.videolan.vlc.util.VLCInstance;
import org.videolan.vlc.util.WeakHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

import butterknife.ButterKnife;
import butterknife.InjectView;
import pct.droid.R;
import pct.droid.base.preferences.Prefs;
import pct.droid.base.providers.media.models.Media;
import pct.droid.base.providers.subs.SubsProvider;
import pct.droid.base.subs.Caption;
import pct.droid.base.subs.FormatSRT;
import pct.droid.base.subs.TimedTextObject;
import pct.droid.base.torrent.DownloadStatus;
import pct.droid.base.torrent.TorrentService;
import pct.droid.base.utils.AnimUtils;
import pct.droid.base.utils.FileUtils;
import pct.droid.base.utils.LocaleUtils;
import pct.droid.base.utils.LogUtils;
import pct.droid.base.utils.PixelUtils;
import pct.droid.base.utils.PrefUtils;
import pct.droid.base.utils.StringUtils;
import pct.droid.dialogfragments.StringArraySelectorDialogFragment;

@TargetApi(Build.VERSION_CODES.HONEYCOMB)
public class VideoPlayerActivity extends BaseActivity implements IVideoPlayer, OnSystemUiVisibilityChangeListener, TorrentService.Listener {


	public static Intent startActivity(Activity activity, String streamUrl, Media data, String quality, String subtitleLanguage, long resumePosition) {
		Intent i = new Intent(activity, VideoPlayerActivity.class);
		i.putExtra(VideoPlayerActivity.DATA, data);
		i.putExtra(VideoPlayerActivity.QUALITY, quality);
		i.putExtra(VideoPlayerActivity.SUBTITLES, subtitleLanguage);
		i.putExtra(VideoPlayerActivity.LOCATION, streamUrl);
		//todo: resume position;
		activity.startActivity(i);
		return i;
	}

	public final static String LOCATION = "stream_url";
	public final static String DATA = "video_data";
	public final static String QUALITY = "quality";
	public final static String SUBTITLES = "subtitles";
	public final static String RESUME_POSITION = "resume_position";

	@InjectView(R.id.toolbar)
	Toolbar toolbar;
	@InjectView(R.id.progressIndicator)
	ProgressBar progressIndicator;
	@InjectView(R.id.videoSurface)
	SurfaceView videoSurface;
	@InjectView(R.id.subtitleText)
	TextView subtitleText;
	@InjectView(R.id.controlLayout)
	RelativeLayout controlLayout;
	@InjectView(R.id.playerInfo)
	TextView playerInfo;
	@InjectView(R.id.controlBar)
	SeekBar controlBar;
	@InjectView(R.id.playButton)
	ImageButton playButton;
	@InjectView(R.id.currentTime)
	TextView currentTime;
	@InjectView(R.id.lengthTime)
	TextView lengthTime;
	View decorView;

	private Handler mDisplayHandler;

	private SurfaceHolder mVideoSurfaceHolder;
	private LibVLC mLibVLC;
	private String mLocation;

	private static final int SURFACE_BEST_FIT = 0;
	private static final int SURFACE_FIT_HORIZONTAL = 1;
	private static final int SURFACE_FIT_VERTICAL = 2;
	private static final int SURFACE_FILL = 3;
	private static final int SURFACE_16_9 = 4;
	private static final int SURFACE_4_3 = 5;
	private static final int SURFACE_ORIGINAL = 6;
	private int mCurrentSize = SURFACE_BEST_FIT;

	private long mDuration = 0;
	private long mCurrentTime = 0;
	private int mStreamerProgress = 0;
	private boolean mOverlayVisible = true;
	private int mLastSystemUIVisibility;
	private long mLastSystemShowTime = System.currentTimeMillis();

	private static final int FADE_OUT_OVERLAY = 5000;
	private static final int FADE_OUT_INFO = 1000;

	private Media mMedia;
	private String mCurrentSubsLang = "no-subs";
	private TimedTextObject mSubs;
	private Caption mLastSub = null;

	private int mSavedIndexPosition = -1;
	private boolean mSeeking = false;

	private int mVideoHeight;
	private int mVideoWidth;
	private int mVideoVisibleHeight;
	private int mVideoVisibleWidth;
	private int mSarNum;
	private int mSarDen;

	private AudioManager mAudioManager;
	private int mAudioMax;
	private int mVol;

	private static final int TOUCH_NONE = 0;
	private static final int TOUCH_VOLUME = 1;
	private static final int TOUCH_BRIGHTNESS = 2;
	private static final int TOUCH_SEEK = 3;
	private int mTouchAction;
	private int mSurfaceYDisplayRange;
	private float mTouchY, mTouchX;

	private boolean mIsFirstBrightnessGesture = true;
	private float mRestoreBrightness = -1f;

	private boolean mDisabledHardwareAcceleration = false;
	private int mPreviousHardwareAccelerationMode;

	private TorrentService mService;

	protected ServiceConnection mServiceConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			mService = ((TorrentService.ServiceBinder) service).getService();
			mService.setListener(VideoPlayerActivity.this);
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			mService = null;
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_videoplayer);
		ButterKnife.inject(this);
		setSupportActionBar(toolbar);

		videoSurface.setVisibility(View.VISIBLE);
		toolbar.setOnTouchListener(new View.OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				onTouchEvent(event);
				return true;
			}
		});

		mDisplayHandler = new Handler(Looper.getMainLooper());

		decorView = getWindow().getDecorView();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			decorView.setOnSystemUiVisibilityChangeListener(this);
		}

		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR2) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
			toolbar.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT,
					getResources().getDimensionPixelSize(R.dimen.abc_action_bar_default_height_material) +
							PixelUtils.getStatusBarHeight(this)));
			toolbar.setPadding(toolbar.getPaddingLeft(), PixelUtils.getStatusBarHeight(this), toolbar.getPaddingRight(),
					toolbar.getPaddingBottom());
		}

		if (getIntent().hasExtra(DATA)) {
			mMedia = getIntent().getParcelableExtra(DATA);
			if (mMedia != null && mMedia.title != null) {
				if (getIntent().hasExtra(QUALITY)) {
					getSupportActionBar().setTitle(
							getString(R.string.now_playing) + ": " + mMedia.title + " (" + getIntent().getStringExtra(QUALITY) + ")");
				} else {
					getSupportActionBar().setTitle(getString(R.string.now_playing) + ": " + mMedia.title);
				}
			} else {
				getSupportActionBar().setTitle(getString(R.string.now_playing));
			}

			if (getIntent().hasExtra(SUBTITLES)) {
				mCurrentSubsLang = getIntent().getStringExtra(SUBTITLES);
				startSubtitles();
			}
		} else {
			getSupportActionBar().setTitle(getString(R.string.now_playing));
		}
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        /* Services and miscellaneous */
		mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
		mAudioMax = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

		try {
			mLibVLC = VLCInstance.getLibVlcInstance();
			mLibVLC.setHardwareAcceleration(PrefUtils.get(this, Prefs.HW_ACCELERATION, LibVLC.HW_ACCELERATION_AUTOMATIC));
		} catch (LibVlcException e) {
			LogUtils.d("LibVLC initialisation failed");
			return;
		}

		subtitleText.setTextColor(PrefUtils.get(this, Prefs.SUBTITLE_COLOR, Color.WHITE));
		subtitleText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, PrefUtils.get(this, Prefs.SUBTITLE_SIZE, 16));

		mVideoSurfaceHolder = videoSurface.getHolder();
		// Comment Chroma code out, experimental: will not work on all devices. To be added in settings later.
		//String chroma = mSettings.getString("chroma_format", "");
		//if(chroma.equals("YV12")) {
		//    mSurfaceHolder.setFormat(ImageFormat.YV12);
		//} else if (chroma.equals("RV16")) {
		//    mSurfaceHolder.setFormat(PixelFormat.RGB_565);
		//} else {
		mVideoSurfaceHolder.setFormat(PixelFormat.RGBX_8888);
		//}
		mVideoSurfaceHolder.addCallback(mSurfaceCallback);

		LogUtils.d("Hardware acceleration mode: " + Integer.toString(mLibVLC.getHardwareAcceleration()));

		mLibVLC.eventVideoPlayerActivityCreated(true);

		EventHandler em = EventHandler.getInstance();
		em.addHandler(eventHandler);

		controlBar.setOnSeekBarChangeListener(mOnControlBarListener);

		this.setVolumeControlStream(AudioManager.STREAM_MUSIC);

		PrefUtils.save(this, RESUME_POSITION, 0);

		loadMedia();
	}

	@Override
	protected void onStart() {
		super.onStart();
	}

	@Override
	protected void onResume() {
		super.onResume();

		resumeVideo();
	}

	@Override
	protected void onPause() {
		super.onPause();

		if (mLibVLC != null) {
			long currentTime = mLibVLC.getTime();
			PrefUtils.save(this, RESUME_POSITION, currentTime);

            /*
			 * Pausing here generates errors because the vout is constantly
             * trying to refresh itself every 80ms while the surface is not
             * accessible anymore.
             * To workaround that, we keep the last known position in the preferences
             */
			mLibVLC.stop();
		}

		videoSurface.setKeepScreenOn(false);
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (mRestoreBrightness != -1f) {
			int brightness = (int) (mRestoreBrightness * 255f);
			Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, brightness);
		} else {
			Settings.System
					.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		EventHandler em = EventHandler.getInstance();
		em.removeHandler(eventHandler);

		// MediaCodec opaque direct rendering should not be used anymore since there is no surface to attach.
		mLibVLC.eventVideoPlayerActivityCreated(false);
		// HW acceleration was temporarily disabled because of an error, restore the previous value.
		if (mDisabledHardwareAcceleration)
			mLibVLC.setHardwareAcceleration(mPreviousHardwareAccelerationMode);

		mAudioManager = null;

		if (mService != null)
			mService.stopStreaming();

		PrefUtils.save(this, RESUME_POSITION, 0);
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		setSurfaceSize(mVideoWidth, mVideoHeight, mVideoVisibleWidth, mVideoVisibleHeight, mSarNum, mSarDen);
		super.onConfigurationChanged(newConfig);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		DisplayMetrics screen = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(screen);

		if (mSurfaceYDisplayRange == 0) {
			mSurfaceYDisplayRange = Math.min(screen.widthPixels, screen.heightPixels);
		}

		float y_changed = event.getRawY() - mTouchY;
		float x_changed = event.getRawX() - mTouchX;

		// coef is the gradient's move to determine a neutral zone
		float coef = Math.abs(y_changed / x_changed);
		float xgesturesize = ((x_changed / screen.xdpi) * 2.54f);

		int[] offset = new int[2];
		videoSurface.getLocationOnScreen(offset);

		switch (event.getAction()) {
			case MotionEvent.ACTION_DOWN:
				// Audio
				mTouchY = event.getRawY();
				mVol = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
				mTouchAction = TOUCH_NONE;
				// Seek
				mTouchX = event.getRawX();
				break;

			case MotionEvent.ACTION_MOVE:
				if (coef > 2) {
					mTouchY = event.getRawY();
					mTouchX = event.getRawX();
					if ((int) mTouchX > (screen.widthPixels / 2)) {
						doVolumeTouch(y_changed);
					}
					if ((int) mTouchX < (screen.widthPixels / 2)) {
						doBrightnessTouch(y_changed);
					}
				} else {
					// Seek (Right or Left move)
					doSeekTouch(coef, xgesturesize, false);
				}
				break;

			case MotionEvent.ACTION_UP:
				if (mTouchAction == TOUCH_NONE) {
					if (!mOverlayVisible) {
						showOverlay();
					} else {
						hideOverlay();
					}
				} else {
					showOverlay();
				}

				doSeekTouch(coef, xgesturesize, true);
				break;
		}
		return super.onTouchEvent(event);
	}


	@Override
	public void onSystemUiVisibilityChange(int visibility) {
		if ((mLastSystemUIVisibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0 &&
				(visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
			showOverlay();
		}

		mLastSystemUIVisibility = visibility;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
				finish();
				return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void doSeekTouch(float coef, float gesturesize, boolean seek) {
		// No seek action if coef > 0.5 and gesturesize < 1cm
		if (coef > 0.5 || Math.abs(gesturesize) < 1) {
			return;
		}

		if (mTouchAction != TOUCH_NONE && mTouchAction != TOUCH_SEEK) {
			return;
		}
		mTouchAction = TOUCH_SEEK;

		// Size of the jump, 10 minutes max (600000), with a bi-cubic progression, for a 8cm gesture
		int jump = (int) (Math.signum(gesturesize) * ((600000 * Math.pow((gesturesize / 8), 4)) + 3000));

		// Adjust the jump
		if ((jump > 0) && ((mCurrentTime + jump) > mDuration)) {
			jump = (int) (mDuration - mCurrentTime);
		}
		if ((jump < 0) && ((mCurrentTime + jump) < 0)) {
			jump = (int) -mCurrentTime;
		}

		if (seek && mDuration > 0) {
			seek(jump);
		}

		if (mDuration > 0) {
			showInfo(String.format("%s%s (%s)", jump >= 0 ? "+" : "", StringUtils.millisToString(jump),
					StringUtils.millisToString(mCurrentTime + jump)));
		}
	}

	private void doVolumeTouch(float y_changed) {
		if (mTouchAction != TOUCH_NONE && mTouchAction != TOUCH_VOLUME)
			return;
		float delta = -((y_changed * 2f / mSurfaceYDisplayRange) * mAudioMax);
		mVol += delta;
		int vol = Math.min(Math.max(mVol, 0), mAudioMax);
		if (delta != 0f) {
			setAudioVolume(vol);
		}
	}

	private void setAudioVolume(int vol) {
		mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
		mTouchAction = TOUCH_VOLUME;
		showInfo(getString(R.string.volume) + '\u00A0' + Integer.toString(vol));
	}

	@TargetApi(android.os.Build.VERSION_CODES.FROYO)
	private void initBrightnessTouch() {
		float brightnesstemp = 0.6f;
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO &&
					Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE) ==
							Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
				Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
						Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
				mRestoreBrightness = -1f;
			} else {
				mRestoreBrightness = brightnesstemp =
						android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS) /
								255.0f;
			}
		} catch (Settings.SettingNotFoundException e) {
			e.printStackTrace();
		}
		WindowManager.LayoutParams lp = getWindow().getAttributes();
		lp.screenBrightness = brightnesstemp;
		getWindow().setAttributes(lp);
		mIsFirstBrightnessGesture = false;
	}

	private void doBrightnessTouch(float y_changed) {
		if (mTouchAction != TOUCH_NONE && mTouchAction != TOUCH_BRIGHTNESS)
			return;
		if (mIsFirstBrightnessGesture) initBrightnessTouch();
		mTouchAction = TOUCH_BRIGHTNESS;

		// Set delta : 2f is arbitrary for now, it possibly will change in the future
		float delta = -y_changed / mSurfaceYDisplayRange * 2f;

		changeBrightness(delta);
	}

	private void changeBrightness(float delta) {
		// Estimate and adjust Brightness
		WindowManager.LayoutParams lp = getWindow().getAttributes();
		lp.screenBrightness = Math.min(Math.max(lp.screenBrightness + delta, 0.01f), 1);
		// Set Brightness
		getWindow().setAttributes(lp);
		showInfo(getString(R.string.brightness) + '\u00A0' + Math.round(lp.screenBrightness * 15));
	}

	@Override
	public void setSurfaceSize(int width, int height, int visible_width, int visible_height, int sar_num, int sar_den) {
		if (width * height == 0)
			return;

		// store video size
		mVideoHeight = height;
		mVideoWidth = width;
		mVideoVisibleHeight = visible_height;
		mVideoVisibleWidth = visible_width;
		mSarNum = sar_num;
		mSarDen = sar_den;
		mDisplayHandler.post(new Runnable() {
			@Override
			public void run() {
				changeSurfaceSize(false);
			}
		});
	}

	public void eventHardwareAccelerationError() {
		EventHandler em = EventHandler.getInstance();
		em.callback(EventHandler.HardwareAccelerationError, new Bundle());
	}

	/**
	 * Handle libvlc asynchronous events
	 */
	private final Handler eventHandler = new VideoPlayerEventHandler(this);

	private static class VideoPlayerEventHandler extends WeakHandler<VideoPlayerActivity> {
		public VideoPlayerEventHandler(VideoPlayerActivity owner) {
			super(owner);
		}

		@Override
		public void handleMessage(Message msg) {
			VideoPlayerActivity activity = getOwner();
			if (activity == null) return;

			switch (msg.getData().getInt("event")) {
				case EventHandler.MediaParsedChanged:
					break;
				case EventHandler.MediaPlayerPlaying:
					activity.resumeVideo();
					activity.progressIndicator.setVisibility(View.GONE);
					activity.showOverlay();
					break;
				case EventHandler.MediaPlayerPaused:
					break;
				case EventHandler.MediaPlayerStopped:
					break;
				case EventHandler.MediaPlayerEndReached:
					activity.endReached();
					break;
				case EventHandler.MediaPlayerEncounteredError:
					activity.encounteredError();
					break;
				case EventHandler.HardwareAccelerationError:
					activity.handleHardwareAccelerationError();
					break;
				case EventHandler.MediaPlayerTimeChanged:
				case EventHandler.MediaPlayerPositionChanged:
					activity.setOverlayProgress();
					activity.checkSubs();
					break;
				default:
					LogUtils.e(String.format("Event not handled (0x%x)", msg.getData().getInt("event")));
					break;
			}
			activity.updatePlayPause();
		}
	}

	private void endReached() {
		if (mLibVLC.getMediaList().expandMedia(mSavedIndexPosition) == 0) {
			LogUtils.d("Found a video playlist, expanding it");
			eventHandler.postDelayed(new Runnable() {
				@Override
				public void run() {
					loadMedia();
				}
			}, 1000);
		} else {
			/* Exit player when reaching the end */
			// TODO: END, ASK USER TO CLOSE PLAYER?
		}
	}

	private void encounteredError() {
        /* Encountered Error, exit player with a message */
		AlertDialog dialog = new AlertDialog.Builder(VideoPlayerActivity.this)
				.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int id) {
						finish();
					}
				})
				.setTitle("Encountered error")
				.setMessage("Encountered error")
				.create();
		dialog.show();
	}

	private void handleHardwareAccelerationError() {
		mLibVLC.stop();
		AlertDialog dialog = new AlertDialog.Builder(VideoPlayerActivity.this)
				.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int id) {
						mDisabledHardwareAcceleration = true;
						mPreviousHardwareAccelerationMode = mLibVLC.getHardwareAcceleration();
						mLibVLC.setHardwareAcceleration(LibVLC.HW_ACCELERATION_DISABLED);
						loadMedia();
					}
				})
				.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int id) {
						finish();
					}
				})
				.setTitle(R.string.hardware_acceleration_error_title)
				.setMessage(R.string.hardware_acceleration_error_message)
				.create();
		if (!isFinishing())
			dialog.show();
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
	private void changeSurfaceSize(boolean message) {
		int sw = getWindow().getDecorView().getWidth();
		int sh = getWindow().getDecorView().getHeight();

		double dw = sw, dh = sh;

		if (sw < sh) {
			dw = sh;
			dh = sw;
		}

		// sanity check
		if (dw * dh == 0 || mVideoWidth * mVideoHeight == 0) {
			LogUtils.e("Invalid surface size");
			return;
		}

		// compute the aspect ratio
		double ar, vw;
		if (mSarDen == mSarNum) {
            /* No indication about the density, assuming 1:1 */
			vw = mVideoVisibleWidth;
			ar = (double) mVideoVisibleWidth / (double) mVideoVisibleHeight;
		} else {
            /* Use the specified aspect ratio */
			vw = mVideoVisibleWidth * (double) mSarNum / mSarDen;
			ar = vw / mVideoVisibleHeight;
		}

		// compute the display aspect ratio
		double dar = dw / dh;


		switch (mCurrentSize) {
			case SURFACE_BEST_FIT:
				if (message) showInfo(getString(R.string.best_fit));
				if (dar < ar)
					dh = dw / ar;
				else
					dw = dh * ar;
				break;
			case SURFACE_FIT_HORIZONTAL:
				dh = dw / ar;
				if (message) showInfo(getString(R.string.fit_horizontal));
				break;
			case SURFACE_FIT_VERTICAL:
				dw = dh * ar;
				if (message) showInfo(getString(R.string.fit_vertical));
				break;
			case SURFACE_FILL:
				if (message) showInfo(getString(R.string.fill));
				break;
			case SURFACE_16_9:
				if (message) showInfo("16:9");
				ar = 16.0 / 9.0;
				if (dar < ar)
					dh = dw / ar;
				else
					dw = dh * ar;
				break;
			case SURFACE_4_3:
				if (message) showInfo("4:3");
				ar = 4.0 / 3.0;
				if (dar < ar)
					dh = dw / ar;
				else
					dw = dh * ar;
				break;
			case SURFACE_ORIGINAL:
				if (message) showInfo(getString(R.string.original_size));
				dh = mVideoVisibleHeight;
				dw = vw;
				break;
		}

		// force surface buffer size
		mVideoSurfaceHolder.setFixedSize(mVideoWidth, mVideoHeight);

		// set display size
		LayoutParams lp = videoSurface.getLayoutParams();
		lp.width = (int) Math.ceil(dw * mVideoWidth / mVideoVisibleWidth);
		lp.height = (int) Math.ceil(dh * mVideoHeight / mVideoVisibleHeight);
		videoSurface.setLayoutParams(lp);

		videoSurface.invalidate();
	}

	void seek(int delta) {
		if (mLibVLC.getLength() <= 0 && !mSeeking) return;

		long position = mLibVLC.getTime() + delta;
		if (position < 0) position = 0;
		mLibVLC.setTime(position);
		showOverlay();
		setOverlayProgress();
		mLastSub = null;
		checkSubs();
	}

	private void resumeVideo() {
		if (mLibVLC == null)
			return;

		long resumePosition = PrefUtils.get(this, RESUME_POSITION, 0);
		long length = mLibVLC.getLength();
		if (length > resumePosition && resumePosition > 0) {
			mLibVLC.setTime(resumePosition);
			PrefUtils.save(this, RESUME_POSITION, 0);
		}
	}

	private void play() {
		mLibVLC.play();
		videoSurface.setKeepScreenOn(true);

		resumeVideo();
	}

	private void pause() {
		mLibVLC.pause();
		videoSurface.setKeepScreenOn(false);
	}

	public void playPauseClick(View v) {
		if (mLibVLC == null)
			return;

		if (mLibVLC.isPlaying()) {
			pause();
		} else {
			play();
		}
		updatePlayPause();
	}

	public void seekForwardClick(View v) {
		seek(10000);
	}

	public void seekBackwardClick(View v) {
		seek(-10000);
	}

	public void scaleClick(View v) {
		if (mCurrentSize < SURFACE_ORIGINAL) {
			mCurrentSize++;
		} else {
			mCurrentSize = 0;
		}
		changeSurfaceSize(true);
		showOverlay();
	}

	public void subsClick(View v) {
		if (mMedia != null && mMedia.subtitles != null) {
			if (getFragmentManager().findFragmentByTag("overlay_fragment") != null) return;
			final String[] subtitles = mMedia.subtitles.keySet().toArray(new String[mMedia.subtitles.size()]);
			Arrays.sort(subtitles);
			String[] readableNames = new String[subtitles.length];
			for (int i = 0; i < readableNames.length; i++) {
				String language = subtitles[i];
				if (language.equals("no-subs")) {
					readableNames[i] = getString(R.string.no_subs);
				} else {
					Locale locale = LocaleUtils.toLocale(language);
					readableNames[i] = locale.getDisplayName(locale);
				}
			}

			StringArraySelectorDialogFragment.showSingleChoice(getFragmentManager(), R.string.subtitles, readableNames,
					Arrays.asList(subtitles).indexOf(mCurrentSubsLang), new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int position) {
							onSubtitleLanguageSelected(subtitles[position]);
							dialog.dismiss();
						}
					});
		}
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	public void showOverlay() {
		if (!mOverlayVisible) {
			updatePlayPause();

			AnimUtils.fadeIn(controlLayout);
			AnimUtils.fadeIn(toolbar);

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
				int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
						View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
				decorView.setSystemUiVisibility(uiOptions);
			} else {
				getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
				getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
			}

			mLastSystemShowTime = System.currentTimeMillis();
		}

		mOverlayVisible = true;
		mDisplayHandler.removeCallbacks(mOverlayHideRunnable);
		mDisplayHandler.postDelayed(mOverlayHideRunnable, FADE_OUT_OVERLAY);
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	public void hideOverlay() {
		// Can only hide 1000 millisec after show, because navbar doesn't seem to hide otherwise.
		if (mLastSystemShowTime + 1000 < System.currentTimeMillis()) {
			AnimUtils.fadeOut(controlLayout);
			AnimUtils.fadeOut(toolbar);

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
				int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN;
				decorView.setSystemUiVisibility(uiOptions);
			} else {
				getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
				getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
			}

			mDisplayHandler.removeCallbacks(mOverlayHideRunnable);
			mOverlayVisible = false;
		}
	}

	private void showInfo(String text) {
		playerInfo.setVisibility(View.VISIBLE);
		playerInfo.setText(text);
		mDisplayHandler.removeCallbacks(mInfoHideRunnable);
		mDisplayHandler.postDelayed(mInfoHideRunnable, FADE_OUT_INFO);
	}

	private void hideInfo() {
		if (playerInfo.getVisibility() == View.VISIBLE) {
			Animation fadeOutAnim = AnimationUtils.loadAnimation(VideoPlayerActivity.this, android.R.anim.fade_out);
			playerInfo.startAnimation(fadeOutAnim);
		}
		playerInfo.setVisibility(View.INVISIBLE);
	}

	public void updatePlayPause() {
		if (mLibVLC == null)
			return;

		if (mLibVLC.isPlaying()) {
			playButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_av_pause));
		} else {
			playButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_av_play));
		}
	}

	private long setOverlayProgress() {
		if (mLibVLC == null) {
			return 0;
		}

		mDuration = mLibVLC.getLength();
		mCurrentTime = mLibVLC.getTime();

		controlBar.setMax((int) mDuration);
		controlBar.setProgress((int) mCurrentTime);
		controlBar.setSecondaryProgress(0); // hack to make the secondary progress appear on Android 5.0
		controlBar.setSecondaryProgress(mStreamerProgress);
		if (mCurrentTime >= 0) currentTime.setText(StringUtils.millisToString(mCurrentTime));
		if (mDuration >= 0) lengthTime.setText(StringUtils.millisToString(mDuration));

		controlBar.setSecondaryProgress(0); // hack to make the secondary progress appear on Android 5.0
		controlBar.setSecondaryProgress(mStreamerProgress);

		return mCurrentTime;
	}

	private SeekBar.OnSeekBarChangeListener mOnControlBarListener = new SeekBar.OnSeekBarChangeListener() {
		@Override
		public void onStartTrackingTouch(SeekBar seekBar) {
			mSeeking = true;
		}

		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {
			mSeeking = false;
		}

		@Override
		public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
			if (fromUser && mSeeking) {
				mLastSub = null;
				mLibVLC.setTime(progress);
				setOverlayProgress();
				checkSubs();
			}
		}
	};

	/**
	 * attach and disattach surface to the lib
	 */
	private final SurfaceHolder.Callback mSurfaceCallback = new Callback() {
		@Override
		public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
			if (format == PixelFormat.RGBX_8888)
				LogUtils.d("Pixel format is RGBX_8888");
			else if (format == PixelFormat.RGB_565)
				LogUtils.d("Pixel format is RGB_565");
			else if (format == ImageFormat.YV12)
				LogUtils.d("Pixel format is YV12");
			else
				LogUtils.d("Pixel format is other/unknown");
			if (mLibVLC != null)
				mLibVLC.attachSurface(holder.getSurface(), VideoPlayerActivity.this);
		}

		@Override
		public void surfaceCreated(SurfaceHolder holder) {
		}

		@Override
		public void surfaceDestroyed(SurfaceHolder holder) {
			if (mLibVLC != null)
				mLibVLC.detachSurface();
		}
	};

	private Runnable mOverlayHideRunnable = new Runnable() {
		@Override
		public void run() {
			hideOverlay();
		}
	};

	private Runnable mInfoHideRunnable = new Runnable() {
		@Override
		public void run() {
			hideInfo();
		}
	};


	/**
	 * External extras: - position (long) - position of the video to start with (in ms)
	 */
	@SuppressWarnings({"unchecked"})
	private void loadMedia() {
		if (mLocation == null && getIntent().getExtras().containsKey(LOCATION)) {
			mLocation = getIntent().getStringExtra(LOCATION);
		}

		videoSurface.setKeepScreenOn(true);

		if (mLibVLC == null)
			return;

        /* Start / resume playback */
		if (mSavedIndexPosition > -1) {
			mLibVLC.setMediaList();
			mLibVLC.playIndex(mSavedIndexPosition);
		} else if (mLocation != null && mLocation.length() > 0) {
			mLibVLC.setMediaList();
			mLibVLC.getMediaList().add(new org.videolan.libvlc.Media(mLibVLC, mLocation));
			mSavedIndexPosition = mLibVLC.getMediaList().size() - 1;
			mLibVLC.playIndex(mSavedIndexPosition);
		}

		long resumeTime = PrefUtils.get(this, RESUME_POSITION, 0);
		if (resumeTime > 0) {
			mLibVLC.setTime(resumeTime);
		}
	}

	private void startSubtitles() {
		new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... voids) {
				try {
					File file = new File(SubsProvider.getStorageLocation(VideoPlayerActivity.this),
							mMedia.videoId + "-" + mCurrentSubsLang + ".srt");
					FileInputStream fileInputStream = new FileInputStream(file);
					FormatSRT formatSRT = new FormatSRT();
					mSubs = formatSRT.parseFile(file.toString(), FileUtils.inputstreamToCharsetString(fileInputStream).split("\n"));
					checkSubs();
				} catch (FileNotFoundException e) {
					if (e.getMessage().contains("EBUSY")) {
						startSubtitles();
					}
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
				return null;
			}
		}.execute();
	}

	public void onTimedText(final Caption text) {
		mDisplayHandler.post(new Runnable() {
			@Override
			public void run() {
				if (text == null) {
					if (subtitleText.getText().length() > 0) {
						subtitleText.setText("");
					}
					return;
				}
				SpannableStringBuilder styledString = (SpannableStringBuilder) Html.fromHtml(text.content);

				ForegroundColorSpan[] toRemoveSpans = styledString.getSpans(0, styledString.length(), ForegroundColorSpan.class);
				for (ForegroundColorSpan remove : toRemoveSpans) {
					styledString.removeSpan(remove);
				}

				if (!subtitleText.getText().toString().equals(styledString.toString())) {
					subtitleText.setText(styledString);
				}
			}
		});
	}

	private void checkSubs() {
		if (mLibVLC != null && mLibVLC.isPlaying() && mSubs != null) {
			Collection<Caption> subtitles = mSubs.captions.values();
			if (mLastSub != null && mCurrentTime >= mLastSub.start.getMilliseconds() && mCurrentTime <= mLastSub.end.getMilliseconds()) {
				onTimedText(mLastSub);
			} else {
				for (Caption caption : subtitles) {
					if (mCurrentTime >= caption.start.getMilliseconds() && mCurrentTime <= caption.end.getMilliseconds()) {
						mLastSub = caption;
						onTimedText(caption);
						break;
					} else if (mCurrentTime > caption.end.getMilliseconds()) {
						onTimedText(null);
					}
				}
			}
		}
	}

	public void onSubtitleLanguageSelected(String language) {
		if (mCurrentSubsLang != null && (language == null || mCurrentSubsLang.equals(language))) {
			return;
		}

		onTimedText(null);

		if (language.equals("no-subs")) {
			mSubs = null;
			return;
		}

		mCurrentSubsLang = language;
		SubsProvider.download(this, mMedia, language, new com.squareup.okhttp.Callback() {
			@Override
			public void onFailure(Request request, IOException e) {
				mSubs = null;
				mCurrentSubsLang = "no-subs";

				try {
					Toast.makeText(getApplicationContext(), "Subtitle download failed", Toast.LENGTH_SHORT).show();
				} catch (RuntimeException runtimeException) {
					runtimeException.printStackTrace();
				}
			}

			@Override
			public void onResponse(Response response) throws IOException {
				startSubtitles();
			}
		});
	}

	@Override
	public void onStreamStarted() {

	}

	@Override
	public void onStreamError(Exception e) {

	}

	@Override
	public void onStreamReady(File videoLocation) {

	}

	@Override
	public void onStreamProgress(DownloadStatus status) {
		int newProgress = (int) ((mDuration / 100) * status.progress);
		if (mStreamerProgress < newProgress) {
			mStreamerProgress = newProgress;
		}
	}

}

