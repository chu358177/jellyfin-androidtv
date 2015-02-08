package tv.mediabrowser.mediabrowsertv;

import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v17.leanback.widget.PlaybackControlsRow;
import android.view.View;
import android.widget.VideoView;

import java.util.List;

import mediabrowser.apiinteraction.ApiClient;
import mediabrowser.apiinteraction.EmptyResponse;
import mediabrowser.apiinteraction.android.profiles.AndroidProfile;
import mediabrowser.model.dlna.StreamBuilder;
import mediabrowser.model.dlna.StreamInfo;
import mediabrowser.model.dlna.VideoOptions;
import mediabrowser.model.dto.BaseItemDto;
import mediabrowser.model.dto.MediaSourceInfo;
import mediabrowser.model.session.PlayMethod;
import mediabrowser.model.session.PlaybackStartInfo;

/**
 * Created by Eric on 12/9/2014.
 */
public class PlaybackController {
    List<BaseItemDto> mItems;
    VideoView mVideoView;
    int mCurrentIndex = 0;
    private int mCurrentPosition = 0;
    private PlaybackState mPlaybackState = PlaybackState.IDLE;
    private TvApp mApplication;

    private StreamInfo mCurrentStreamInfo;

    private PlaybackOverlayFragment mFragment;
    private View mSpinner;
    private Boolean spinnerOff = false;

    private VideoOptions mCurrentOptions;

    private PlayMethod mPlaybackMethod = PlayMethod.Transcode;

    private Runnable mReportLoop;
    private Runnable mProgressLoop;
    private Handler mHandler;
    private static int REPORT_INTERVAL = 3000;
    private static final int DEFAULT_UPDATE_PERIOD = 1000;
    private static final int UPDATE_PERIOD = 500;

    public PlaybackController(List<BaseItemDto> items, PlaybackOverlayFragment fragment) {
        mItems = items;
        mFragment = fragment;
        mApplication = TvApp.getApplication();
        mHandler = new Handler();

    }

    public void init(VideoView view, View spinner) {
        mVideoView = view;
        mSpinner = spinner;
        setupCallbacks();
    }

    public PlayMethod getPlaybackMethod() {
        return mPlaybackMethod;
    }

    public void setPlaybackMethod(PlayMethod value) {
        mPlaybackMethod = value;
    }

    public BaseItemDto getCurrentlyPlayingItem() {
        return mItems.get(mCurrentIndex);
    }
    public MediaSourceInfo getCurrentMediaSource() { return mCurrentStreamInfo != null && mCurrentStreamInfo.getMediaSource() != null ? mCurrentStreamInfo.getMediaSource() : getCurrentlyPlayingItem().getMediaSources().get(0);}
    public StreamInfo getCurrentStreamInfo() { return mCurrentStreamInfo; }

    public boolean isPlaying() {
        return mPlaybackState == PlaybackState.PLAYING;
    }

    public void play(int position) {
        mApplication.getLogger().Debug("Play called with pos: "+position);
        switch (mPlaybackState) {
            case PLAYING:
                // do nothing
                break;
            case PAUSED:
                // just resume
                mVideoView.start();
                mPlaybackState = PlaybackState.PLAYING;
                startProgressAutomation();
                if (mFragment != null) mFragment.setFadingEnabled(true);
                startReportLoop();
                break;
            case BUFFERING:
                // onPrepared should take care of it
                break;
            case IDLE:
                // start new playback
                mSpinner.setVisibility(View.VISIBLE);
                BaseItemDto item = getCurrentlyPlayingItem();
                mCurrentOptions = new VideoOptions();
                mCurrentOptions.setDeviceId(mApplication.getApiClient().getDeviceId());
                mCurrentOptions.setItemId(item.getId());
                mCurrentOptions.setMediaSources(item.getMediaSources());
                mCurrentOptions.setMaxBitrate(getMaxBitrate());

                mCurrentOptions.setProfile(new AndroidProfile());

                mCurrentStreamInfo = playInternal(getCurrentlyPlayingItem(), position, mVideoView, mCurrentOptions);
                if (mFragment != null) {
                    mFragment.setFadingEnabled(true);
                    mFragment.getPlaybackControlsRow().setCurrentTime(position);
                }
                mPlaybackState = PlaybackState.BUFFERING;
                break;
        }
    }

