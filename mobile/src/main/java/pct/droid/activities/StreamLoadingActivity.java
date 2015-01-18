package pct.droid.activities;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.util.Pair;
import android.transition.Explode;
import android.transition.Transition;
import android.transition.TransitionInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import pct.droid.R;
import pct.droid.base.utils.VersionUtil;
import pct.droid.fragments.StreamLoadingFragment;

public class StreamLoadingActivity extends BaseActivity implements StreamLoadingFragment.FragmentListener {

    public final static String EXTRA_INFO = "mInfo";

    private StreamLoadingFragment.StreamInfo mInfo;
    private StreamLoadingFragment mFragment;

    public static Intent startActivity(Activity activity, StreamLoadingFragment.StreamInfo info) {
        Intent i = new Intent(activity, StreamLoadingActivity.class);
        i.putExtra(EXTRA_INFO, info);
        activity.startActivity(i);
        return i;
    }

    public static Intent startActivity(Activity activity, StreamLoadingFragment.StreamInfo info,Pair<View,String>... elements) {
        Intent i = new Intent(activity, StreamLoadingActivity.class);
        i.putExtra(EXTRA_INFO, info);

        ActivityOptionsCompat options =
                ActivityOptionsCompat.makeSceneTransitionAnimation(activity,elements);
        ActivityCompat.startActivity(activity, i, options.toBundle());
        return i;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setBackgroundDrawableResource(android.R.color.black);

        super.onCreate(savedInstanceState, R.layout.activity_streamloading);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (!getIntent().hasExtra(EXTRA_INFO)) finish();

        mInfo = getIntent().getParcelableExtra(EXTRA_INFO);

        mFragment = (StreamLoadingFragment) getSupportFragmentManager().findFragmentById(R.id.fragment);
    }


    @Override
    public StreamLoadingFragment.StreamInfo getStreamInformation() {
        return mInfo;
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();

        mFragment.cancelStream();
    }
}