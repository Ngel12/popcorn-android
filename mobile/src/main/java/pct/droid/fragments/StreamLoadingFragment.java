package pct.droid.fragments;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.DecimalFormat;

import butterknife.ButterKnife;
import butterknife.InjectView;
import pct.droid.R;
import pct.droid.activities.VideoPlayerActivity;
import pct.droid.base.fragments.BaseStreamLoadingFragment;
import pct.droid.base.providers.media.types.Media;
import pct.droid.base.torrent.DownloadStatus;
import pct.droid.base.utils.ThreadUtils;

public class StreamLoadingFragment extends BaseStreamLoadingFragment {

	@InjectView(R.id.progressIndicator)
	ProgressBar progressIndicator;
	@InjectView(R.id.primary_textview)
	TextView mPrimaryTextView;
	@InjectView(R.id.secondary_textview)
	TextView mSecondaryTextView;
	@InjectView(R.id.tertiary_textview)
	TextView mTertiaryTextView;

	@Override public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_streamloading, container, false);
	}

	@Override public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		ButterKnife.inject(this, view);
	}

	private void updateStatus(final DownloadStatus status) {
		final DecimalFormat df = new DecimalFormat("#############0.00");
		ThreadUtils.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				progressIndicator.setIndeterminate(false);
				progressIndicator.setProgress(status.bufferProgress);
				mPrimaryTextView.setText(status.bufferProgress + "%");

				if (status.downloadSpeed / 1024 < 1000) {
					mSecondaryTextView.setText(df.format(status.downloadSpeed / 1024) + " KB/s");
				} else {
					mSecondaryTextView.setText(df.format(status.downloadSpeed / 1048576) + " MB/s");
				}
				mTertiaryTextView.setText(status.seeds + " " + getString(R.string.seeds));
			}
		});
	}


	@Override protected void updateView(State state, Object extra) {

		switch (state) {
			case UNINITIALISED:
				mTertiaryTextView.setText(null);
				mPrimaryTextView.setText(null);
				mSecondaryTextView.setText(null);
				progressIndicator.setIndeterminate(true);
				progressIndicator.setProgress(0);
				break;
			case ERROR:
				if (null != extra && extra instanceof String)
					mPrimaryTextView.setText((String) extra);
				mSecondaryTextView.setText(null);
				mTertiaryTextView.setText(null);
				progressIndicator.setIndeterminate(true);
				progressIndicator.setProgress(0);
				break;
			case BUFFERING:
				mPrimaryTextView.setText(R.string.starting_buffering);
				mTertiaryTextView.setText(null);
				mSecondaryTextView.setText(null);
				progressIndicator.setIndeterminate(true);
				progressIndicator.setProgress(0);
				break;
			case STREAMING:
				mPrimaryTextView.setText(R.string.streaming_started);
				if (null != extra && extra instanceof DownloadStatus)
					updateStatus((DownloadStatus) extra);
				break;
			case WAITING_SUBTITLES:
				mPrimaryTextView.setText(R.string.waiting_for_subtitles);
				mTertiaryTextView.setText(null);
				mSecondaryTextView.setText(null);
				progressIndicator.setIndeterminate(true);
				progressIndicator.setProgress(0);
				break;
			case WAITING_TORRENT:
				mPrimaryTextView.setText(R.string.waiting_torrent);
				mTertiaryTextView.setText(null);
				mSecondaryTextView.setText(null);
				progressIndicator.setIndeterminate(true);
				progressIndicator.setProgress(0);
				break;

		}
	}

	@Override protected void startPlayerActivity(FragmentActivity activity, String location, Media media, String quality,
			String subtitleLanguage,
			int i) {
		VideoPlayerActivity.startActivity(activity, location, media, quality, subtitleLanguage, i);
	}
}