    public int getMaxBitrate() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(mApplication);
        String maxRate = sharedPref.getString("pref_max_bitrate", "15");
        return Integer.parseInt(maxRate) * 1000000;
    }
    private StreamInfo playInternal(BaseItemDto item, int position, VideoView view, VideoOptions options) {
        StreamBuilder builder = new StreamBuilder();
        Long mbPos = (long)position * 10000;
        ApiClient apiClient = mApplication.getApiClient();
        StreamInfo ret = null;

        if (item.getPath() != null && item.getPath().startsWith("http://")) {
            //try direct stream
            view.setVideoPath(item.getPath());
            setPlaybackMethod(PlayMethod.DirectStream);
            ret = new StreamInfo();
            ret.setMediaSource(item.getMediaSources().get(0));
        } else {

            StreamInfo info = builder.BuildVideoItem(options);
            view.setVideoPath(info.ToUrl(apiClient.getApiUrl()));
            setPlaybackMethod(info.getPlayMethod());
            ret = info;
        }

        if (position > 0) {
            mApplication.getPlaybackController().seek(position);
        }
        view.start();
        mApplication.setCurrentPlayingItem(item);

        PlaybackStartInfo startInfo = new PlaybackStartInfo();
        startInfo.setItemId(item.getId());
        startInfo.setPositionTicks(mbPos);
        apiClient.ReportPlaybackStartAsync(startInfo, new EmptyResponse());

        return ret;

    }

    public void switchAudioStream(int index) {
        if (!isPlaying()) return;

        mSpinner.setVisibility(View.VISIBLE);
        spinnerOff = false;
        mCurrentOptions.setAudioStreamIndex(index);
        mApplication.getLogger().Debug("Setting audio index to: " + index);
        mCurrentOptions.setMediaSourceId(getCurrentMediaSource().getId());
        stop();
        mCurrentStreamInfo = playInternal(getCurrentlyPlayingItem(), mCurrentPosition, mVideoView, mCurrentOptions);
        mPlaybackState = PlaybackState.PLAYING;
    }

    public void switchSubtitleStream(int index) {
        if (!isPlaying()) return;

        mSpinner.setVisibility(View.VISIBLE);
        spinnerOff = false;
        mCurrentOptions.setSubtitleStreamIndex(index >= 0 ? index : null);
        mApplication.getLogger().Debug("Setting subtitle index to: " + index);
        mCurrentOptions.setMediaSourceId(getCurrentMediaSource().getId());
        stop();
        mCurrentStreamInfo = playInternal(getCurrentlyPlayingItem(), mCurrentPosition, mVideoView, mCurrentOptions);
        mPlaybackState = PlaybackState.PLAYING;
    }

    public void pause() {
        mPlaybackState = PlaybackState.PAUSED;
        stopProgressAutomation();
        mVideoView.pause();
        if (mFragment != null) mFragment.setFadingEnabled(false);
        stopReportLoop();

    }

    public void stop() {
        if (mPlaybackState != PlaybackState.IDLE) {
            mPlaybackState = PlaybackState.IDLE;
            stopReportLoop();
            stopProgressAutomation();
            Long mbPos = (long)mCurrentPosition * 10000;
            Utils.ReportStopped(getCurrentlyPlayingItem(), mbPos);
            mVideoView.stopPlayback();
        }
    }

    public void next() {
        stop();
        mApplication.getLogger().Debug("Next called.");
        if (mCurrentIndex < mItems.size() - 1) {
            mCurrentIndex++;
            mApplication.getLogger().Debug("Moving to index: "+mCurrentIndex+" out of "+mItems.size() + " total items.");
            mFragment.removeQueueItem(0);
            mFragment.addPlaybackControlsRow();
            spinnerOff = false;
            play(0);
        }
    }

    public void prev() {

    }

    public void seek(int pos) {
        stopReportLoop();
        stopProgressAutomation();
        mPlaybackState = PlaybackState.SEEKING;
        mApplication.getLogger().Debug("Seeking to "+pos);
        mVideoView.seekTo(pos);

    }

    public void skip(int msec) {
        seek(mVideoView.getCurrentPosition() + msec);
    }

    private int getUpdatePeriod() {
        if (mPlaybackState != PlaybackState.PLAYING) {
            return DEFAULT_UPDATE_PERIOD;
        }
        return UPDATE_PERIOD;
    }

    private void startProgressAutomation() {
        mProgressLoop = new Runnable() {
            @Override
            public void run() {
                int updatePeriod = getUpdatePeriod();
                PlaybackControlsRow controls = mFragment.getPlaybackControlsRow();
                if (isPlaying()) {
                    if (!spinnerOff) {
                        spinnerOff = true;
                        if (mSpinner != null) mSpinner.setVisibility(View.GONE);
                    }
                    int currentTime = mVideoView.getCurrentPosition();
                    controls.setCurrentTime(currentTime);
                    mCurrentPosition = currentTime;

                }

                mHandler.postDelayed(this, updatePeriod);
            }
        };
        mHandler.postDelayed(mProgressLoop, getUpdatePeriod());
    }

    public void stopProgressAutomation() {
        if (mHandler != null && mProgressLoop != null) {
            mHandler.removeCallbacks(mProgressLoop);
        }
    }


    private void startReportLoop() {
        mReportLoop = new Runnable() {
            @Override
            public void run() {
                if (mPlaybackState == PlaybackState.PLAYING) {
                    Utils.ReportProgress(getCurrentlyPlayingItem(), (long)mVideoView.getCurrentPosition() * 10000);
                }
                mHandler.postDelayed(this, REPORT_INTERVAL);
            }
        };
        mHandler.postDelayed(mReportLoop, REPORT_INTERVAL);
    }

    private void stopReportLoop() {
        if (mHandler != null && mReportLoop != null) {
            mHandler.removeCallbacks(mReportLoop);
        }

    }


    private void setupCallbacks() {

        mVideoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {

            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                String msg = "";
                if (extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT) {
                    msg = mApplication.getString(R.string.video_error_media_load_timeout);
                } else if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
                    msg = mApplication.getString(R.string.video_error_server_inaccessible);
                } else {
                    msg = mApplication.getString(R.string.video_error_unknown_error);
                }
                Utils.showToast(mApplication, "Video Playback error - "+msg);
                mApplication.getLogger().Error("Playback error - "+msg);
                mVideoView.stopPlayback();
                mPlaybackState = PlaybackState.IDLE;
                stopProgressAutomation();
                stopReportLoop();
                return false;
            }
        });


        mVideoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {

                mp.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
                    @Override
                    public void onSeekComplete(MediaPlayer mp) {
                        mApplication.getLogger().Debug("Seek complete...");
                        mPlaybackState = PlaybackState.PLAYING;
                        mFragment.getPlaybackControlsRow().setCurrentTime(mVideoView.getCurrentPosition());
                        startProgressAutomation();
                        startReportLoop();
                    }
                });
                if (mPlaybackState == PlaybackState.BUFFERING) {
                    mPlaybackState = PlaybackState.PLAYING;
                    startProgressAutomation();
                    startReportLoop();
                }
            }
        });


        mVideoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                mPlaybackState = PlaybackState.IDLE;
                stopProgressAutomation();
                stopReportLoop();
                Long mbPos = (long) mVideoView.getCurrentPosition() * 10000;
                Utils.ReportStopped(mApplication.getCurrentPlayingItem(), mbPos);
                if (mCurrentIndex < mItems.size() - 1) {
                    // move to next in queue
                    mCurrentIndex++;
                    mFragment.removeQueueItem(0);
                    mFragment.addPlaybackControlsRow();
                    spinnerOff = false;
                    play(0);
                } else {
                    // exit activity
                    mApplication.getLogger().Debug("Last item completed. Finishing activity.");
                    mFragment.finish();
                }
            }
        });

    }

    public int getCurrentPosition() {
        return mFragment.getPlaybackControlsRow().getCurrentTime();
    }

    public boolean isPaused() {
        return mPlaybackState == PlaybackState.PAUSED;
    }

    public boolean isIdle() {
        return mPlaybackState == PlaybackState.IDLE;
    }


    /*
 * List of various states that we can be in
 */
    public static enum PlaybackState {
        PLAYING, PAUSED, BUFFERING, IDLE, SEEKING;
    }

}
