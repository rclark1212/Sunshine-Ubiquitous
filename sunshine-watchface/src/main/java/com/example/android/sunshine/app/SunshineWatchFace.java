/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final String TAG = "SunWatch";

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    //and lets store a tag of the last time updated (if phone doesn't communicate for a day, we want to know...
    public static long mLastUpdateMillis;
    final private static long TIMEOUT_IN_HOURS = 24;

    //and the weather line values (sent by phone, painted by watch
    final private static int UNKNOWN_TEMP = -1000;  //will never be -1000. Use this as flag
    public static Drawable mWeatherIcon = null;
    public static int mHighTemp = UNKNOWN_TEMP;
    public static int mLowTemp = UNKNOWN_TEMP;

    //And the data item tag
    private final static String DATAITEM_PATH = "/sunshineWeather";
    private final static String DATAITEM_LOW_TEMP = "low";
    private final static String DATAITEM_HIGH_TEMP = "high";
    private final static String DATAITEM_ICON = "icon";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode. Note - we could save power here and slow down updates.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        //Paint objects
        Paint mBackgroundPaint;
        Paint mHourPaint;
        Paint mMinutePaint;
        Paint mDatePaint;
        Paint mTempPaintHigh;
        Paint mTempPaintLow;

        //watch face size
        int mWatchX = 150;
        int mWatchY = 150;

        boolean mbIsRound;

        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        //offsets calculated for painting
        float mYTimeOffset;
        float mYDateOffset;
        float mYSeperaterOffset;
        float mYTempOffset;
        float mXStartSep;
        float mXEndSep;
        float mIconSize;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        //And make api object
        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setHotwordIndicatorGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL)    //okay - this kind of sucks. But best place is in center...
                    .setViewProtectionMode(WatchFaceStyle.PROTECT_HOTWORD_INDICATOR | WatchFaceStyle.PROTECT_STATUS_BAR)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));

            //mTextPaint = new Paint();
            //mTextPaint = createTextPaint(resources.getColor(R.color.primary_text_light));
            //Set up the various paint resources...
            mHourPaint = createTextPaint(resources.getColor(R.color.digital_text), BOLD_TYPEFACE);
            mMinutePaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatePaint = createTextPaint(resources.getColor(R.color.gray_text));
            mTempPaintHigh = createTextPaint(resources.getColor(R.color.digital_text), BOLD_TYPEFACE);
            mTempPaintLow = createTextPaint(resources.getColor(R.color.gray_text));

            mTime = new Time();
            mTime.setToNow();

            //initialize the timeout timer
            mLastUpdateMillis = System.currentTimeMillis();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }

            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        //
        // This routine sets up drawing coordinates for the watch face. Only run once.
        //
        private void updateYOffsets() {
            //Okay - we will need to use text height in the onDraw command. Instead of measuring
            //text height all the time there, do it once here...
            Resources resources = SunshineWatchFace.this.getResources();

            float timeHeight;
            float dateHeight;
            float tempHeight;
            String log;

            //Normally use getTextSize for size of text. Problem though is it includes descenders...
            //So do a better method.
            Rect bounds = new Rect();
            //timeHeight = mHourPaint.getTextSize();
            mHourPaint.getTextBounds("0", 0, 1, bounds);
            timeHeight = bounds.height();
            Log.v(TAG, String.format("tiHeight=%d", (int) timeHeight));

            //dateHeight = mDatePaint.getTextSize();
            mDatePaint.getTextBounds("0", 0, 1, bounds);
            dateHeight = bounds.height();
            Log.v(TAG, String.format("dHeight=%d", (int) dateHeight));

            //tempHeight = mTempPaintHigh.getTextSize();
            mTempPaintHigh.getTextBounds("0", 0, 1, bounds);
            tempHeight = bounds.height();
            Log.v(TAG, String.format("teHeight=%d",(int)tempHeight));

            //And make icon size same as temp...
            mIconSize = tempHeight;

            //And finally, use the above to calculate y offsets for each line...
            //remember - text is bottom justified
            mYSeperaterOffset = mWatchY/2;

            //next date...
            mYDateOffset = mYSeperaterOffset - resources.getDimension(R.dimen.digital_y_offset);

            //next time...
            mYTimeOffset = mYDateOffset - dateHeight - resources.getDimension(R.dimen.digital_y_spacing);

            //next temp...
            mYTempOffset = mYSeperaterOffset + resources.getDimension(R.dimen.digital_y_offset) + tempHeight;

            //finally calc start/end of sep X
            mXStartSep = (mWatchX/2) - resources.getDimension(R.dimen.digital_y_offset);
            mXEndSep = (mWatchX/2) + resources.getDimension(R.dimen.digital_y_offset);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            Log.v(TAG, "onSurfaceChanged");

            //need to capture x, y for centering...
            mWatchX = width;
            mWatchY = height;
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            Log.v(TAG, "onApplyWindowInsets");

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean mbIsRound = insets.isRound();

            //Want to center digital time in x-axis, offset in Y
            //Want small date string centered just underneath and in gray
            //Want centered seperator under that
            //And want icon, high low after
            //Centering strat is every line center justified in X
            //For y, place seperator in middle.
            //Then grow text with a Y offset from center...

            //3 text sizes - time, date, temp
            float textSizeTime = resources.getDimension(mbIsRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float textSizeDate = resources.getDimension(mbIsRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size);

            float textSizeTemp = resources.getDimension(mbIsRound
                    ? R.dimen.temp_text_size_round : R.dimen.temp_text_size);


            //mTextPaint.setTextSize(textSize);
            mHourPaint.setTextSize(textSizeTime);
            mMinutePaint.setTextSize(textSizeTime);

            mDatePaint.setTextSize(textSizeDate);
            mTempPaintHigh.setTextSize(textSizeTemp);
            mTempPaintLow.setTextSize(textSizeTemp);

            updateYOffsets();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    //mTextPaint.setAntiAlias(!inAmbientMode);
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mMinutePaint.setAntiAlias(!inAmbientMode);
                }

                //Note - we don't have to update mBackground paint here for ambient as that is handled in onDraw...

                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    //FIXME
                    /*
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2)); */
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Always paint time in every mode
            // Always draw HH:MM. Draw HH as bold. :MM as normal.
            mTime.setToNow();

            String textHour = String.format("%d", mTime.hour);
            String textMinute = String.format(":%02d", mTime.minute);

            //calc center position
            float hourWidth = mHourPaint.measureText(textHour);
            float minuteWidth = mMinutePaint.measureText(textMinute);
            float x = (mWatchX - hourWidth - minuteWidth)/2;

            canvas.drawText(textHour, x, mYTimeOffset, mHourPaint);
            x += hourWidth;
            canvas.drawText(textMinute, x, mYTimeOffset, mMinutePaint);

            //only paint date, temp if not in ambient mode
            if (!isInAmbientMode()) {
                //Paint it all!
                //First get date
                SimpleDateFormat sdf = new SimpleDateFormat(SunshineWatchFace.this.getResources().getString(R.string.date_format));
                String date = sdf.format(Calendar.getInstance().getTime()); //yes, I could optimize here...

                canvas.drawText(date.toUpperCase(), (mWatchX - mDatePaint.measureText(date.toUpperCase())) / 2, mYDateOffset, mDatePaint);

                //draw seperator
                canvas.drawLine(mXStartSep, mWatchY/2, mXEndSep, mWatchY/2, mDatePaint);

                //draw temperature line
                drawTemps(canvas);
            }
        }

        //
        //  This routine draws the temp line items...
        //
        private void drawTemps(Canvas canvas) {

            //draw temp
            String tempHigh;
            String tempLow;

            if (resetPhoneData()) {
                mHighTemp = UNKNOWN_TEMP;
                mLowTemp = UNKNOWN_TEMP;
                mWeatherIcon = null;
            }

            //Note - only need to check one temp for no data (valid data always comes in pairs)
            if (mHighTemp == UNKNOWN_TEMP) {
                tempHigh = " ?°";
                tempLow = " ?°";
            } else {
                tempHigh = String.format(" %d°", mHighTemp);
                tempLow = String.format(" %d°", mLowTemp);
            }

            //Grab the icon if one does not exist
            if (mWeatherIcon == null) {
                mWeatherIcon = SunshineWatchFace.this.getResources().getDrawable(R.drawable.ic_muzei);
            }

            //Grr - the icons are not full size in sunshine (there is blank space around edges).
            //To try to scale them to be same size as text, they need between 20% and 25% inflation.
            //So go with 25% since I like images...
            //And yes, for production app, would optimize by putting this in an execute once section - floating point
            //math multiply not cheap.
            float iconAdjust = (0.25f * mIconSize)/2;

            //figure out width...
            float x = mTempPaintHigh.measureText(tempHigh) + mTempPaintLow.measureText(tempLow) + mIconSize + 2*iconAdjust;

            //set the bounds...
            mWeatherIcon.setBounds(
                    (int) (((mWatchX - x) / 2) - iconAdjust),
                    (int) (mYTempOffset - mIconSize - iconAdjust),
                    (int) (((mWatchX - x) / 2) + mIconSize + iconAdjust),
                    (int) (mYTempOffset + iconAdjust));

            //Draw
            mWeatherIcon.draw(canvas);
            canvas.drawText(tempHigh, ((mWatchX-x)/2)+mIconSize, mYTempOffset, mTempPaintHigh);
            canvas.drawText(tempLow, ((mWatchX-x)/2)+mIconSize + mTempPaintHigh.measureText(tempHigh), mYTempOffset, mTempPaintLow);

        }

        //
        // This routine is a chance for the phone to reset its data.
        // For this exercise, we will put in a very simple rule of >24 hours.
        // This could also be done with a timer. But power savings would be minimal (integer math op
        // and compare every time watch face comes up pretty trivial in the larger picture).
        // Timeout time set in the message receiver.
        //
        private boolean resetPhoneData() {
            if (System.currentTimeMillis() > (mLastUpdateMillis + TIMEOUT_IN_HOURS*60*60*1000)) {
                Log.v(TAG, "resetDataTimeout");
                return true;
            } else {
                return false;
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        /*
            Communication routines follow
         */
        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.v(TAG, "OnDataChanged");
            for (DataEvent dataEvent : dataEvents) {
                //if this event not a data changed, ignore
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                //if this event is not a recognized data item, ignore.
                DataItem dataItem = dataEvent.getDataItem();
                if (!dataItem.getUri().getPath().equals(DATAITEM_PATH)) {
                    continue;
                }

                Log.v(TAG, "ourData");

                DataMap weather = DataMapItem.fromDataItem(dataItem).getDataMap();

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "DataItem updated:" + weather);
                }

                //update our data
                updateDataFromDataMap(weather);
            }
        }

        //
        // utility which grabs out our data from the our datamap
        private void updateDataFromDataMap(DataMap weather) {
            mLowTemp = weather.getInt(DATAITEM_LOW_TEMP);
            mHighTemp = weather.getInt(DATAITEM_HIGH_TEMP);

            //FIXME - need to recover icon
        }


        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {
            Log.v(TAG, "onConnected: " + connectionHint);
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);

            //Was thinking I might have to grab data here but on second thought...
            //there should be a data packet waiting for us since watch can only start
            //once sunshine loaded. And since we are guaranteed delivery, should
            //have a data packet waiting for us in all cases (including new installs).
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            Log.v(TAG, "onConnectionSuspended: " + cause);
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(ConnectionResult result) {
            Log.v(TAG, "onConnectionFailed: " + result);
        }

    }
}
