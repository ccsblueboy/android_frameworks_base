/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2015 The MoKee OpenSource Project
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
package com.android.keyguard;

import android.content.Context;
import android.gesture.Gesture;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockGestureView;

import java.util.List;

public class KeyguardGestureView extends LinearLayout implements KeyguardSecurityView {

    private static final String TAG = "SecurityGestureView";
    private static final boolean DEBUG = false;

    // how long before we clear the wrong gesture
    private static final int GESTURE_CLEAR_TIMEOUT_MS = 2000;

    // how long we stay awake after touch events
    private static final int UNLOCK_GESTURE_WAKE_INTERVAL_MS = 5000;

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    private int mFailedGestureAttemptsSinceLastTimeout = 0;
    private int mTotalFailedGestureAttempts = 0;
    private CountDownTimer mCountdownTimer = null;
    private LockPatternUtils mLockPatternUtils;
    private LockGestureView mLockGestureView;
    private KeyguardSecurityCallback mCallback;

    /**
     * Keeps track of the last time we poked the wake lock during dispatching of the touch event.
     * Initialized to something guaranteed to make us poke the wakelock when the user starts
     * drawing the gesture.
     * @see #dispatchTouchEvent(android.view.MotionEvent)
     */
    private long mLastPokeTime = -UNLOCK_GESTURE_WAKE_INTERVAL_MS;

    /**
     * Useful for clearing out the wrong gesture after a delay
     */
    private Runnable mCancelGestureRunnable = new Runnable() {
        public void run() {
            mLockGestureView.clearGesture();
        }
    };
    private Rect mTempRect = new Rect();
    private SecurityMessageDisplay mSecurityMessageDisplay;
    private View mEcaView;
    private Drawable mBouncerFrame;
    private ViewGroup mKeyguardBouncerFrame;
    private KeyguardMessageArea mHelpMessage;

    enum FooterMode {
        Normal,
        ForgotLockGesture,
        VerifyUnlocked
    }

    public KeyguardGestureView(Context context) {
        this(context, null);
    }

    public KeyguardGestureView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLockPatternUtils = mLockPatternUtils == null
                ? new LockPatternUtils(mContext) : mLockPatternUtils;

        mLockGestureView = (LockGestureView) findViewById(R.id.lock_gesture_view);
        mLockGestureView.setSaveEnabled(false);
        mLockGestureView.setFocusable(false);
        mLockGestureView.setOnGestureListener(new UnlockGestureListener());

        // stealth mode will be the same for the life of this screen
        mLockGestureView.setInStealthMode(!mLockPatternUtils.isVisibleGestureEnabled());

        setFocusableInTouchMode(true);

        mSecurityMessageDisplay = new KeyguardMessageArea.Helper(this);
        mEcaView = findViewById(R.id.keyguard_selector_fade_container);
        View bouncerFrameView = findViewById(R.id.keyguard_bouncer_frame);
        if (bouncerFrameView != null) {
            mBouncerFrame = bouncerFrameView.getBackground();
        }

