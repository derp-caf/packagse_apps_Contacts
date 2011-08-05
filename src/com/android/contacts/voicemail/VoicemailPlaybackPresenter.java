/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.contacts.voicemail;

import static android.util.MathUtils.constrain;

import com.android.contacts.R;
import com.android.ex.variablespeed.MediaPlayerProxy;
import com.android.ex.variablespeed.SingleThreadedMediaPlayerProxy;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Contains the controlling logic for a voicemail playback ui.
 * <p>
 * Specifically right now this class is used to control the
 * {@link com.android.contacts.voicemail.VoicemailPlaybackFragment}.
 * <p>
 * This class is not thread safe. The thread policy for this class is
 * thread-confinement, all calls into this class from outside must be done from
 * the main ui thread.
 */
@NotThreadSafe
/*package*/ class VoicemailPlaybackPresenter {
    /** Contract describing the behaviour we need from the ui we are controlling. */
    public interface PlaybackView {
        Context getDataSourceContext();
        void runOnUiThread(Runnable runnable);
        void setStartStopListener(View.OnClickListener listener);
        void setPositionSeekListener(SeekBar.OnSeekBarChangeListener listener);
        void setSpeakerphoneListener(View.OnClickListener listener);
        void setClipPosition(int clipPositionInMillis, int clipLengthInMillis);
        int getDesiredClipPosition();
        void playbackStarted();
        void playbackStopped();
        void playbackError(Exception e);
        boolean isSpeakerPhoneOn();
        void setSpeakerPhoneOn(boolean on);
        void finish();
        void setRateDisplay(float rate, int stringResourceId);
        void setRateIncreaseButtonListener(View.OnClickListener listener);
        void setRateDecreaseButtonListener(View.OnClickListener listener);
    }

    /** Update rate for the slider, 30fps. */
    private static final int SLIDER_UPDATE_PERIOD_MILLIS = 1000 / 30;
    /**
     * If present in the saved instance bundle, we should not resume playback on
     * create.
     */
    private static final String PAUSED_STATE_KEY = VoicemailPlaybackPresenter.class.getName()
            + ".PAUSED_STATE_KEY";
    /**
     * If present in the saved instance bundle, indicates where to set the
     * playback slider.
     */
    private static final String CLIP_POSITION_KEY = VoicemailPlaybackPresenter.class.getName()
            + ".CLIP_POSITION_KEY";

    /** The preset variable-speed rates.  Each is greater than the previous by 25%. */
    private static final float[] PRESET_RATES = new float[] {
        0.64f, 0.8f, 1.0f, 1.25f, 1.5625f
    };
    /** The string resource ids corresponding to the names given to the above preset rates. */
    private static final int[] PRESET_NAMES = new int[] {
        R.string.voicemail_speed_slowest,
        R.string.voicemail_speed_slower,
        R.string.voicemail_speed_normal,
        R.string.voicemail_speed_faster,
        R.string.voicemail_speed_fastest,
    };
    /**
     * Pointer into the {@link VoicemailPlaybackPresenter#PRESET_RATES} array.
     * <p>
     * This doesn't need to be synchronized, it's used only by the {@link RateChangeListener}
     * which in turn is only executed on the ui thread.  This can't be encapsulated inside the
     * rate change listener since multiple rate change listeners must share the same value.
     */
    private int mRateIndex = 2;

    /**
     * The most recently calculated duration.
     * <p>
     * We cache this in a field since we don't want to keep requesting it from the player, as
     * this can easily lead to throwing {@link IllegalStateException} (any time the player is
     * released, it's illegal to ask for the duration).
     */
    private final AtomicInteger mDuration = new AtomicInteger(0);

    private final PlaybackView mView;
    private final MediaPlayerProxy mPlayer;
    private final PositionUpdater mPositionUpdater;

    /** Voicemail uri to play. */
    private final Uri mVoicemailUri;
    /** Start playing in onCreate iff this is true. */
    private final boolean mStartPlayingImmediately;

    public VoicemailPlaybackPresenter(PlaybackView view, MediaPlayerProxy player,
            Uri voicemailUri, ScheduledExecutorService executorService,
            boolean startPlayingImmediately) {
        mView = view;
        mPlayer = player;
        mVoicemailUri = voicemailUri;
        mStartPlayingImmediately = startPlayingImmediately;
        mPositionUpdater = new PositionUpdater(executorService, SLIDER_UPDATE_PERIOD_MILLIS);
    }

    public void onCreate(Bundle bundle) {
        mView.setPositionSeekListener(new PlaybackPositionListener());
        mView.setStartStopListener(new StartStopButtonListener());
        mView.setSpeakerphoneListener(new SpeakerphoneListener());
        mPlayer.setOnErrorListener(new MediaPlayerErrorListener());
        mPlayer.setOnCompletionListener(new MediaPlayerCompletionListener());
        mView.setSpeakerPhoneOn(mView.isSpeakerPhoneOn());
        mView.setRateDecreaseButtonListener(createRateDecreaseListener());
        mView.setRateIncreaseButtonListener(createRateIncreaseListener());
        mView.setClipPosition(0, 0);
        mView.playbackStopped();
        if (mStartPlayingImmediately) {
            resetPrepareStartPlaying(0);
        }
        // TODO: Now I'm ignoring the bundle, when previously I was checking for contains against
        // the PAUSED_STATE_KEY, and CLIP_POSITION_KEY.
    }

    public void onSaveInstanceState(Bundle outState) {
        outState.putInt(CLIP_POSITION_KEY, mView.getDesiredClipPosition());
        if (!mPlayer.isPlaying()) {
            outState.putBoolean(PAUSED_STATE_KEY, true);
        }
    }

    public void onDestroy() {
        mPositionUpdater.stopUpdating();
        mPlayer.release();
    }

    private class MediaPlayerErrorListener implements MediaPlayer.OnErrorListener {
        @Override
        public boolean onError(MediaPlayer mp, int what, int extra) {
            mView.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    handleError(new IllegalStateException("MediaPlayer error listener invoked"));
                }
            });
            return true;
        }
    }

    private class MediaPlayerCompletionListener implements MediaPlayer.OnCompletionListener {
        @Override
        public void onCompletion(final MediaPlayer mp) {
            mView.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    handleCompletion(mp);
                }
            });
        }
    }

    public View.OnClickListener createRateDecreaseListener() {
        return new RateChangeListener(false);
    }

    public View.OnClickListener createRateIncreaseListener() {
        return new RateChangeListener(true);
    }

    /**
     * Listens to clicks on the rate increase and decrease buttons.
     * <p>
     * This class is not thread-safe, but all interactions with it will happen on the ui thread.
     */
    private class RateChangeListener implements View.OnClickListener {
        private final boolean mIncrease;

        public RateChangeListener(boolean increase) {
            mIncrease = increase;
        }

        @Override
        public void onClick(View v) {
            // Adjust the current rate, then clamp it to the allowed values.
            mRateIndex = constrain(mRateIndex + (mIncrease ? 1 : -1), 0, PRESET_RATES.length - 1);
            // Whether or not we have actually changed the index, call changeRate().
            // This will ensure that we show the "fastest" or "slowest" text on the ui to indicate
            // to the user that it doesn't get any faster or slower.
            changeRate(PRESET_RATES[mRateIndex], PRESET_NAMES[mRateIndex]);
        }
    }

    private void resetPrepareStartPlaying(int clipPositionInMillis) {
        try {
            mPlayer.reset();
            mPlayer.setDataSource(mView.getDataSourceContext(), mVoicemailUri);
            mPlayer.prepare();
            mDuration.set(mPlayer.getDuration());
            int startPosition = constrain(clipPositionInMillis, 0, mDuration.get());
            mView.setClipPosition(startPosition, mDuration.get());
            mPlayer.seekTo(startPosition);
            mPlayer.start();
            mView.playbackStarted();
            mPositionUpdater.startUpdating(startPosition, mDuration.get());
        } catch (IOException e) {
            handleError(e);
        }
    }

    private void handleError(Exception e) {
        mView.playbackError(e);
        mPositionUpdater.stopUpdating();
        mPlayer.release();
    }

    public void handleCompletion(MediaPlayer mediaPlayer) {
        stopPlaybackAtPosition(0, mDuration.get());
    }

    private void stopPlaybackAtPosition(int clipPosition, int duration) {
        mPositionUpdater.stopUpdating();
        mView.playbackStopped();
        mView.setClipPosition(clipPosition, duration);
        if (mPlayer.isPlaying()) {
            mPlayer.pause();
        }
    }

    private class PlaybackPositionListener implements SeekBar.OnSeekBarChangeListener {
        private boolean mShouldResumePlaybackAfterSeeking;

        @Override
        public void onStartTrackingTouch(SeekBar arg0) {
            if (mPlayer.isPlaying()) {
                mShouldResumePlaybackAfterSeeking = true;
                stopPlaybackAtPosition(mPlayer.getCurrentPosition(), mDuration.get());
            } else {
                mShouldResumePlaybackAfterSeeking = false;
            }
        }

        @Override
        public void onStopTrackingTouch(SeekBar arg0) {
            if (mPlayer.isPlaying()) {
                stopPlaybackAtPosition(mPlayer.getCurrentPosition(), mDuration.get());
            }
            if (mShouldResumePlaybackAfterSeeking) {
                resetPrepareStartPlaying(mView.getDesiredClipPosition());
            }
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            mView.setClipPosition(seekBar.getProgress(), seekBar.getMax());
        }
    }

    private void changeRate(float rate, int stringResourceId) {
        ((SingleThreadedMediaPlayerProxy) mPlayer).setVariableSpeed(rate);
        mView.setRateDisplay(rate, stringResourceId);
    }

    private class SpeakerphoneListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            mView.setSpeakerPhoneOn(!mView.isSpeakerPhoneOn());
        }
    }

    private class StartStopButtonListener implements View.OnClickListener {
        @Override
        public void onClick(View arg0) {
            if (mPlayer.isPlaying()) {
                stopPlaybackAtPosition(mPlayer.getCurrentPosition(), mDuration.get());
            } else {
                resetPrepareStartPlaying(mView.getDesiredClipPosition());
            }
        }
    }

    /**
     * Controls the animation of the playback slider.
     */
    @ThreadSafe
    private final class PositionUpdater implements Runnable {
        private final ScheduledExecutorService mExecutorService;
        private final int mPeriodMillis;
        private final Object mLock = new Object();
        @GuardedBy("mLock") private ScheduledFuture<?> mScheduledFuture;
        private final Runnable mSetClipPostitionRunnable = new Runnable() {
            @Override
            public void run() {
                int currentPosition = 0;
                synchronized (mLock) {
                    if (mScheduledFuture != null) {
                        currentPosition = mPlayer.getCurrentPosition();
                    }
                }
                mView.setClipPosition(currentPosition, mDuration.get());
            }
        };

        public PositionUpdater(ScheduledExecutorService executorService, int periodMillis) {
            mExecutorService = executorService;
            mPeriodMillis = periodMillis;
        }

        @Override
        public void run() {
            mView.runOnUiThread(mSetClipPostitionRunnable);
        }

        public void startUpdating(int beginPosition, int endPosition) {
            synchronized (mLock) {
                if (mScheduledFuture != null) {
                    mScheduledFuture.cancel(false);
                }
                mScheduledFuture = mExecutorService.scheduleAtFixedRate(this, 0, mPeriodMillis,
                        TimeUnit.MILLISECONDS);
            }
        }

        public void stopUpdating() {
            synchronized (mLock) {
                if (mScheduledFuture != null) {
                    mScheduledFuture.cancel(false);
                    mScheduledFuture = null;
                }
            }
        }
    }
}
