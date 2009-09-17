/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.camera;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.Size;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.provider.MediaStore;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.MenuItem.OnMenuItemClickListener;
import android.widget.ImageView;
import android.widget.ZoomButtonsController;

import com.android.camera.gallery.IImage;
import com.android.camera.gallery.IImageList;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Activity of the Camera which used to see preview and take pictures.
 */
public class Camera extends Activity implements View.OnClickListener,
        ShutterButton.OnShutterButtonListener, SurfaceHolder.Callback,
        Switcher.OnSwitchListener, OnScreenSettings.OnVisibilityChangedListener,
        OnSharedPreferenceChangeListener {

    private static final String TAG = "camera";

    private static final int CROP_MSG = 1;
    private static final int FIRST_TIME_INIT = 2;
    private static final int RESTART_PREVIEW = 3;
    private static final int CLEAR_SCREEN_DELAY = 4;

    private static final int SCREEN_DELAY = 2 * 60 * 1000;
    private static final int FOCUS_BEEP_VOLUME = 100;

    private double mZoomValue;  // The current zoom value.
    private double mZoomStep;
    private double mZoomMax;
    public static final double ZOOM_STEP_MIN = 0.25;
    public static final String ZOOM_STOP = "stop";
    public static final String ZOOM_IMMEDIATE = "zoom-immediate";
    public static final String ZOOM_CONTINUOUS = "zoom-continuous";
    public static final double ZOOM_MIN = 1.0;
    public static final String ZOOM_SPEED = "99";

    private Parameters mParameters;

    // The parameter strings to communicate with camera driver.
    public static final String PARM_ZOOM_STATE = "zoom-state";
    public static final String PARM_ZOOM_STEP = "zoom-step";
    public static final String PARM_ZOOM_TO_LEVEL = "zoom-to-level";
    public static final String PARM_ZOOM_SPEED = "zoom-speed";
    public static final String PARM_ZOOM_MAX = "max-picture-continuous-zoom";

    private OrientationEventListener mOrientationListener;
    private int mLastOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
    private SharedPreferences mPreferences;

    private static final int IDLE = 1;
    private static final int SNAPSHOT_IN_PROGRESS = 2;

    private static final boolean SWITCH_CAMERA = true;
    private static final boolean SWITCH_VIDEO = false;

    private int mStatus = IDLE;
    private static final String sTempCropFilename = "crop-temp";

    private android.hardware.Camera mCameraDevice;
    private VideoPreview mSurfaceView;
    private SurfaceHolder mSurfaceHolder = null;
    private ShutterButton mShutterButton;
    private FocusRectangle mFocusRectangle;
    private ImageView mGpsIndicator;
    private ToneGenerator mFocusToneGenerator;
    private ZoomButtonsController mZoomButtons;
    private GestureDetector mGestureDetector;
    private Switcher mSwitcher;
    private boolean mStartPreviewFail = false;

    // mPostCaptureAlert, mLastPictureButton, mThumbController
    // are non-null only if isImageCaptureIntent() is true.
    private ImageView mLastPictureButton;
    private ThumbnailController mThumbController;

    private int mViewFinderWidth, mViewFinderHeight;

    private ImageCapture mImageCapture = null;

    private boolean mPreviewing;
    private boolean mPausing;
    private boolean mFirstTimeInitialized;
    private boolean mIsImageCaptureIntent;
    private boolean mRecordLocation;

    private static final int FOCUS_NOT_STARTED = 0;
    private static final int FOCUSING = 1;
    private static final int FOCUSING_SNAP_ON_FINISH = 2;
    private static final int FOCUS_SUCCESS = 3;
    private static final int FOCUS_FAIL = 4;
    private int mFocusState = FOCUS_NOT_STARTED;

    private ContentResolver mContentResolver;
    private boolean mDidRegister = false;

    private final ArrayList<MenuItem> mGalleryItems = new ArrayList<MenuItem>();

    private LocationManager mLocationManager = null;

    // Use OneShotPreviewCallback to measure the time between
    // JpegPictureCallback and preview.
    private final OneShotPreviewCallback mOneShotPreviewCallback =
            new OneShotPreviewCallback();
    private final ShutterCallback mShutterCallback = new ShutterCallback();
    private final RawPictureCallback mRawPictureCallback =
            new RawPictureCallback();
    private final AutoFocusCallback mAutoFocusCallback =
            new AutoFocusCallback();
    private final ZoomCallback mZoomCallback = new ZoomCallback();
    // Use the ErrorCallback to capture the crash count
    // on the mediaserver
    private final ErrorCallback mErrorCallback = new ErrorCallback();

    private long mFocusStartTime;
    private long mFocusCallbackTime;
    private long mCaptureStartTime;
    private long mShutterCallbackTime;
    private long mRawPictureCallbackTime;
    private long mJpegPictureCallbackTime;
    private int mPicturesRemaining;

    // These latency time are for the CameraLatency test.
    public long mAutoFocusTime;
    public long mShutterLag;
    public long mShutterAndRawPictureCallbackTime;
    public long mJpegPictureCallbackTimeLag;
    public long mRawPictureAndJpegPictureCallbackTime;

    // Add the media server tag
    public static boolean mMediaServerDied = false;
    // Focus mode. Options are pref_camera_focusmode_entryvalues.
    private String mFocusMode;

    private final Handler mHandler = new MainHandler();
    private OnScreenSettings mSettings;

    /**
     * This Handler is used to post message back onto the main thread of the
     * application
     */
    private class MainHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case RESTART_PREVIEW: {
                    restartPreview();
                    break;
                }

                case CLEAR_SCREEN_DELAY: {
                    getWindow().clearFlags(
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    break;
                }

                case FIRST_TIME_INIT: {
                    initializeFirstTime();
                    break;
                }
            }
        }
    }

    // Snapshots can only be taken after this is called. It should be called
    // once only. We could have done these things in onCreate() but we want to
    // make preview screen appear as soon as possible.
    private void initializeFirstTime() {
        if (mFirstTimeInitialized) return;

        // Create orientation listenter. This should be done first because it
        // takes some time to get first orientation.
        mOrientationListener =
                new OrientationEventListener(Camera.this) {
            @Override
            public void onOrientationChanged(int orientation) {
                // We keep the last known orientation. So if the user
                // first orient the camera then point the camera to
                // floor/sky, we still have the correct orientation.
                if (orientation != ORIENTATION_UNKNOWN) {
                    mLastOrientation = orientation;
                }
            }
        };
        mOrientationListener.enable();

        // Initialize location sevice.
        mLocationManager = (LocationManager)
                getSystemService(Context.LOCATION_SERVICE);
        readPreference();
        if (mRecordLocation) startReceivingLocationUpdates();

        checkStorage();

        // Initialize last picture button.
        mContentResolver = getContentResolver();
        if (!mIsImageCaptureIntent)  {
            findViewById(R.id.camera_switch).setOnClickListener(this);
            mLastPictureButton =
                    (ImageView) findViewById(R.id.review_thumbnail);
            mLastPictureButton.setOnClickListener(this);
            mThumbController = new ThumbnailController(
                    getResources(), mLastPictureButton, mContentResolver);
            mThumbController.loadData(ImageManager.getLastImageThumbPath());
            // Update last image thumbnail.
            updateThumbnailButton();
        }

        // Initialize shutter button.
        mShutterButton = (ShutterButton) findViewById(R.id.shutter_button);
        mShutterButton.setOnShutterButtonListener(this);
        mShutterButton.setVisibility(View.VISIBLE);

        mFocusRectangle = (FocusRectangle) findViewById(R.id.focus_rectangle);
        updateFocusIndicator();

        // Initialize GPS indicator.
        mGpsIndicator = (ImageView) findViewById(R.id.gps_indicator);
        mGpsIndicator.setImageResource(R.drawable.ic_camera_sym_gps);

        ImageManager.ensureOSXCompatibleFolder();

        installIntentFilter();

        initializeFocusTone();

        initializeZoom();

        mFirstTimeInitialized = true;
    }

    private void updateThumbnailButton() {
        // Update last image if URI is invalid and the storage is ready.
        if (!mThumbController.isUriValid() && mPicturesRemaining >= 0) {
            updateLastImage();
        }
        mThumbController.updateDisplayIfNeeded();
    }

    // If the activity is paused and resumed, this method will be called in
    // onResume.
    private void initializeSecondTime() {
        // Start orientation listener as soon as possible because it takes
        // some time to get first orientation.
        mOrientationListener.enable();

        // Start location update if needed.
        readPreference();
        if (mRecordLocation) startReceivingLocationUpdates();

        installIntentFilter();

        initializeFocusTone();

        checkStorage();

        if (mZoomButtons != null) {
            mZoomValue = Double.parseDouble(
                    mParameters.get(PARM_ZOOM_TO_LEVEL));
            mCameraDevice.setZoomCallback(mZoomCallback);
        }

        if (!mIsImageCaptureIntent) {
            updateThumbnailButton();
        }
    }

    private void initializeZoom() {
        // Check if the phone has zoom capability.
        String zoomState = mParameters.get(PARM_ZOOM_STATE);
        if (zoomState == null) return;

        mZoomValue = Double.parseDouble(mParameters.get(PARM_ZOOM_TO_LEVEL));
        mZoomMax = Double.parseDouble(mParameters.get(PARM_ZOOM_MAX));
        mZoomStep = Double.parseDouble(mParameters.get(PARM_ZOOM_STEP));
        mParameters.set(PARM_ZOOM_SPEED, ZOOM_SPEED);
        mCameraDevice.setParameters(mParameters);

        mGestureDetector = new GestureDetector(this, new ZoomGestureListener());
        mCameraDevice.setZoomCallback(mZoomCallback);
        mZoomButtons = new ZoomButtonsController(mSurfaceView);
        mZoomButtons.setAutoDismissed(true);
        mZoomButtons.setZoomSpeed(100);
        mZoomButtons.setOnZoomListener(
                new ZoomButtonsController.OnZoomListener() {
            public void onVisibilityChanged(boolean visible) {
                if (visible) {
                    updateZoomButtonsEnabled();
                }
            }

            public void onZoom(boolean zoomIn) {
                if (isZooming()) return;

                if (zoomIn) {
                    if (mZoomValue < mZoomMax) {
                        zoomToLevel(ZOOM_CONTINUOUS, mZoomValue + mZoomStep);
                    }
                } else {
                    if (mZoomValue > ZOOM_MIN) {
                        zoomToLevel(ZOOM_CONTINUOUS, mZoomValue - mZoomStep);
                    }
                }
            }
        });
    }

    public void onVisibilityChanged(boolean visible) {
        // When the on-screen setting is not displayed, we show the gripper.
        // When the on-screen setting is displayed, we hide the gripper.
        findViewById(R.id.btn_gripper).setVisibility(
                visible ? View.INVISIBLE : View.VISIBLE);
    }

    private boolean isZooming() {
        mParameters = mCameraDevice.getParameters();
        return "continuous".equals(mParameters.get(PARM_ZOOM_STATE));
    }

    private void zoomToLevel(String type, double zoomValue) {
        if (zoomValue > mZoomMax) zoomValue = mZoomMax;
        if (zoomValue < ZOOM_MIN) zoomValue = ZOOM_MIN;

        // If the application sets a unchanged zoom value, the driver will stuck
        // at the zoom state. This is a work-around to ensure the state is at
        // "stop".
        mParameters.set(PARM_ZOOM_STATE, ZOOM_STOP);
        mCameraDevice.setParameters(mParameters);

        mParameters.set(PARM_ZOOM_TO_LEVEL, Double.toString(zoomValue));
        mParameters.set(PARM_ZOOM_STATE, type);
        mCameraDevice.setParameters(mParameters);

        if (ZOOM_IMMEDIATE.equals(type)) mZoomValue = zoomValue;
    }

    private void updateZoomButtonsEnabled() {
        mZoomButtons.setZoomInEnabled(mZoomValue < mZoomMax);
        mZoomButtons.setZoomOutEnabled(mZoomValue > ZOOM_MIN);
    }

    private class ZoomGestureListener extends
            GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            // Show zoom buttons only when preview is started and snapshot
            // is not in progress. mZoomButtons may be null if it is not
            // initialized.
            if (!mPausing && isCameraIdle() && mPreviewing
                    && mZoomButtons != null) {
                mZoomButtons.setVisible(true);
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            // Perform zoom only when preview is started and snapshot is not in
            // progress.
            if (mPausing || !isCameraIdle() || !mPreviewing
                    || mZoomButtons == null || isZooming()) {
                return false;
            }

            if (mZoomValue < mZoomMax) {
                // Zoom in to the maximum.
                while (mZoomValue < mZoomMax) {
                    zoomToLevel(ZOOM_IMMEDIATE, mZoomValue + ZOOM_STEP_MIN);
                    // Wait for a while so we are not changing zoom too fast.
                    try {
                        Thread.currentThread().sleep(5);
                    } catch (InterruptedException ex) {
                    }
                }
            } else {
                // Zoom out to the minimum.
                while (mZoomValue > ZOOM_MIN) {
                    zoomToLevel(ZOOM_IMMEDIATE, mZoomValue - ZOOM_STEP_MIN);
                    // Wait for a while so we are not changing zoom too fast.
                    try {
                        Thread.currentThread().sleep(5);
                    } catch (InterruptedException ex) {
                    }
                }
            }
            updateZoomButtonsEnabled();
            return true;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent m) {
        if (!super.dispatchTouchEvent(m) && mGestureDetector != null) {
            return mGestureDetector.onTouchEvent(m);
        }
        return true;
    }

    LocationListener [] mLocationListeners = new LocationListener[] {
            new LocationListener(LocationManager.GPS_PROVIDER),
            new LocationListener(LocationManager.NETWORK_PROVIDER)
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_MEDIA_MOUNTED)
                    || action.equals(Intent.ACTION_MEDIA_UNMOUNTED)
                    || action.equals(Intent.ACTION_MEDIA_CHECKING)
                    || action.equals(Intent.ACTION_MEDIA_SCANNER_STARTED)) {
                checkStorage();
            } else if (action.equals(Intent.ACTION_MEDIA_SCANNER_FINISHED)) {
                checkStorage();
                if (!mIsImageCaptureIntent)  {
                    updateThumbnailButton();
                }
            }
        }
    };

    private class LocationListener
            implements android.location.LocationListener {
        Location mLastLocation;
        boolean mValid = false;
        String mProvider;

        public LocationListener(String provider) {
            mProvider = provider;
            mLastLocation = new Location(mProvider);
        }

        public void onLocationChanged(Location newLocation) {
            if (newLocation.getLatitude() == 0.0
                    && newLocation.getLongitude() == 0.0) {
                // Hack to filter out 0.0,0.0 locations
                return;
            }
            // If GPS is available before start camera, we won't get status
            // update so update GPS indicator when we receive data.
            if (mRecordLocation
                    && LocationManager.GPS_PROVIDER.equals(mProvider)) {
                mGpsIndicator.setVisibility(View.VISIBLE);
            }
            mLastLocation.set(newLocation);
            mValid = true;
        }

        public void onProviderEnabled(String provider) {
        }

        public void onProviderDisabled(String provider) {
            mValid = false;
        }

        public void onStatusChanged(
                String provider, int status, Bundle extras) {
            switch(status) {
                case LocationProvider.OUT_OF_SERVICE:
                case LocationProvider.TEMPORARILY_UNAVAILABLE: {
                    mValid = false;
                    if (mRecordLocation &&
                            LocationManager.GPS_PROVIDER.equals(provider)) {
                        mGpsIndicator.setVisibility(View.INVISIBLE);
                    }
                    break;
                }
            }
        }

        public Location current() {
            return mValid ? mLastLocation : null;
        }
    }

    private final class OneShotPreviewCallback
            implements android.hardware.Camera.PreviewCallback {
        public void onPreviewFrame(byte[] data,
                                   android.hardware.Camera camera) {
            long now = System.currentTimeMillis();
            if (mJpegPictureCallbackTime != 0) {
                mJpegPictureCallbackTimeLag = now - mJpegPictureCallbackTime;
                Log.v(TAG, "mJpegPictureCallbackTimeLag = "
                        + mJpegPictureCallbackTimeLag + "ms");
                mJpegPictureCallbackTime = 0;
            } else {
                Log.v(TAG, "Got first frame");
            }
        }
    }

    private final class ShutterCallback
            implements android.hardware.Camera.ShutterCallback {
        public void onShutter() {
            mShutterCallbackTime = System.currentTimeMillis();
            mShutterLag = mShutterCallbackTime - mCaptureStartTime;
            Log.v(TAG, "mShutterLag = " + mShutterLag + "ms");
            clearFocusState();
        }
    }

    private final class RawPictureCallback implements PictureCallback {
        public void onPictureTaken(
                byte [] rawData, android.hardware.Camera camera) {
            mRawPictureCallbackTime = System.currentTimeMillis();
            mShutterAndRawPictureCallbackTime =
                mRawPictureCallbackTime - mShutterCallbackTime;
            Log.v(TAG, "mShutterAndRawPictureCallbackTime = "
                    + mShutterAndRawPictureCallbackTime + "ms");
        }
    }

    private final class JpegPictureCallback implements PictureCallback {
        Location mLocation;

        public JpegPictureCallback(Location loc) {
            mLocation = loc;
        }

        public void onPictureTaken(
                final byte [] jpegData, final android.hardware.Camera camera) {
            if (mPausing) {
                return;
            }

            mJpegPictureCallbackTime = System.currentTimeMillis();
            mRawPictureAndJpegPictureCallbackTime =
                mJpegPictureCallbackTime - mRawPictureCallbackTime;
            Log.v(TAG, "mRawPictureAndJpegPictureCallbackTime = "
                    + mRawPictureAndJpegPictureCallbackTime + "ms");
            mImageCapture.storeImage(jpegData, camera, mLocation);

            if (!mIsImageCaptureIntent) {
                long delay = 1200 - (
                        System.currentTimeMillis() - mRawPictureCallbackTime);
                mHandler.sendEmptyMessageDelayed(
                        RESTART_PREVIEW, Math.max(delay, 0));
            }
        }
    }

    private final class AutoFocusCallback
            implements android.hardware.Camera.AutoFocusCallback {
        public void onAutoFocus(
                boolean focused, android.hardware.Camera camera) {
            mFocusCallbackTime = System.currentTimeMillis();
            mAutoFocusTime = mFocusCallbackTime - mFocusStartTime;
            Log.v(TAG, "mAutoFocusTime = " + mAutoFocusTime + "ms");
            if (mFocusState == FOCUSING_SNAP_ON_FINISH) {
                // Take the picture no matter focus succeeds or fails. No need
                // to play the AF sound if we're about to play the shutter
                // sound.
                if (focused) {
                    mFocusState = FOCUS_SUCCESS;
                } else {
                    mFocusState = FOCUS_FAIL;
                }
                mImageCapture.onSnap();
            } else if (mFocusState == FOCUSING) {
                // User is half-pressing the focus key. Play the focus tone.
                // Do not take the picture now.
                ToneGenerator tg = mFocusToneGenerator;
                if (tg != null) {
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP2);
                }
                if (focused) {
                    mFocusState = FOCUS_SUCCESS;
                } else {
                    mFocusState = FOCUS_FAIL;
                }
            } else if (mFocusState == FOCUS_NOT_STARTED) {
                // User has released the focus key before focus completes.
                // Do nothing.
            }
            updateFocusIndicator();
        }
    }

    private final class ErrorCallback
        implements android.hardware.Camera.ErrorCallback {
        public void onError(int error, android.hardware.Camera camera) {
            if (error == android.hardware.Camera.CAMERA_ERROR_SERVER_DIED) {
                 mMediaServerDied = true;
                 Log.v(TAG, "media server died");
            }
        }
    }

    private final class ZoomCallback
        implements android.hardware.Camera.ZoomCallback {
        public void onZoomUpdate(int zoomLevel,
                                 android.hardware.Camera camera) {
            mZoomValue = (double) zoomLevel / 1000;
            Log.v(TAG, "ZoomCallback: zoom level=" + zoomLevel);
            updateZoomButtonsEnabled();
        }
    }

    private class ImageCapture {

        private boolean mCancel = false;

        private Uri mLastContentUri;

        Bitmap mCaptureOnlyBitmap;

        private void storeImage(byte[] data, Location loc) {
            try {
                long dateTaken = System.currentTimeMillis();
                String name = createName(dateTaken) + ".jpg";
                mLastContentUri = ImageManager.addImage(
                        mContentResolver,
                        name,
                        dateTaken,
                        loc, // location for the database goes here
                        0, // the dsp will use the right orientation so
                           // don't "double set it"
                        ImageManager.CAMERA_IMAGE_BUCKET_NAME,
                        name);
                if (mLastContentUri == null) {
                    // this means we got an error
                    mCancel = true;
                }
                if (!mCancel) {
                    ImageManager.storeImage(
                            mLastContentUri, mContentResolver,
                            0, null, data);
                    ImageManager.setImageSize(mContentResolver, mLastContentUri,
                            new File(ImageManager.CAMERA_IMAGE_BUCKET_NAME,
                            name).length());
                }
            } catch (Exception ex) {
                Log.e(TAG, "Exception while compressing image.", ex);
            }
        }

        public void storeImage(final byte[] data,
                android.hardware.Camera camera, Location loc) {
            if (!mIsImageCaptureIntent) {
                storeImage(data, loc);
                sendBroadcast(new Intent(
                        "com.android.camera.NEW_PICTURE", mLastContentUri));
                setLastPictureThumb(data, mImageCapture.getLastCaptureUri());
                mThumbController.updateDisplayIfNeeded();
            } else {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 4;
                mCaptureOnlyBitmap = BitmapFactory.decodeByteArray(
                        data, 0, data.length, options);
                showPostCaptureAlert();
            }
        }

        /**
         * Initiate the capture of an image.
         */
        public void initiate() {
            if (mCameraDevice == null) {
                return;
            }

            mCancel = false;

            capture();
        }

        public Uri getLastCaptureUri() {
            return mLastContentUri;
        }

        public Bitmap getLastBitmap() {
            return mCaptureOnlyBitmap;
        }

        private void capture() {
            mCaptureOnlyBitmap = null;

            // Set rotation.
            int orientation = mLastOrientation;
            if (orientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                orientation += 90;
            }
            orientation = ImageManager.roundOrientation(orientation);
            Log.v(TAG, "mLastOrientation = " + mLastOrientation
                    + ", orientation = " + orientation);
            mParameters.setRotation(orientation);

            // Clear previous GPS location from the parameters.
            mParameters.removeGpsData();

            // Set GPS location.
            Location loc = mRecordLocation ? getCurrentLocation() : null;
            if (loc != null) {
                double lat = loc.getLatitude();
                double lon = loc.getLongitude();
                boolean hasLatLon = (lat != 0.0d) || (lon != 0.0d);

                if (hasLatLon) {
                    mParameters.setGpsLatitude(lat);
                    mParameters.setGpsLongitude(lon);
                    if (loc.hasAltitude()) {
                        mParameters.setGpsAltitude(loc.getAltitude());
                    } else {
                        // for NETWORK_PROVIDER location provider, we may have
                        // no altitude information, but the driver needs it, so
                        // we fake one.
                        mParameters.setGpsAltitude(0);
                    }
                    if (loc.getTime() != 0) {
                        // Location.getTime() is UTC in milliseconds.
                        // gps-timestamp is UTC in seconds.
                        long utcTimeSeconds = loc.getTime() / 1000;
                        mParameters.setGpsTimestamp(utcTimeSeconds);
                    }
                } else {
                    loc = null;
                }
            }

            mCameraDevice.setParameters(mParameters);

            mCameraDevice.takePicture(mShutterCallback, mRawPictureCallback,
                    new JpegPictureCallback(loc));
            mPreviewing = false;
        }

        public void onSnap() {
            // If we are already in the middle of taking a snapshot then ignore.
            if (mPausing || mStatus == SNAPSHOT_IN_PROGRESS) {
                return;
            }
            mCaptureStartTime = System.currentTimeMillis();

            // Don't check the filesystem here, we can't afford the latency.
            // Instead, check the cached value which was calculated when the
            // preview was restarted.
            if (mPicturesRemaining < 1) {
                updateStorageHint(mPicturesRemaining);
                return;
            }

            mStatus = SNAPSHOT_IN_PROGRESS;

            mImageCapture.initiate();
        }

        private void clearLastBitmap() {
            if (mCaptureOnlyBitmap != null) {
                mCaptureOnlyBitmap.recycle();
                mCaptureOnlyBitmap = null;
            }
        }
    }

    private void setLastPictureThumb(byte[] data, Uri uri) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 16;
        Bitmap lastPictureThumb =
                BitmapFactory.decodeByteArray(data, 0, data.length, options);
        mThumbController.setData(uri, lastPictureThumb);
    }

    private static String createName(long dateTaken) {
        return DateFormat.format("yyyy-MM-dd kk.mm.ss", dateTaken).toString();
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Window win = getWindow();
        win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.camera);
        mSurfaceView = (VideoPreview) findViewById(R.id.camera_preview);
        mViewFinderWidth = mSurfaceView.getLayoutParams().width;
        mViewFinderHeight = mSurfaceView.getLayoutParams().height;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mPreferences.registerOnSharedPreferenceChangeListener(this);

        /*
         * To reduce startup time, we start the preview in another thread.
         * We make sure the preview is started at the end of onCreate.
         */
        Thread startPreviewThread = new Thread(new Runnable() {
            public void run() {
                try {
                    mStartPreviewFail = false;
                    startPreview();
                } catch (CameraHardwareException e) {
                    // In eng build, we throw the exception so that test tool
                    // can detect it and report it
                    if ("eng".equals(Build.TYPE)) {
                        throw new RuntimeException(e);
                    }
                    mStartPreviewFail = true;
                }
            }
        });
        startPreviewThread.start();

        // don't set mSurfaceHolder here. We have it set ONLY within
        // surfaceChanged / surfaceDestroyed, other parts of the code
        // assume that when it is set, the surface is also set.
        SurfaceHolder holder = mSurfaceView.getHolder();
        holder.addCallback(this);
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        mIsImageCaptureIntent = isImageCaptureIntent();
        LayoutInflater inflater = getLayoutInflater();

        ViewGroup rootView = (ViewGroup) findViewById(R.id.camera);
        if (mIsImageCaptureIntent) {
            View controlBar = inflater.inflate(
                    R.layout.attach_camera_control, rootView);
            controlBar.findViewById(R.id.btn_cancel).setOnClickListener(this);
            controlBar.findViewById(R.id.btn_retake).setOnClickListener(this);
            controlBar.findViewById(R.id.btn_done).setOnClickListener(this);
        } else {
            inflater.inflate(R.layout.camera_control, rootView);
            mSwitcher = ((Switcher) findViewById(R.id.camera_switch));
            mSwitcher.setOnSwitchListener(this);
            mSwitcher.addTouchView(findViewById(R.id.camera_switch_set));
        }
        findViewById(R.id.btn_gripper)
                .setOnTouchListener(new GripperTouchListener());

        // Make sure preview is started.
        try {
            startPreviewThread.join();
            if (mStartPreviewFail) {
                showCameraErrorAndFinish();
                return;
            }
        } catch (InterruptedException ex) {
            // ignore
        }

        // Resize mVideoPreview to the right aspect ratio.
        resizeForPreviewAspectRatio(mSurfaceView);
    }

    private class GripperTouchListener implements View.OnTouchListener {
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    return true;
                case MotionEvent.ACTION_UP:
                    showOnScreenSettings();
                    return true;
            }
            return false;
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!mIsImageCaptureIntent) {
            mSwitcher.setSwitch(SWITCH_CAMERA);
        }
    }

    private void checkStorage() {
        if (ImageManager.isMediaScannerScanning(getContentResolver())) {
            mPicturesRemaining = MenuHelper.NO_STORAGE_ERROR;
        } else {
            calculatePicturesRemaining();
        }
        updateStorageHint(mPicturesRemaining);
    }

    private void showOnScreenSettings() {
        if (mSettings == null) {
            mSettings = new OnScreenSettings(
                    findViewById(R.id.camera_preview));
            CameraSettings helper = new CameraSettings(this, mParameters);
            mSettings.setPreferenceScreen(helper
                    .getPreferenceScreen(R.xml.camera_preferences));
            mSettings.setOnVisibilityChangedListener(this);
        }
        mSettings.expandPanel();
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_retake:
                hidePostCaptureAlert();
                restartPreview();
                break;
            case R.id.review_thumbnail:
                if (isCameraIdle()) {
                    viewLastImage();
                }
                break;
            case R.id.btn_done:
                doAttach();
                break;
            case R.id.btn_cancel:
                doCancel();
        }
    }

    private void doAttach() {
        if (mPausing) {
            return;
        }
        Bitmap bitmap = mImageCapture.getLastBitmap();

        String cropValue = null;
        Uri saveUri = null;

        Bundle myExtras = getIntent().getExtras();
        if (myExtras != null) {
            saveUri = (Uri) myExtras.getParcelable(MediaStore.EXTRA_OUTPUT);
            cropValue = myExtras.getString("crop");
        }


        if (cropValue == null) {
            // First handle the no crop case -- just return the value.  If the
            // caller specifies a "save uri" then write the data to it's
            // stream. Otherwise, pass back a scaled down version of the bitmap
            // directly in the extras.
            if (saveUri != null) {
                OutputStream outputStream = null;
                try {
                    outputStream = mContentResolver.openOutputStream(saveUri);
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75,
                            outputStream);
                    outputStream.close();

                    setResult(RESULT_OK);
                    finish();
                } catch (IOException ex) {
                    // ignore exception
                } finally {
                    if (outputStream != null) {
                        try {
                            outputStream.close();
                        } catch (IOException ex) {
                            // ignore exception
                        }
                    }
                }
            } else {
                float scale = .5F;
                Matrix m = new Matrix();
                m.setScale(scale, scale);

                bitmap = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(),
                        bitmap.getHeight(),
                        m, true);

                setResult(RESULT_OK,
                        new Intent("inline-data").putExtra("data", bitmap));
                finish();
            }
        } else {
            // Save the image to a temp file and invoke the cropper
            Uri tempUri = null;
            FileOutputStream tempStream = null;
            try {
                File path = getFileStreamPath(sTempCropFilename);
                path.delete();
                tempStream = openFileOutput(sTempCropFilename, 0);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, tempStream);
                tempStream.close();
                tempUri = Uri.fromFile(path);
            } catch (FileNotFoundException ex) {
                setResult(Activity.RESULT_CANCELED);
                finish();
                return;
            } catch (IOException ex) {
                setResult(Activity.RESULT_CANCELED);
                finish();
                return;
            } finally {
                if (tempStream != null) {
                    try {
                        tempStream.close();
                    } catch (IOException ex) {
                        // ignore exception
                    }
                }
            }

            Bundle newExtras = new Bundle();
            if (cropValue.equals("circle")) {
                newExtras.putString("circleCrop", "true");
            }
            if (saveUri != null) {
                newExtras.putParcelable(MediaStore.EXTRA_OUTPUT, saveUri);
            } else {
                newExtras.putBoolean("return-data", true);
            }

            Intent cropIntent = new Intent();
            cropIntent.setClass(this, CropImage.class);
            cropIntent.setData(tempUri);
            cropIntent.putExtras(newExtras);

            startActivityForResult(cropIntent, CROP_MSG);
        }
    }

    private void doCancel() {
        setResult(RESULT_CANCELED, new Intent());
        finish();
    }

    public void onShutterButtonFocus(ShutterButton button, boolean pressed) {
        if (mPausing) {
            return;
        }
        switch (button.getId()) {
            case R.id.shutter_button:
                doFocus(pressed);
                break;
        }
    }

    public void onShutterButtonClick(ShutterButton button) {
        if (mPausing) {
            return;
        }
        switch (button.getId()) {
            case R.id.shutter_button:
                doSnap();
                break;
        }
    }

    private OnScreenHint mStorageHint;

    private void updateStorageHint(int remaining) {
        String noStorageText = null;

        if (remaining == MenuHelper.NO_STORAGE_ERROR) {
            String state = Environment.getExternalStorageState();
            if (state == Environment.MEDIA_CHECKING ||
                    ImageManager.isMediaScannerScanning(getContentResolver())) {
                noStorageText = getString(R.string.preparing_sd);
            } else {
                noStorageText = getString(R.string.no_storage);
            }
        } else if (remaining < 1) {
            noStorageText = getString(R.string.not_enough_space);
        }

        if (noStorageText != null) {
            if (mStorageHint == null) {
                mStorageHint = OnScreenHint.makeText(this, noStorageText);
            } else {
                mStorageHint.setText(noStorageText);
            }
            mStorageHint.show();
        } else if (mStorageHint != null) {
            mStorageHint.cancel();
            mStorageHint = null;
        }
    }

    private void installIntentFilter() {
        // install an intent filter to receive SD card related events.
        IntentFilter intentFilter =
                new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        intentFilter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        intentFilter.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
        intentFilter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        intentFilter.addAction(Intent.ACTION_MEDIA_CHECKING);
        intentFilter.addDataScheme("file");
        registerReceiver(mReceiver, intentFilter);
        mDidRegister = true;
    }

    private void initializeFocusTone() {
        // Initialize focus tone generator.
        try {
            mFocusToneGenerator = new ToneGenerator(
                    AudioManager.STREAM_SYSTEM, FOCUS_BEEP_VOLUME);
        } catch (Throwable ex) {
            Log.w(TAG, "Exception caught while creating tone generator: ", ex);
            mFocusToneGenerator = null;
        }
    }

    private void readPreference() {
        mRecordLocation = mPreferences.getBoolean(
                "pref_camera_recordlocation_key", false);
        mFocusMode = mPreferences.getString(
                CameraSettings.KEY_FOCUS_MODE,
                getString(R.string.pref_camera_focusmode_default));
    }

    @Override
    public void onResume() {
        super.onResume();

        mPausing = false;
        mJpegPictureCallbackTime = 0;
        mImageCapture = new ImageCapture();

        // Start the preview if it is not started.
        if (!mPreviewing && !mStartPreviewFail) {
            try {
                startPreview();
            } catch (CameraHardwareException e) {
                showCameraErrorAndFinish();
                return;
            }
        }

        if (mSurfaceHolder != null) {
            // If first time initialization is not finished, put it in the
            // message queue.
            if (!mFirstTimeInitialized) {
                mHandler.sendEmptyMessage(FIRST_TIME_INIT);
            } else {
                initializeSecondTime();
            }
        }
        keepScreenOnAwhile();
    }

    private static ImageManager.DataLocation dataLocation() {
        return ImageManager.DataLocation.EXTERNAL;
    }

    @Override
    protected void onPause() {
        mPausing = true;
        stopPreview();
        // Close the camera now because other activities may need to use it.
        closeCamera();
        resetScreenOn();

        if (mSettings != null && mSettings.isVisible()) {
            mSettings.setVisible(false);
        }

        if (mFirstTimeInitialized) {
            mOrientationListener.disable();
            mGpsIndicator.setVisibility(View.INVISIBLE);
            if (!mIsImageCaptureIntent) {
                mThumbController.storeData(
                        ImageManager.getLastImageThumbPath());
            }
            hidePostCaptureAlert();
        }

        if (mDidRegister) {
            unregisterReceiver(mReceiver);
            mDidRegister = false;
        }
        stopReceivingLocationUpdates();

        if (mFocusToneGenerator != null) {
            mFocusToneGenerator.release();
            mFocusToneGenerator = null;
        }

        if (mStorageHint != null) {
            mStorageHint.cancel();
            mStorageHint = null;
        }

        // If we are in an image capture intent and has taken
        // a picture, we just clear it in onPause.
        mImageCapture.clearLastBitmap();
        mImageCapture = null;

        // This is necessary to make the ZoomButtonsController unregister
        // its configuration change receiver.
        if (mZoomButtons != null) {
            mZoomButtons.setVisible(false);
        }

        // Remove the messages in the event queue.
        mHandler.removeMessages(RESTART_PREVIEW);
        mHandler.removeMessages(FIRST_TIME_INIT);

        super.onPause();
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case CROP_MSG: {
                Intent intent = new Intent();
                if (data != null) {
                    Bundle extras = data.getExtras();
                    if (extras != null) {
                        intent.putExtras(extras);
                    }
                }
                setResult(resultCode, intent);
                finish();

                File path = getFileStreamPath(sTempCropFilename);
                path.delete();

                break;
            }
        }
    }

    private boolean canTakePicture() {
        return isCameraIdle() && mPreviewing && (mPicturesRemaining > 0);
    }

    private void autoFocus() {
        // Initiate autofocus only when preview is started and snapshot is not
        // in progress.
        if (canTakePicture()) {
            Log.v(TAG, "Start autofocus.");
            if (mZoomButtons != null) mZoomButtons.setVisible(false);
            mFocusStartTime = System.currentTimeMillis();
            mFocusState = FOCUSING;
            updateFocusIndicator();
            mCameraDevice.autoFocus(mAutoFocusCallback);
        }
    }

    private void cancelAutoFocus() {
        // User releases half-pressed focus key.
        if (mFocusState == FOCUSING || mFocusState == FOCUS_SUCCESS
                || mFocusState == FOCUS_FAIL) {
            Log.v(TAG, "Cancel autofocus.");
            mCameraDevice.cancelAutoFocus();
        }
        if (mFocusState != FOCUSING_SNAP_ON_FINISH) {
            clearFocusState();
        }
    }

    private void clearFocusState() {
        mFocusState = FOCUS_NOT_STARTED;
        updateFocusIndicator();
    }

    private void updateFocusIndicator() {
        if (mFocusRectangle == null) return;

        if (mFocusState == FOCUSING || mFocusState == FOCUSING_SNAP_ON_FINISH) {
            mFocusRectangle.showStart();
        } else if (mFocusState == FOCUS_SUCCESS) {
            mFocusRectangle.showSuccess();
        } else if (mFocusState == FOCUS_FAIL) {
            mFocusRectangle.showFail();
        } else {
            mFocusRectangle.clear();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (!isCameraIdle()) {
                    // ignore backs while we're taking a picture
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_FOCUS:
                if (mFirstTimeInitialized && event.getRepeatCount() == 0) {
                    doFocus(true);
                }
                return true;
            case KeyEvent.KEYCODE_CAMERA:
                if (mFirstTimeInitialized && event.getRepeatCount() == 0) {
                    doSnap();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                // If we get a dpad center event without any focused view, move
                // the focus to the shutter button and press it.
                if (mFirstTimeInitialized && event.getRepeatCount() == 0) {
                    // Start auto-focus immediately to reduce shutter lag. After
                    // the shutter button gets the focus, doFocus() will be
                    // called again but it is fine.
                    doFocus(true);
                    if (mShutterButton.isInTouchMode()) {
                        mShutterButton.requestFocusFromTouch();
                    } else {
                        mShutterButton.requestFocus();
                    }
                    mShutterButton.setPressed(true);
                }
                return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_FOCUS:
                if (mFirstTimeInitialized) {
                    doFocus(false);
                }
                return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void doSnap() {
        // If the user has half-pressed the shutter and focus is completed, we
        // can take the photo right away. If the focus mode is infinity, we can
        // also take the photo.
        if (mFocusMode.equals(getString(
                R.string.pref_camera_focusmode_value_infinity))
                || (mFocusState == FOCUS_SUCCESS
                || mFocusState == FOCUS_FAIL)) {
            if (mZoomButtons != null) mZoomButtons.setVisible(false);
            mImageCapture.onSnap();
        } else if (mFocusState == FOCUSING) {
            // Half pressing the shutter (i.e. the focus button event) will
            // already have requested AF for us, so just request capture on
            // focus here.
            mFocusState = FOCUSING_SNAP_ON_FINISH;
        } else if (mFocusState == FOCUS_NOT_STARTED) {
            // Focus key down event is dropped for some reasons. Just ignore.
        }
    }

    private void doFocus(boolean pressed) {
        // Do the focus if the mode is auto. No focus needed in infinity mode.
        if (mFocusMode.equals(getString(
                R.string.pref_camera_focusmode_value_auto))) {
            if (pressed) {  // Focus key down.
                autoFocus();
            } else {  // Focus key up.
                cancelAutoFocus();
            }
        }
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        // Make sure we have a surface in the holder before proceeding.
        if (holder.getSurface() == null) {
            Log.d(TAG, "holder.getSurface() == null");
            return;
        }

        // The mCameraDevice will be null if it fails to connect to the camera
        // hardware. In this case we will show a dialog and then finish the
        // activity, so it's OK to ignore it.
        if (mCameraDevice == null) return;

        mSurfaceHolder = holder;
        mViewFinderWidth = w;
        mViewFinderHeight = h;

        // Sometimes surfaceChanged is called after onPause. Ignore it.
        if (mPausing || isFinishing()) return;

        // Set preview display if the surface is being created. Preview was
        // already started.
        if (holder.isCreating()) {
            setPreviewDisplay(holder);
        }

        // If first time initialization is not finished, send a message to do
        // it later. We want to finish surfaceChanged as soon as possible to let
        // user see preview first.
        if (!mFirstTimeInitialized) {
            mHandler.sendEmptyMessage(FIRST_TIME_INIT);
        } else {
            initializeSecondTime();
        }
    }

    public void surfaceCreated(SurfaceHolder holder) {
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        stopPreview();
        mSurfaceHolder = null;
    }

    private void closeCamera() {
        if (mCameraDevice != null) {
            CameraHolder.instance().release();
            mCameraDevice = null;
            mPreviewing = false;
        }
    }

    private void ensureCameraDevice() throws CameraHardwareException {
        if (mCameraDevice == null) {
            mCameraDevice = CameraHolder.instance().open();
        }
    }

    private void updateLastImage() {
        IImageList list = ImageManager.makeImageList(
            mContentResolver,
            dataLocation(),
            ImageManager.INCLUDE_IMAGES,
            ImageManager.SORT_ASCENDING,
            ImageManager.CAMERA_IMAGE_BUCKET_ID);
        int count = list.getCount();
        if (count > 0) {
            IImage image = list.getImageAt(count - 1);
            Uri uri = image.fullSizeImageUri();
            mThumbController.setData(uri, image.miniThumbBitmap());
        } else {
            mThumbController.setData(null, null);
        }
        list.close();
    }

    private void showCameraErrorAndFinish() {
        Resources ress = getResources();
        Util.showFatalErrorAndFinish(Camera.this,
                ress.getString(R.string.camera_error_title),
                ress.getString(R.string.cannot_connect_camera));
    }

    private void restartPreview() {
        // make sure the surfaceview fills the whole screen when previewing
        mSurfaceView.setAspectRatio(VideoPreview.DONT_CARE);
        try {
            startPreview();
        } catch (CameraHardwareException e) {
            showCameraErrorAndFinish();
            return;
        }

        // Calculate this in advance of each shot so we don't add to shutter
        // latency. It's true that someone else could write to the SD card in
        // the mean time and fill it, but that could have happened between the
        // shutter press and saving the JPEG too.
        calculatePicturesRemaining();
    }

    private void setPreviewDisplay(SurfaceHolder holder) {
        try {
            mCameraDevice.setPreviewDisplay(holder);
        } catch (Throwable ex) {
            closeCamera();
            throw new RuntimeException("setPreviewDisplay failed", ex);
        }
    }

    private void startPreview() throws CameraHardwareException {
        if (mPausing || isFinishing()) return;

        ensureCameraDevice();

        // If we're previewing already, stop the preview first (this will blank
        // the screen).
        if (mPreviewing) stopPreview();

        setPreviewDisplay(mSurfaceHolder);

        setCameraParameter();

        final long wallTimeStart = SystemClock.elapsedRealtime();
        final long threadTimeStart = Debug.threadCpuTimeNanos();

        // Set one shot preview callback for latency measurement.
        mCameraDevice.setOneShotPreviewCallback(mOneShotPreviewCallback);
        mCameraDevice.setErrorCallback(mErrorCallback);

        try {
            Log.v(TAG, "startPreview");
            mCameraDevice.startPreview();
        } catch (Throwable ex) {
            closeCamera();
            throw new RuntimeException("startPreview failed", ex);
        }
        mPreviewing = true;
        mStatus = IDLE;

        long threadTimeEnd = Debug.threadCpuTimeNanos();
        long wallTimeEnd = SystemClock.elapsedRealtime();
        if ((wallTimeEnd - wallTimeStart) > 3000) {
            Log.w(TAG, "startPreview() to " + (wallTimeEnd - wallTimeStart)
                    + " ms. Thread time was"
                    + (threadTimeEnd - threadTimeStart) / 1000000 + " ms.");
        }
    }

    private void stopPreview() {
        if (mCameraDevice != null && mPreviewing) {
            Log.v(TAG, "stopPreview");
            mCameraDevice.stopPreview();
        }
        mPreviewing = false;
        // If auto focus was in progress, it would have been canceled.
        clearFocusState();
    }

    private void resizeForPreviewAspectRatio(View v) {
        ViewGroup.LayoutParams params;
        params = v.getLayoutParams();
        Size size = mParameters.getPreviewSize();
        params.width = (int) (params.height * size.width / size.height);
        Log.v(TAG, "resize to " + params.width + "x" + params.height);
        v.setLayoutParams(params);
    }

    private Size getOptimalPreviewSize(List<Size> sizes) {
        Size optimalSize = null;
        if (sizes != null) {
            optimalSize = sizes.get(0);
            for (int i = 1; i < sizes.size(); i++) {
                if (Math.abs(sizes.get(i).height - mViewFinderHeight) <
                        Math.abs(optimalSize.height - mViewFinderHeight)) {
                    optimalSize = sizes.get(i);
                }
            }
            Log.v(TAG, "Optimal preview size is " + optimalSize.width + "x"
                    + optimalSize.height);
        }
        return optimalSize;
    }

    private void setCameraParameter() {
        mParameters = mCameraDevice.getParameters();

        // Reset preview frame rate to the maximum because it may be lowered by
        // video camera application.
        List<Integer> frameRates = mParameters.getSupportedPreviewFrameRates();
        if (frameRates != null) {
            Integer max = Collections.max(frameRates);
            mParameters.setPreviewFrameRate(max);
        }

        // Set a preview size that is closest to the viewfinder height.
        List<Size> sizes = mParameters.getSupportedPreviewSizes();
        Size optimalSize = getOptimalPreviewSize(sizes);
        if (optimalSize != null) {
            mParameters.setPreviewSize(optimalSize.width, optimalSize.height);
        }

        // Set picture size.
        String pictureSize = mPreferences.getString(
                CameraSettings.KEY_PICTURE_SIZE,
                getString(R.string.pref_camera_picturesize_default));
        setCameraPictureSizeIfSupported(pictureSize);

        // Set JPEG quality.
        String jpegQuality = mPreferences.getString(
                CameraSettings.KEY_JPEG_QUALITY,
                getString(R.string.pref_camera_jpegquality_default));
        mParameters.setJpegQuality(Integer.parseInt(jpegQuality));

        // Set flash mode.
        if (mParameters.getSupportedFlashModes() != null) {
            String flashMode = mPreferences.getString(
                    CameraSettings.KEY_FLASH_MODE, "auto");
            mParameters.setFlashMode(flashMode);
        }

        // Set white balance parameter.
        if (mParameters.getSupportedWhiteBalance() != null) {
            String whiteBalance = mPreferences.getString(
                    CameraSettings.KEY_WHITE_BALANCE,
                    getString(R.string.pref_camera_whitebalance_default));
            mParameters.setWhiteBalance(whiteBalance);
        }

        // Set color effect parameter.
        if (mParameters.getSupportedColorEffects() != null) {
            String colorEffect = mPreferences.getString(
                    CameraSettings.KEY_COLOR_EFFECT,
                    getString(R.string.pref_camera_coloreffect_default));
            mParameters.setColorEffect(colorEffect);
        }

        mCameraDevice.setParameters(mParameters);
    }

    private void gotoGallery() {
        MenuHelper.gotoCameraImageGallery(this);
    }

    private void viewLastImage() {
        if (mThumbController.isUriValid()) {
            Uri targetUri = mThumbController.getUri();
            targetUri = targetUri.buildUpon().appendQueryParameter(
                    "bucketId", ImageManager.CAMERA_IMAGE_BUCKET_ID).build();
            Intent intent = new Intent(this, ReviewImage.class);
            intent.setData(targetUri);
            intent.putExtra(MediaStore.EXTRA_FULL_SCREEN, true);
            intent.putExtra(MediaStore.EXTRA_SHOW_ACTION_ICONS, true);
            intent.putExtra("com.android.camera.ReviewMode", true);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException ex) {
                Log.e(TAG, "review image fail", ex);
            }
        } else {
            Log.e(TAG, "Can't view last image.");
        }
    }

    private void startReceivingLocationUpdates() {
        if (mLocationManager != null) {
            try {
                mLocationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000,
                        0F,
                        mLocationListeners[1]);
            } catch (java.lang.SecurityException ex) {
                Log.i(TAG, "fail to request location update, ignore", ex);
            } catch (IllegalArgumentException ex) {
                Log.d(TAG, "provider does not exist " + ex.getMessage());
            }
            try {
                mLocationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000,
                        0F,
                        mLocationListeners[0]);
            } catch (java.lang.SecurityException ex) {
                Log.i(TAG, "fail to request location update, ignore", ex);
            } catch (IllegalArgumentException ex) {
                Log.d(TAG, "provider does not exist " + ex.getMessage());
            }
        }
    }

    private void stopReceivingLocationUpdates() {
        if (mLocationManager != null) {
            for (int i = 0; i < mLocationListeners.length; i++) {
                try {
                    mLocationManager.removeUpdates(mLocationListeners[i]);
                } catch (Exception ex) {
                    Log.i(TAG, "fail to remove location listners, ignore", ex);
                }
            }
        }
    }

    private Location getCurrentLocation() {
        // go in best to worst order
        for (int i = 0; i < mLocationListeners.length; i++) {
            Location l = mLocationListeners[i].current();
            if (l != null) return l;
        }
        return null;
    }

    private boolean isCameraIdle() {
        return mStatus == IDLE && mFocusState == FOCUS_NOT_STARTED;
    }

    private boolean isImageCaptureIntent() {
        String action = getIntent().getAction();
        return (MediaStore.ACTION_IMAGE_CAPTURE.equals(action));
    }

    private void showPostCaptureAlert() {
        if (mIsImageCaptureIntent) {
            findViewById(R.id.shutter_button).setVisibility(View.INVISIBLE);
            int[] pickIds = {R.id.btn_retake, R.id.btn_done};
            for (int id : pickIds) {
                View button = findViewById(id);
                ((View) button.getParent()).setVisibility(View.VISIBLE);
            }
        }
    }

    private void hidePostCaptureAlert() {
        if (mIsImageCaptureIntent) {
            findViewById(R.id.shutter_button).setVisibility(View.VISIBLE);
            int[] pickIds = {R.id.btn_retake, R.id.btn_done};
            for (int id : pickIds) {
                View button = findViewById(id);
                ((View) button.getParent()).setVisibility(View.GONE);
            }
        }
    }

    private int calculatePicturesRemaining() {
        mPicturesRemaining = MenuHelper.calculatePicturesRemaining();
        return mPicturesRemaining;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        // Only show the menu when camera is idle.
        for (int i = 0; i < menu.size(); i++) {
            menu.getItem(i).setVisible(isCameraIdle());
        }

        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        if (mIsImageCaptureIntent) {
            // No options menu for attach mode.
            return false;
        } else {
            addBaseMenuItems(menu);
        }
        return true;
    }

    private void addBaseMenuItems(Menu menu) {
        MenuItem gallery = menu.add(Menu.NONE, Menu.NONE,
                MenuHelper.POSITION_GOTO_GALLERY,
                R.string.camera_gallery_photos_text)
                .setOnMenuItemClickListener(new OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                gotoGallery();
                return true;
            }
        });
        gallery.setIcon(android.R.drawable.ic_menu_gallery);
        mGalleryItems.add(gallery);

        MenuItem item = menu.add(Menu.NONE, Menu.NONE,
                MenuHelper.POSITION_CAMERA_SETTING, R.string.settings)
                .setOnMenuItemClickListener(new OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                showOnScreenSettings();
                return true;
            }
        });
        item.setIcon(android.R.drawable.ic_menu_preferences);
    }

    public boolean onSwitchChanged(Switcher source, boolean onOff) {
        if (onOff == SWITCH_VIDEO) {
            if (!isCameraIdle()) return false;
            MenuHelper.gotoVideoMode(this);
            finish();
        }
        return true;
    }

    private void setCameraPictureSizeIfSupported(String sizeString) {
        List<Size> pictureSizes = mParameters.getSupportedPictureSizes();
        if (pictureSizes != null) {
            int index = sizeString.indexOf('x');
            int width = Integer.parseInt(sizeString.substring(0, index));
            int height = Integer.parseInt(sizeString.substring(index + 1));
            for (Size size: pictureSizes) {
                if (size.width == width && size.height == height) {
                    mParameters.setPictureSize(width, height);
                    break;
                }
            }
        }
    }

    public void onSharedPreferenceChanged(
            SharedPreferences preferences, String key) {
        // ignore the events after "onPause()"
        if (mPausing) return;

        if (CameraSettings.KEY_FLASH_MODE.equals(key)) {
            mParameters.setFlashMode(preferences.getString(key, "auto"));
            mCameraDevice.setParameters(mParameters);
        } else if (CameraSettings.KEY_FOCUS_MODE.equals(key)) {
            mFocusMode = preferences.getString(key,
                    getString(R.string.pref_camera_focusmode_default));
        } else if (CameraSettings.KEY_PICTURE_SIZE.equals(key)) {
            String pictureSize = preferences.getString(key,
                    getString(R.string.pref_camera_picturesize_default));
            setCameraPictureSizeIfSupported(pictureSize);
            mCameraDevice.setParameters(mParameters);
        } else if (CameraSettings.KEY_JPEG_QUALITY.equals(key)) {
            String jpegQuality = preferences.getString(key,
                    getString(R.string.pref_camera_jpegquality_default));
            mParameters.setJpegQuality(Integer.parseInt(jpegQuality));
            mCameraDevice.setParameters(mParameters);
        } else if (CameraSettings.KEY_RECORD_LOCATION.equals(key)) {
            mRecordLocation = preferences.getBoolean(key, false);
            if (mRecordLocation) {
                startReceivingLocationUpdates();
            } else {
                stopReceivingLocationUpdates();
            }
        } else if (CameraSettings.KEY_COLOR_EFFECT.equals(key)) {
            String colorEffect = preferences.getString(key,
                    getString(R.string.pref_camera_coloreffect_default));
            mParameters.setColorEffect(colorEffect);
            mCameraDevice.setParameters(mParameters);
        } else if (CameraSettings.KEY_WHITE_BALANCE.equals(key)) {
            String whiteBalance = preferences.getString(key,
                    getString(R.string.pref_camera_whitebalance_default));
            mParameters.setWhiteBalance(whiteBalance);
            mCameraDevice.setParameters(mParameters);
        } else if (CameraSettings.KEY_SCENE_MODE.equals(key)) {
            String sceneMode = preferences.getString(key,
                    getString(R.string.pref_camera_scenemode_default));
            mParameters.setSceneMode(sceneMode);
            mCameraDevice.setParameters(mParameters);
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        keepScreenOnAwhile();
    }

    private void resetScreenOn() {
        mHandler.removeMessages(CLEAR_SCREEN_DELAY);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void keepScreenOnAwhile() {
        mHandler.removeMessages(CLEAR_SCREEN_DELAY);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mHandler.sendEmptyMessageDelayed(CLEAR_SCREEN_DELAY, SCREEN_DELAY);
    }
}

class FocusRectangle extends View {

    @SuppressWarnings("unused")
    private static final String TAG = "FocusRectangle";

    public FocusRectangle(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private void setDrawable(int resid) {
        setBackgroundDrawable(getResources().getDrawable(resid));
    }

    public void showStart() {
        setDrawable(R.drawable.focus_focusing);
    }

    public void showSuccess() {
        setDrawable(R.drawable.focus_focused);
    }

    public void showFail() {
        setDrawable(R.drawable.focus_focus_failed);
    }

    public void clear() {
        setBackgroundDrawable(null);
    }
}