        mKeyguardBouncerFrame = (ViewGroup) findViewById(R.id.keyguard_bouncer_frame);
        mHelpMessage = (KeyguardMessageArea) findViewById(R.id.keyguard_message_area);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean result = super.onTouchEvent(ev);
        // as long as the user is entering a gesture (i.e sending a touch event that was handled
        // by this screen), keep poking the wake lock so that the screen will stay on.
        final long elapsed = SystemClock.elapsedRealtime() - mLastPokeTime;
        if (result && (elapsed > (UNLOCK_GESTURE_WAKE_INTERVAL_MS - 100))) {
            mLastPokeTime = SystemClock.elapsedRealtime();
        }
        mTempRect.set(0, 0, 0, 0);
        offsetRectIntoDescendantCoords(mLockGestureView, mTempRect);
        ev.offsetLocation(mTempRect.left, mTempRect.top);
        result = mLockGestureView.dispatchTouchEvent(ev) || result;
        ev.offsetLocation(-mTempRect.left, -mTempRect.top);
        return result;
    }

    public void reset() {
        // reset lock gesture
        mLockGestureView.enableInput();
        mLockGestureView.setEnabled(true);
        mLockGestureView.clearGesture();

        // if the user is currently locked out, enforce it.
        long deadline = mLockPatternUtils.getLockoutAttemptDeadline();
        if (deadline != 0) {
            handleAttemptLockout(deadline);
        } else {
            displayDefaultSecurityMessage();
        }
    }

    private void displayDefaultSecurityMessage() {
        if (mKeyguardUpdateMonitor.getMaxBiometricUnlockAttemptsReached()) {
            mSecurityMessageDisplay.setMessage(R.string.faceunlock_multiple_failures, true);
        } else {
            mSecurityMessageDisplay.setMessage(R.string.kg_gesture_instructions, false);
        }
    }

    @Override
    public void showUsabilityHint() {
    }

    /** TODO: hook this up */
    public void cleanUp() {
        if (DEBUG) Log.v(TAG, "Cleanup() called on " + this);
        mLockPatternUtils = null;
        mLockGestureView.setOnGestureListener(null);
    }

    private class UnlockGestureListener implements LockGestureView.OnLockGestureListener {

        public void onGestureStart() {
            mLockGestureView.setDisplayMode(LockGestureView.DisplayMode.Correct);
            mLockGestureView.removeCallbacks(mCancelGestureRunnable);
        }

        public void onGestureCleared() {
        }

        public void onGestureDetected(Gesture gesture) {
            mCallback.userActivity();
            if (mLockPatternUtils.checkGesture(gesture)) {
                mCallback.reportUnlockAttempt(true);
                mLockGestureView.setDisplayMode(LockGestureView.DisplayMode.Correct);
                mTotalFailedGestureAttempts = 0;
                mCallback.dismiss(true);
            } else {
                mLockGestureView.setDisplayMode(LockGestureView.DisplayMode.Wrong);
                mTotalFailedGestureAttempts++;
                mFailedGestureAttemptsSinceLastTimeout++;
                mCallback.reportUnlockAttempt(false);
                if (mFailedGestureAttemptsSinceLastTimeout
                        >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT) {
                    long deadline = mLockPatternUtils.setLockoutAttemptDeadline();
                    handleAttemptLockout(deadline);
                } else {
                    mSecurityMessageDisplay.setMessage(R.string.kg_wrong_gesture, true);
                    mLockGestureView.postDelayed(mCancelGestureRunnable, GESTURE_CLEAR_TIMEOUT_MS);
                }
            }
        }
    }

    private void handleAttemptLockout(long elapsedRealtimeDeadline) {
        mLockGestureView.clearGesture();
        mLockGestureView.setEnabled(false);
        final long elapsedRealtime = SystemClock.elapsedRealtime();

        mCountdownTimer = new CountDownTimer(elapsedRealtimeDeadline - elapsedRealtime, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                final int secondsRemaining = (int) (millisUntilFinished / 1000);
                mSecurityMessageDisplay.setMessage(
                        R.string.kg_too_many_failed_attempts_countdown, true, secondsRemaining);
            }

            @Override
            public void onFinish() {
                mLockGestureView.setEnabled(true);
                displayDefaultSecurityMessage();
            }

        }.start();
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void onPause() {
        if (mCountdownTimer != null) {
            mCountdownTimer.cancel();
            mCountdownTimer = null;
        }
    }

    @Override
    public void onResume(int reason) {
        reset();
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void showBouncer(int duration) {
        KeyguardSecurityViewHelper.
                showBouncer(mSecurityMessageDisplay, mEcaView, mBouncerFrame, duration);
    }

    @Override
    public void hideBouncer(int duration) {
        KeyguardSecurityViewHelper.
                hideBouncer(mSecurityMessageDisplay, mEcaView, mBouncerFrame, duration);
    }

    @Override
    public void startAppearAnimation() {
        // TODO.
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return false;
    }
}
