package com.marcouberti.f35watchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
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
import com.marcouberti.f35watchface.utils.ScreenUtils;

import java.lang.ref.WeakReference;
import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class F35Face extends CanvasWatchFaceService {

    private static final String TAG = "NatureGradientsFace";

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = 1000;

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private static final int BATTERY = 0;
    private static final int DAY_NUMBER = 1;
    private static final int DAY_WEEK = 2;
    private static final int MONTH = 3;
    private static final int YEAR = 4;
    private static final int NONE = 5;

    private int BOTTOM_COMPLICATION_MODE = BATTERY;
    private int LEFT_COMPLICATION_MODE = DAY_WEEK;
    private int RIGHT_COMPLICATION_MODE = DAY_NUMBER;

    int selectedColorCode;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{

        Bitmap bg;
        Paint mHandPaint;
        Paint mBackgroundPaint;
        Paint mSecondsCirclePaint,mComplicationPaint;
        Paint blackFillPaint, whiteFillPaint;
        boolean mAmbient;
        boolean nightMode = false;
        Calendar mCalendar;
        Time mTime;
        boolean mIsRound =false;

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(F35Face.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        boolean mRegisteredTimeZoneReceiver = false;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            mIsRound = insets.isRound();
            /*
            if(mIsRound) {
                mHandPaint.setTextSize(getResources().getDimension(R.dimen.font_size_time_round));
            }else{
                mHandPaint.setTextSize(getResources().getDimension(R.dimen.font_size_time_square));
            }
            */
        }

        @Override
        public void onTapCommand(@TapType int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case WatchFaceService.TAP_TYPE_TAP:
                    //detect screen area (CENTER_LEFT, CENTER_RIGHT, BOTTOM_CENTER)
                    handleTouch(x,y);
                    invalidate();
                    break;

                case WatchFaceService.TAP_TYPE_TOUCH:
                    break;
                case WatchFaceService.TAP_TYPE_TOUCH_CANCEL:
                    break;

                default:
                    super.onTapCommand(tapType, x, y, eventTime);
                    break;
            }
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(F35Face.this)
                    .setAcceptsTapEvents(true)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                            //.setStatusBarGravity(Gravity.CENTER_VERTICAL)
                    .setShowSystemUiTime(false).
                            setViewProtectionMode(WatchFaceStyle.PROTECT_STATUS_BAR)
                    .build());

            bg = BitmapFactory.decodeResource(getResources(), R.drawable.background);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setAntiAlias(true);
            mBackgroundPaint.setColor(getResources().getColor(R.color.dark_gray));

            mSecondsCirclePaint= new Paint();
            mSecondsCirclePaint.setAntiAlias(true);
            mSecondsCirclePaint.setStyle(Paint.Style.FILL);
            mSecondsCirclePaint.setColor(Color.WHITE);
            mSecondsCirclePaint.setStrokeWidth(ScreenUtils.convertDpToPixels(getApplicationContext(), 3f));
            //mSecondsCirclePaint.setShadowLayer(2, 0, 0, Color.BLACK);

            mComplicationPaint= new Paint();
            mComplicationPaint.setAntiAlias(true);
            mComplicationPaint.setTextAlign(Paint.Align.CENTER);
            mComplicationPaint.setColor(getResources().getColor(R.color.white));
            mComplicationPaint.setTypeface(Typeface.createFromAsset(getApplicationContext().getAssets(), "fonts/Dolce Vita.ttf"));
            mComplicationPaint.setTextSize(getResources().getDimension(R.dimen.font_size_string));

            mHandPaint= new Paint();
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            mHandPaint.setColor(Color.RED);
            mHandPaint.setStrokeWidth(ScreenUtils.convertDpToPixels(getApplicationContext(), 1f));

            blackFillPaint = new Paint();
            blackFillPaint.setColor(Color.BLACK);
            blackFillPaint.setStyle(Paint.Style.FILL);
            blackFillPaint.setAntiAlias(true);

            whiteFillPaint = new Paint();
            whiteFillPaint.setColor(Color.WHITE);
            whiteFillPaint.setStyle(Paint.Style.FILL);
            whiteFillPaint.setAntiAlias(true);
            whiteFillPaint.setFilterBitmap(true);

            mTime = new Time();
            mCalendar = Calendar.getInstance();

            selectedColorCode = GradientsUtils.getGradients(getApplicationContext(),0);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
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
                    //mHandPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            int width = bounds.width();
            int height = bounds.height();
            /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            final float seconds =
                    (mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f);
            final float secondsRotation = seconds * 6f;

            final float minutesRotation = mCalendar.get(Calendar.MINUTE) * 6f;

            final float hourHandOffset = mCalendar.get(Calendar.MINUTE) / 2f;
            final float hoursRotation = (mCalendar.get(Calendar.HOUR) * 30) + hourHandOffset;

            //BACKGROUND
            if (!mAmbient && !nightMode) {
                //Draw bg bitmap
                Rect src = new Rect(0,0, bg.getWidth(), bg.getHeight());
                canvas.drawBitmap(bg, src, bounds, whiteFillPaint);
            }else {//AMBIENT MODE
                //BLACK BG TO SAVE ENERGY
                canvas.drawColor(Color.BLACK);
            }

            //COMPLICATIONS
            if(!mAmbient) {
                //left bottom
                drawLeftComplication(canvas, width, height);
                //right bottom
                drawRightComplication(canvas, width, height);
            }
            //END COMPLICATIONS

            //Hands sizes and round rect readius
            int RR = ScreenUtils.convertDpToPixels(getApplicationContext(), 10);
            int RRradius = ScreenUtils.convertDpToPixels(getApplicationContext(), 4.5f);

            //Minutes hand
            canvas.save();
            canvas.rotate(minutesRotation, width / 2, width / 2);
            canvas.drawLine(width / 2, height / 2, width / 2, (height / 2F) * 0.20F, mSecondsCirclePaint);
            canvas.drawRoundRect(width / 2 - RRradius, (height / 2F) * 0.20F, width / 2 + RRradius, height / 2f * 4f / 5f, RR, RR, mSecondsCirclePaint);
            canvas.restore();
            //END Minutes hands

            //Hours hand
            canvas.save();
            canvas.rotate(hoursRotation, width / 2, width / 2);
            canvas.drawLine(width / 2, height / 2, width / 2, (height / 2F) * 0.35F, mSecondsCirclePaint);
            canvas.drawRoundRect(width / 2 - RRradius, (height / 2F) * 0.35F, width / 2 + RRradius, height / 2f * 4f / 5f, RR, RR, mSecondsCirclePaint);
            mHandPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(width/2, (height / 2F) * 0.39F, ScreenUtils.convertDpToPixels(getApplicationContext(), 3F),mHandPaint);
            canvas.restore();
            //END Hours hand

            //Center circle
            canvas.drawCircle(width / 2, height / 2, ScreenUtils.convertDpToPixels(getApplicationContext(), 6), mSecondsCirclePaint);

            //Seconds hand
            if(!mAmbient) {
                canvas.save();
                canvas.rotate(secondsRotation, width / 2, width / 2);
                mHandPaint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(width / 2, height / 2 + (height / 15) * 2f, width / 2, (height / 25), mHandPaint);
                canvas.restore();
            }
            //END seconds hand

            //Red center circle
            if(!mAmbient) {
                mHandPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(width / 2, height / 2, ScreenUtils.convertDpToPixels(getApplicationContext(), 3.5f), mHandPaint);
            }else {
                mHandPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(width / 2, height / 2, ScreenUtils.convertDpToPixels(getApplicationContext(), 3.5f), blackFillPaint);
            }
        }

        private void drawLeftComplication(Canvas canvas, int width, int height) {
            drawMonthAndYear(canvas, width, height);
        }

        private void drawRightComplication(Canvas canvas, int width, int height) {
            drawWeekDays(canvas, width, height);
        }

        private void drawWeekDays(Canvas canvas, int width, int height) {
            float RX = height*0.64f;
            float RY = height*0.64f;
            float CR = width/8.5f;
            String[] days =getWeekDaysSymbols();
            Path rPath = new Path();
            rPath.addCircle(RX, RY, CR * 0.7f, Path.Direction.CW);
            for(int i=0; i<days.length; i++) {

                if(days[i] == null || days[i].equalsIgnoreCase("")) continue;

                canvas.save();
                canvas.rotate(i * 51.4f, RX, RY);
                if(days[i].toUpperCase().equalsIgnoreCase(getWeekDay())) {
                    mComplicationPaint.setColor(GradientsUtils.getGradients(getApplicationContext(), selectedColorCode));
                }else {
                    mComplicationPaint.setColor(Color.WHITE);
                }
                canvas.drawTextOnPath(days[i].toUpperCase(), rPath, 0, 0, mComplicationPaint);
                mComplicationPaint.setColor(Color.WHITE);
                canvas.restore();
            }
        }

        private void drawMonthAndYear(Canvas canvas, int width, int height) {
            float LX = width*0.36f;
            float LY = height*0.64f;
            float CR = width/8.5f;
            //left bottom
            //canvas.drawCircle(LX, LY, CR, mSecondsCirclePaint);
            canvas.save();
            canvas.rotate(90, LX, LY);
            Path path = new Path();
            path.addCircle(LX, LY, CR * 0.7f, Path.Direction.CW);
            canvas.drawTextOnPath("TEMPERATURE", path, 0, 0, mComplicationPaint);
            canvas.restore();
        }

        private String[] getWeekDaysSymbols(){
            DateFormatSymbols symbols = new DateFormatSymbols();
            String[] dayNames = symbols.getShortWeekdays();
            return dayNames;
        }

        private String getWeekDay() {
            mBackgroundPaint.setTextSize(getResources().getDimension(R.dimen.font_size_string));
            return new SimpleDateFormat("EEE").format(Calendar.getInstance().getTime()).toUpperCase();
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
            F35Face.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            F35Face.this.unregisterReceiver(mTimeZoneReceiver);
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


        private void updateConfigDataItemAndUiOnStartup() {
            WatchFaceUtil.fetchConfigDataMap(mGoogleApiClient,
                    new WatchFaceUtil.FetchConfigDataMapCallback() {
                        @Override
                        public void onConfigDataMapFetched(DataMap startupConfig) {
                            // If the DataItem hasn't been created yet or some keys are missing,
                            // use the default values.
                            setDefaultValuesForMissingConfigKeys(startupConfig);
                            WatchFaceUtil.putConfigDataItem(mGoogleApiClient, startupConfig);

                            updateUiForConfigDataMap(startupConfig);
                        }
                    }
            );
        }

        private void setDefaultValuesForMissingConfigKeys(DataMap config) {
            addIntKeyIfMissing(config, WatchFaceUtil.KEY_BACKGROUND_COLOR,
                    WatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_BACKGROUND);
        }

        private void addIntKeyIfMissing(DataMap config, String key, int color) {
            if (!config.containsKey(key)) {
                config.putInt(key, color);
            }
        }

        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                if (!dataItem.getUri().getPath().equals(
                        WatchFaceUtil.PATH_WITH_FEATURE)) {
                    continue;
                }

                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap config = dataMapItem.getDataMap();
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Config DataItem updated:" + config);
                }
                updateUiForConfigDataMap(config);
            }
        }

        private void updateUiForConfigDataMap(final DataMap config) {
            boolean uiUpdated = false;
            for (String configKey : config.keySet()) {
                if (!config.containsKey(configKey)) {
                    continue;
                }

                if(configKey.equalsIgnoreCase(WatchFaceUtil.KEY_BACKGROUND_COLOR)) {
                    int color = config.getInt(configKey);
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Found watch face config key: " + configKey + " -> "
                                + color);
                    }
                    if (updateUiForKey(configKey, color)) {
                        uiUpdated = true;
                    }
                }
            }
            if (uiUpdated) {
                invalidate();
            }
        }

        /**
         * Updates the color of a UI item according to the given {@code configKey}. Does nothing if
         * {@code configKey} isn't recognized.
         *
         * @return whether UI has been updated
         */
        private boolean updateUiForKey(String configKey, int color) {
            if (configKey.equals(WatchFaceUtil.KEY_BACKGROUND_COLOR)) {
                setGradient(color);
            } else {
                Log.w(TAG, "Ignoring unknown config key: " + configKey);
                return false;
            }
            return true;
        }

        private void setGradient(int color) {
            Log.d("color=",color+"");
            selectedColorCode = color;
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + connectionHint);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            updateConfigDataItemAndUiOnStartup();
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(ConnectionResult result) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + result);
            }
        }
    }

    private void handleTouch(int x, int y) {
        int W = ScreenUtils.getScreenWidth(getApplicationContext());
        int H = ScreenUtils.getScreenHeight(getApplicationContext());
        int DELTA_X =W/5;
        int DELTA_Y = H/5;
        //LEFT CENTER
        if(x <(W/4 + DELTA_X) && x >(W/4 - DELTA_X)) {
            if(y <(H/2 + DELTA_Y) && y >(H/2 - DELTA_Y)) {
               handleTouchLeftCenter();
                return;
            }
        }
        //RIGHT CENTER
        if(x <(W*3/4 + DELTA_X) && x >(W*3/4 - DELTA_X)) {
            if(y <(H/2 + DELTA_Y) && y >(H/2 - DELTA_Y)) {
                handleTouchRightCenter();
                return;
            }
        }
        //BOTTOM CENTER
        if(x <(W/2 + DELTA_X) && x >(W/2 - DELTA_X)) {
            if(y <(H*3/4 + DELTA_Y) && y >(H*3/4 - DELTA_Y)) {
                handleTouchBottomCenter();
                return;
            }
        }
    }

    private void handleTouchBottomCenter() {
        if(BOTTOM_COMPLICATION_MODE == BATTERY) BOTTOM_COMPLICATION_MODE =DAY_NUMBER;
        else if(BOTTOM_COMPLICATION_MODE == DAY_NUMBER) BOTTOM_COMPLICATION_MODE =DAY_WEEK;
        else if(BOTTOM_COMPLICATION_MODE == DAY_WEEK) BOTTOM_COMPLICATION_MODE =MONTH;
        else if(BOTTOM_COMPLICATION_MODE == MONTH) BOTTOM_COMPLICATION_MODE =YEAR;
        else if(BOTTOM_COMPLICATION_MODE == YEAR) BOTTOM_COMPLICATION_MODE =NONE;
        else BOTTOM_COMPLICATION_MODE =BATTERY;
    }

    private void handleTouchRightCenter() {
        if(RIGHT_COMPLICATION_MODE == BATTERY) RIGHT_COMPLICATION_MODE =DAY_NUMBER;
        else if(RIGHT_COMPLICATION_MODE == DAY_NUMBER) RIGHT_COMPLICATION_MODE =DAY_WEEK;
        else if(RIGHT_COMPLICATION_MODE == DAY_WEEK) RIGHT_COMPLICATION_MODE =MONTH;
        else if(RIGHT_COMPLICATION_MODE == MONTH) RIGHT_COMPLICATION_MODE =YEAR;
        else if(RIGHT_COMPLICATION_MODE == YEAR) RIGHT_COMPLICATION_MODE =NONE;
        else RIGHT_COMPLICATION_MODE =BATTERY;
    }

    private void handleTouchLeftCenter() {
        if(LEFT_COMPLICATION_MODE == BATTERY) LEFT_COMPLICATION_MODE =DAY_NUMBER;
        else if(LEFT_COMPLICATION_MODE == DAY_NUMBER) LEFT_COMPLICATION_MODE =DAY_WEEK;
        else if(LEFT_COMPLICATION_MODE == DAY_WEEK) LEFT_COMPLICATION_MODE =MONTH;
        else if(LEFT_COMPLICATION_MODE == MONTH) LEFT_COMPLICATION_MODE =YEAR;
        else if(LEFT_COMPLICATION_MODE == YEAR) LEFT_COMPLICATION_MODE =NONE;
        else LEFT_COMPLICATION_MODE =BATTERY;
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<F35Face.Engine> mWeakReference;

        public EngineHandler(F35Face.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            F35Face.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private String getYear() {
        return new SimpleDateFormat("yyyy").format(Calendar.getInstance().getTime()).toUpperCase();
    }

    private String getMonth() {
        return new SimpleDateFormat("MMM").format(Calendar.getInstance().getTime()).toUpperCase();
    }

    private String getDayNumber() {
        return new SimpleDateFormat("d").format(Calendar.getInstance().getTime()).toUpperCase();
    }

    private String getWeekDay() {
        return new SimpleDateFormat("EEE").format(Calendar.getInstance().getTime()).toUpperCase();
    }

    public int getBatteryLevel() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        // Error checking that probably isn't needed but I added just in case.
        if(level == -1 || scale == -1) {
            return 50;
        }

        return (int)(((float)level / (float)scale) * 100.0f);
    }
}