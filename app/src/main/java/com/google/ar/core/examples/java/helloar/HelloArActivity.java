/*
 * Copyright 2017 Google LLC
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

package com.google.ar.core.examples.java.helloar;
import com.google.ar.core.Coordinates2d;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.media.Image;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.media.AudioAttributes;
import java.util.Locale;
import android.util.Log;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import ai.onnxruntime.*;
import java.nio.ByteOrder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.nio.FloatBuffer;
import java.util.Collections;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.ArCoreApk.Availability;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Config.InstantPlacementMode;
import com.google.ar.core.DepthPoint;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.InstantPlacementPoint;
import com.google.ar.core.LightEstimate;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DepthSettings;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.InstantPlacementSettings;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TapHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.samplerender.Framebuffer;
import com.google.ar.core.examples.java.common.samplerender.GLError;
import com.google.ar.core.examples.java.common.samplerender.Mesh;
import com.google.ar.core.examples.java.common.samplerender.SampleRender;
import com.google.ar.core.examples.java.common.samplerender.Shader;
import com.google.ar.core.examples.java.common.samplerender.Texture;
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer;
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.SpecularCubemapFilter;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import android.graphics.Bitmap;
import java.util.List;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3D model.
 */
public class HelloArActivity extends AppCompatActivity implements SampleRender.Renderer {

  private static final String TAG = HelloArActivity.class.getSimpleName();

  private static final String SEARCHING_PLANE_MESSAGE = "Searching for surfaces...";
  private static final String WAITING_FOR_TAP_MESSAGE = "Tap on a surface to place an object.";

  // See the definition of updateSphericalHarmonicsCoefficients for an explanation of these
  // constants.
  private static final float[] sphericalHarmonicFactors = {
    0.282095f,
    -0.325735f,
    0.325735f,
    -0.325735f,
    0.273137f,
    -0.273137f,
    0.078848f,
    -0.273137f,
    0.136569f,
  };

  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 100f;

  private static final int CUBEMAP_RESOLUTION = 16;
  private static final int CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32;

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;

  private boolean installRequested;
  private boolean loggedCam = false;
  private int frameCount = 0;
  private boolean dumpedFrame = false;
  private final float[] cpuCoords = new float[2];
  private final float[] texCoords = new float[2];
  private final int[] samples = new int[5];
  private Session session;
  // Workaround for ARCore 1.54 regression (issue #1762): the Samsung sensor HAL routes
  // EnableSensor through queueBatch when uncalibrated sensors aren't already streaming,
  // which ARCore's standard_event_provider cannot handle. Holding the sensors open with a
  // no-op listener keeps the HAL in continuous mode so ARCore's registration succeeds.
  private SensorManager sensorManager;
  private final SensorEventListener noOpSensorListener =
          new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
              if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) {
                gyroMag = Math.abs(e.values[0]) + Math.abs(e.values[1]) + Math.abs(e.values[2]);
              }
            }
            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
          };

  private void holdUncalibratedSensorsOpen() {
    if (sensorManager == null) {
      sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    }
    Sensor gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED);
    Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED);
    if (gyro != null) {
      sensorManager.registerListener(
              noOpSensorListener, gyro, SensorManager.SENSOR_DELAY_FASTEST);
    }
    if (accel != null) {
      sensorManager.registerListener(
              noOpSensorListener, accel, SensorManager.SENSOR_DELAY_FASTEST);
    }
    Log.i(TAG, "Uncalibrated sensor hold: gyro=" + (gyro != null) + " accel=" + (accel != null));
  }
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
  private TapHelper tapHelper;
  private SampleRender render;

  private PlaneRenderer planeRenderer;
  private BackgroundRenderer backgroundRenderer;
  private Framebuffer virtualSceneFramebuffer;
  private boolean hasSetTextureNames = false;

  private final DepthSettings depthSettings = new DepthSettings();
  private boolean[] depthSettingsMenuDialogCheckboxes = new boolean[2];

  private final InstantPlacementSettings instantPlacementSettings = new InstantPlacementSettings();
  private boolean[] instantPlacementSettingsMenuDialogCheckboxes = new boolean[1];
  // Assumed distance from the device camera to the surface on which user will try to place objects.
  // This value affects the apparent scale of objects while the tracking method of the
  // Instant Placement point is SCREENSPACE_WITH_APPROXIMATE_DISTANCE.
  // Values in the [0.2, 2.0] meter range are a good choice for most AR experiences. Use lower
  // values for AR experiences where users are expected to place objects on surfaces close to the
  // camera. Use larger values for experiences where the user will likely be standing and trying to
  // place an object on the ground or floor in front of them.
  private static final float APPROXIMATE_DISTANCE_METERS = 2.0f;

  // Point Cloud
  private VertexBuffer pointCloudVertexBuffer;
  private Mesh pointCloudMesh;
  private Shader pointCloudShader;
  // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
  // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
  private long lastPointCloudTimestamp = 0;
  private volatile boolean detectRequested = false;
  // Virtual object (ARCore pawn)
  private Mesh virtualObjectMesh;
  private Shader virtualObjectShader;
  private Texture virtualObjectAlbedoTexture;
  private Texture virtualObjectAlbedoInstantPlacementTexture;

  private final List<WrappedAnchor> wrappedAnchors = new ArrayList<>();

  // Environmental HDR
  private Texture dfgTexture;
  private SpecularCubemapFilter cubemapFilter;

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] modelMatrix = new float[16];
  private final float[] viewMatrix = new float[16];
  private final float[] projectionMatrix = new float[16];
  private final float[] modelViewMatrix = new float[16]; // view x model
  private final float[] modelViewProjectionMatrix = new float[16]; // projection x view x model
  private final float[] sphericalHarmonicsCoefficients = new float[9 * 3];
  private final float[] viewInverseMatrix = new float[16];
  private final float[] worldLightDirection = {0.0f, 0.0f, 0.0f, 0.0f};
  private final float[] viewLightDirection = new float[4]; // view x world light direction

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(/* context= */ this);

    // Set up touch listener.
    surfaceView.setClickable(true);
    surfaceView.setContentDescription("Scan for objects");
    surfaceView.setOnClickListener(v -> {
      detectRequested = true;
      Log.i(TAG, "TAP requested");
    });
    // Set up renderer.
    render = new SampleRender(surfaceView, this, getAssets());

    installRequested = false;

    depthSettings.onCreate(this);
    instantPlacementSettings.onCreate(this);
    ImageButton settingsButton = findViewById(R.id.settings_button);
    settingsButton.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View v) {
            PopupMenu popup = new PopupMenu(HelloArActivity.this, v);
            popup.setOnMenuItemClickListener(HelloArActivity.this::settingsMenuClick);
            popup.inflate(R.menu.settings_menu);
            popup.show();
          }
        });
    settingsButton.setContentDescription("Settings");
    armLen = getPreferences(MODE_PRIVATE).getFloat("armLen", 0.65f);
    tts = new TextToSpeech(this, status -> {
      if (status != TextToSpeech.SUCCESS) {
        Log.e(TAG, "TTS init failed: " + status);
        return;
      }
      tts.setLanguage(Locale.US);
      tts.setAudioAttributes(new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build());
      ttsReady = true;
      Log.i(TAG, "TTS ready");
    });
  }

  /** Menu button to launch feature specific settings. */
  protected boolean settingsMenuClick(MenuItem item) {
    if (item.getItemId() == R.id.depth_settings) {
      launchDepthSettingsMenuDialog();
      return true;
    } else if (item.getItemId() == R.id.arm_length_settings) {
      launchArmLengthDialog();
      return true;
    } else if (item.getItemId() == R.id.instant_placement_settings) {
      launchInstantPlacementSettingsMenuDialog();
      return true;
    }
    return false;
  }

  @Override
  protected void onDestroy() {
    if (session != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      session.close();
      session = null;
    }
    if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();
    holdUncalibratedSensorsOpen();
    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        // Always check the latest availability.
        Availability availability = ArCoreApk.getInstance().checkAvailability(this);

        // In all other cases, try to install ARCore and handle installation failures.
        if (availability != Availability.SUPPORTED_INSTALLED) {
          switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
            case INSTALL_REQUESTED:
              installRequested = true;
              return;
            case INSTALLED:
              break;
          }
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // Create the session.
        session = new Session(/* context= */ this);
      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      configureSession();
      // To record a live camera session for later playback, call
      // `session.startRecording(recordingConfig)` at anytime. To playback a previously recorded AR
      // session instead of using the live camera feed, call
      // `session.setPlaybackDatasetUri(Uri)` before calling `session.resume()`. To
      // learn more about recording and playback, see:
      // https://developers.google.com/ar/develop/java/recording-and-playback
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      speak("Camera not available. Try restarting the app.");
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();
    try { initOrt(); } catch (Exception e) { Log.e(TAG, "ORT init failed", e); }

  }

  @Override
  public void onPause() {
    super.onPause();
    if (sensorManager != null) {
      sensorManager.unregisterListener(noOpSensorListener);
    }
    if (session != null) {
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(SampleRender render) {
    // Prepare the rendering objects. This involves reading shaders and 3D model files, so may throw
    // an IOException.
    try {
      planeRenderer = new PlaneRenderer(render);
      backgroundRenderer = new BackgroundRenderer(render);
      virtualSceneFramebuffer = new Framebuffer(render, /* width= */ 1, /* height= */ 1);

      cubemapFilter =
          new SpecularCubemapFilter(
              render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES);
      // Load DFG lookup table for environmental lighting
      dfgTexture =
          new Texture(
              render,
              Texture.Target.TEXTURE_2D,
              Texture.WrapMode.CLAMP_TO_EDGE,
              /* useMipmaps= */ false);
      // The dfg.raw file is a raw half-float texture with two channels.
      final int dfgResolution = 64;
      final int dfgChannels = 2;
      final int halfFloatSize = 2;

      ByteBuffer buffer =
          ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize);
      try (InputStream is = getAssets().open("models/dfg.raw")) {
        is.read(buffer.array());
      }
      // SampleRender abstraction leaks here.
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.getTextureId());
      GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture");
      GLES30.glTexImage2D(
          GLES30.GL_TEXTURE_2D,
          /* level= */ 0,
          GLES30.GL_RG16F,
          /* width= */ dfgResolution,
          /* height= */ dfgResolution,
          /* border= */ 0,
          GLES30.GL_RG,
          GLES30.GL_HALF_FLOAT,
          buffer);
      GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D");

      // Point cloud
      pointCloudShader =
          Shader.createFromAssets(
                  render,
                  "shaders/point_cloud.vert",
                  "shaders/point_cloud.frag",
                  /* defines= */ null)
              .setVec4(
                  "u_Color", new float[] {31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f})
              .setFloat("u_PointSize", 5.0f);
      // four entries per vertex: X, Y, Z, confidence
      pointCloudVertexBuffer =
          new VertexBuffer(render, /* numberOfEntriesPerVertex= */ 4, /* entries= */ null);
      final VertexBuffer[] pointCloudVertexBuffers = {pointCloudVertexBuffer};
      pointCloudMesh =
          new Mesh(
              render, Mesh.PrimitiveMode.POINTS, /* indexBuffer= */ null, pointCloudVertexBuffers);

      // Virtual object to render (ARCore pawn)
      virtualObjectAlbedoTexture =
          Texture.createFromAsset(
              render,
              "models/pawn_albedo.png",
              Texture.WrapMode.CLAMP_TO_EDGE,
              Texture.ColorFormat.SRGB);
      virtualObjectAlbedoInstantPlacementTexture =
          Texture.createFromAsset(
              render,
              "models/pawn_albedo_instant_placement.png",
              Texture.WrapMode.CLAMP_TO_EDGE,
              Texture.ColorFormat.SRGB);
      Texture virtualObjectPbrTexture =
          Texture.createFromAsset(
              render,
              "models/pawn_roughness_metallic_ao.png",
              Texture.WrapMode.CLAMP_TO_EDGE,
              Texture.ColorFormat.LINEAR);

      virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj");
      virtualObjectShader =
          Shader.createFromAssets(
                  render,
                  "shaders/environmental_hdr.vert",
                  "shaders/environmental_hdr.frag",
                  /* defines= */ new HashMap<String, String>() {
                    {
                      put(
                          "NUMBER_OF_MIPMAP_LEVELS",
                          Integer.toString(cubemapFilter.getNumberOfMipmapLevels()));
                    }
                  })
              .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
              .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
              .setTexture("u_Cubemap", cubemapFilter.getFilteredCubemapTexture())
              .setTexture("u_DfgTexture", dfgTexture);
    } catch (IOException e) {
      Log.e(TAG, "Failed to read a required asset file", e);
      messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
    }
  }

  @Override
  public void onSurfaceChanged(SampleRender render, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    virtualSceneFramebuffer.resize(width, height);
  }

  @Override
  public void onDrawFrame(SampleRender render) {

    if (session == null) {
      return;
    }

    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(
          new int[] {backgroundRenderer.getCameraColorTexture().getTextureId()});
      hasSetTextureNames = true;
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    // Obtain the current frame from the AR Session. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    Frame frame;
    try {
      frame = session.update();
    } catch (CameraNotAvailableException e) {
      Log.e(TAG, "Camera not available during onDrawFrame", e);
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      speak("Camera not available. Try restarting the app.");
      return;
    }
    Camera camera = frame.getCamera();

    if (detectRequested
            && camera.getTrackingState() == TrackingState.TRACKING
            && gyroMag < 0.8f) {
      detectRequested = false;
      try (Image img = frame.acquireCameraImage()) {
        long t0 = System.nanoTime();
        detect(frame, img);
        Log.i(TAG, "detect took " + (System.nanoTime() - t0) / 1_000_000 + "ms");
      } catch (Exception e) {
        Log.e(TAG, "detect failed", e);
      }
    }
    // Update BackgroundRenderer state to match the depth settings.
    try {
      backgroundRenderer.setUseDepthVisualization(
          render, depthSettings.depthColorVisualizationEnabled());
      backgroundRenderer.setUseOcclusion(render, depthSettings.useDepthForOcclusion());
    } catch (IOException e) {
      Log.e(TAG, "Failed to read a required asset file", e);
      messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
      return;
    }
    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame);

    if (camera.getTrackingState() == TrackingState.TRACKING
        && (depthSettings.useDepthForOcclusion()
            || depthSettings.depthColorVisualizationEnabled())) {
      try (Image depthImage = frame.acquireDepthImage16Bits()) {
        backgroundRenderer.updateCameraDepthTexture(depthImage);
      } catch (NotYetAvailableException e) {
        // This normally means that depth data is not available yet. This is normal so we will not
        // spam the logcat with this.
      }
    }


    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

    // Show a message based on whether tracking has failed, if planes are detected, and if the user
    // has placed any objects.
    // -- Draw background

    if (frame.getTimestamp() != 0) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render);
    }

    // If not tracking, don't draw 3D objects.
  }

  // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.


  /**
   * Shows a pop-up dialog on the first call, determining whether the user wants to enable
   * depth-based occlusion. The result of this dialog can be retrieved with useDepthForOcclusion().
   */

  private void launchInstantPlacementSettingsMenuDialog() {
    resetSettingsMenuDialogCheckboxes();
    Resources resources = getResources();
    new AlertDialog.Builder(this)
        .setTitle(R.string.options_title_instant_placement)
        .setMultiChoiceItems(
            resources.getStringArray(R.array.instant_placement_options_array),
            instantPlacementSettingsMenuDialogCheckboxes,
            (DialogInterface dialog, int which, boolean isChecked) ->
                instantPlacementSettingsMenuDialogCheckboxes[which] = isChecked)
        .setPositiveButton(
            R.string.done,
            (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
        .setNegativeButton(
            android.R.string.cancel,
            (DialogInterface dialog, int which) -> resetSettingsMenuDialogCheckboxes())
        .show();
  }
  private void launchArmLengthDialog() {
    final String[] opts = {"Short (55 cm)", "Average (65 cm)", "Long (75 cm)"};
    final float[] vals = {0.55f, 0.65f, 0.75f};
    new AlertDialog.Builder(this)
            .setTitle("Arm length")
            .setItems(opts, (d, which) -> {
              armLen = armLen = vals[which];
              getPreferences(MODE_PRIVATE).edit().putFloat("armLen", armLen).apply();
              speak("Arm length set to " + opts[which]);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
  }
  /** Shows checkboxes to the user to facilitate toggling of depth-based effects. */
  private void launchDepthSettingsMenuDialog() {
    // Retrieves the current settings to show in the checkboxes.
    resetSettingsMenuDialogCheckboxes();

    // Shows the dialog to the user.
    Resources resources = getResources();
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
      // With depth support, the user can select visualization options.
      new AlertDialog.Builder(this)
          .setTitle(R.string.options_title_with_depth)
          .setMultiChoiceItems(
              resources.getStringArray(R.array.depth_options_array),
              depthSettingsMenuDialogCheckboxes,
              (DialogInterface dialog, int which, boolean isChecked) ->
                  depthSettingsMenuDialogCheckboxes[which] = isChecked)
          .setPositiveButton(
              R.string.done,
              (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
          .setNegativeButton(
              android.R.string.cancel,
              (DialogInterface dialog, int which) -> resetSettingsMenuDialogCheckboxes())
          .show();
    } else {
      // Without depth support, no settings are available.
      new AlertDialog.Builder(this)
          .setTitle(R.string.options_title_without_depth)
          .setPositiveButton(
              R.string.done,
              (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
          .show();
    }
  }

  private void applySettingsMenuDialogCheckboxes() {
    depthSettings.setUseDepthForOcclusion(depthSettingsMenuDialogCheckboxes[0]);
    depthSettings.setDepthColorVisualizationEnabled(depthSettingsMenuDialogCheckboxes[1]);
    instantPlacementSettings.setInstantPlacementEnabled(
        instantPlacementSettingsMenuDialogCheckboxes[0]);
    configureSession();
  }

  private void resetSettingsMenuDialogCheckboxes() {
    depthSettingsMenuDialogCheckboxes[0] = depthSettings.useDepthForOcclusion();
    depthSettingsMenuDialogCheckboxes[1] = depthSettings.depthColorVisualizationEnabled();
    instantPlacementSettingsMenuDialogCheckboxes[0] =
        instantPlacementSettings.isInstantPlacementEnabled();
  }
  /** Configures the session with feature settings. */
  private void configureSession() {
    Config config = session.getConfig();
    config.setLightEstimationMode(Config.LightEstimationMode.ENVIRONMENTAL_HDR);
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
      config.setDepthMode(Config.DepthMode.AUTOMATIC);
    } else {
      config.setDepthMode(Config.DepthMode.DISABLED);
    }
    if (instantPlacementSettings.isInstantPlacementEnabled()) {
      config.setInstantPlacementMode(InstantPlacementMode.LOCAL_Y_UP);
    } else {
      config.setInstantPlacementMode(InstantPlacementMode.DISABLED);
    }
    session.configure(config);
  }
  private int[] argb;   // reused across frames
  // ---------- detection ----------
  static final String[] LABELS = {
          "pencil", "plate", "door", "credit card", "document", "stairs",
          "railing", "toothbrush", "bottle", "charging cable", "book",
          "chair", "table", "cup", "cash", "envelope", "wrapper"
  };
  private static final int SZ = 416;
  private static final float CONF = 0.25f;

  private OrtEnvironment ortEnv;
  private OrtSession ortSession;
  private String ortInputName;
  private boolean detectedOnce = false;
  private TextToSpeech tts;
  private volatile boolean ttsReady = false;
  private volatile float gyroMag = 0f;

  private float armLen;   // meters, adjustable later
  private int lastCls = -1;
  private int lastMm = 0;
  private long lastSpeakNs = 0;
  private static final float SPEAK_CONF = 0.40f;
  private static final int MAX_DET = 16;
  private final int[]   detCls  = new int[MAX_DET];
  private final float[] detConf = new float[MAX_DET];
  private final float[] detCx   = new float[MAX_DET];
  private final float[] detCy   = new float[MAX_DET];
  private final int[]   detMm   = new int[MAX_DET];
  private int detCount = 0;
  private boolean loggedShape = false;
  private FloatBuffer inputBuf;
  private Bitmap canvasBmp;
  private Canvas canvasCv;
  private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
  private final android.graphics.Matrix xform = new android.graphics.Matrix();
  private int[] pixels;

  private void initOrt() throws Exception {
    if (ortSession != null) return;
    ortEnv = OrtEnvironment.getEnvironment();
    byte[] bytes;
    try (InputStream is = getAssets().open("yoloe-26n-seg.onnx")) {
      bytes = new byte[is.available()];
      is.read(bytes);
    }
    ortSession = ortEnv.createSession(bytes, new OrtSession.SessionOptions());
    ortInputName = ortSession.getInputNames().iterator().next();
    Log.i(TAG, "ORT ready");
  }

  private void detect(Frame frame, Image img) throws Exception {
    if (ortSession == null) initOrt();

    final int sw = img.getWidth(), sh = img.getHeight();   // 640x480 landscape
    int[] src = yuvToArgb(img);
    Bitmap srcBmp = Bitmap.createBitmap(src, sw, sh, Bitmap.Config.ARGB_8888);

    // sensor image is 90 deg CCW from upright -> rotate 90 CW
    final int rot = 90;
    final int w0 = sh, h0 = sw;                            // dims after rotation

    float s = Math.min((float) SZ / h0, (float) SZ / w0);
    int nw = Math.round(w0 * s), nh = Math.round(h0 * s);
    int px = (SZ - nw) / 2, py = (SZ - nh) / 2;

    if (canvasBmp == null) {
      canvasBmp = Bitmap.createBitmap(SZ, SZ, Bitmap.Config.ARGB_8888);
      canvasCv = new Canvas(canvasBmp);
      pixels = new int[SZ * SZ];
      inputBuf = ByteBuffer.allocateDirect(3 * SZ * SZ * 4)
              .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }
    canvasCv.drawColor(Color.rgb(114, 114, 114));

    xform.reset();
    xform.postRotate(rot);
    xform.postTranslate(sh, 0);        // bring back into view after 90 CW
    xform.postScale(s, s);
    xform.postTranslate(px, py);
    canvasCv.drawBitmap(srcBmp, xform, paint);

    canvasBmp.getPixels(pixels, 0, SZ, 0, 0, SZ, SZ);
    final int plane = SZ * SZ;
    for (int i = 0; i < plane; i++) {
      int v = pixels[i];
      inputBuf.put(i,             ((v >> 16) & 0xFF) * (1f / 255f));
      inputBuf.put(i + plane,     ((v >>  8) & 0xFF) * (1f / 255f));
      inputBuf.put(i + 2 * plane, ( v        & 0xFF) * (1f / 255f));
    }
    inputBuf.rewind();

    Image depthImage = null;
    try {
      depthImage = frame.acquireDepthImage16Bits();
    } catch (NotYetAvailableException e) {
      // no depth this frame — label only
    }

    try (OnnxTensor t = OnnxTensor.createTensor(ortEnv, inputBuf, new long[]{1, 3, SZ, SZ});
         OrtSession.Result res = ortSession.run(Collections.singletonMap(ortInputName, t))) {

      OnnxTensor outT = (OnnxTensor) res.get(0);
      FloatBuffer buf = outT.getFloatBuffer();
      if (buf == null) { Log.e(TAG, "output not float-convertible"); return; }

      long[] shape = outT.getInfo().getShape();
      if (!loggedShape) {
        Log.i(TAG, "out shape " + java.util.Arrays.toString(shape));
        loggedShape = true;
      }
      final int n = (int) shape[1];
      final int stride = (int) shape[2];
      detCount = 0;
      for (int r = 0; r < n; r++) {
        int o = r * stride;
        float conf = buf.get(o + 4);
        if (conf < CONF) continue;
        int cls = (int) buf.get(o + 5);
        if (cls < 0 || cls >= LABELS.length) continue;

        float x1 = (buf.get(o)     - px) / s;
        float y1 = (buf.get(o + 1) - py) / s;
        float x2 = (buf.get(o + 2) - px) / s;
        float y2 = (buf.get(o + 3) - py) / s;
        float cx = (x1 + x2) * 0.5f, cy = (y1 + y2) * 0.5f;
        float qw = (x2 - x1) * 0.25f, qh = (y2 - y1) * 0.25f;

        int mm = -1;
        if (depthImage != null) {
          int k = 0, v;
          if ((v = depthAt(frame, depthImage, cx,      cy,      sh)) > 0) samples[k++] = v;
          if ((v = depthAt(frame, depthImage, cx - qw, cy,      sh)) > 0) samples[k++] = v;
          if ((v = depthAt(frame, depthImage, cx + qw, cy,      sh)) > 0) samples[k++] = v;
          if ((v = depthAt(frame, depthImage, cx,      cy - qh, sh)) > 0) samples[k++] = v;
          if ((v = depthAt(frame, depthImage, cx,      cy + qh, sh)) > 0) samples[k++] = v;
          if (k > 0) {
            java.util.Arrays.sort(samples, 0, k);
            mm = samples[k / 2];
          }
        }
        if (detCount < MAX_DET) {
          detCls[detCount]  = cls;
          detConf[detCount] = conf;
          detCx[detCount]   = cx;
          detCy[detCount]   = cy;
          detMm[detCount]   = mm;
          detCount++;
        }
      }
      int best = -1;
      float bestScore = Float.MAX_VALUE;
      for (int i = 0; i < detCount; i++) {
        float dx = detCx[i] - 240f, dy = detCy[i] - 320f;
        float d = dx * dx + dy * dy;
        if (d < bestScore) { bestScore = d; best = i; }
      }

      // most-centred wins, but only if it's confident enough to speak;
      // otherwise fall back to the most confident detection anywhere in frame
      if (best >= 0 && detConf[best] < SPEAK_CONF) {
        int alt = -1;
        for (int i = 0; i < detCount; i++) {
          if (detConf[i] >= SPEAK_CONF && (alt < 0 || detConf[i] > detConf[alt])) alt = i;
        }
        best = alt;
      }
      if (best >= 0) {
        // drop a lower-confidence duplicate sitting on the same object
        for (int i = 0; i < detCount; i++) {
          if (i == best) continue;
          if (Math.abs(detCx[i] - detCx[best]) < 40
                  && Math.abs(detCy[i] - detCy[best]) < 40
                  && detConf[i] > detConf[best]) {
            best = i;
          }
        }
        String phrase = LABELS[detCls[best]]
                + ", " + distancePhrase(detMm[best])
                + ", " + bearingPhrase(detCx[best]);
        Log.i(TAG, "SAY " + phrase);
        speak(phrase);
      } else {
        Log.i(TAG, "SAY nothing found");
        speak("nothing found");
      }
    } finally {
      if (depthImage != null) depthImage.close();
    }
  }
  private int[] yuvToArgb(Image img) {
    final int w = img.getWidth(), h = img.getHeight();
    if (argb == null || argb.length != w * h) argb = new int[w * h];

    Image.Plane[] p = img.getPlanes();
    ByteBuffer yB = p[0].getBuffer();
    ByteBuffer uB = p[1].getBuffer();
    ByteBuffer vB = p[2].getBuffer();

    final int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
    final int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
    final int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
    final int uLim = uB.limit(), vLim = vB.limit();

    for (int j = 0; j < h; j++) {
      final int yBase  = j * yRow;
      final int uvRow  = (j >> 1);
      final int uBase  = uvRow * uRow;
      final int vBase  = uvRow * vRow;
      final int outRow = j * w;

      for (int i = 0; i < w; i++) {
        int Y = yB.get(yBase + i * yPix) & 0xFF;

        int ui = uBase + (i >> 1) * uPix;
        int vi = vBase + (i >> 1) * vPix;
        int U = (ui < uLim ? uB.get(ui) & 0xFF : 128) - 128;
        int V = (vi < vLim ? vB.get(vi) & 0xFF : 128) - 128;

        // BT.601, fixed point (<<16)
        int r = Y + ((91881 * V) >> 16);
        int g = Y - ((22554 * U + 46802 * V) >> 16);
        int b = Y + ((116130 * U) >> 16);

        if (r < 0) r = 0; else if (r > 255) r = 255;
        if (g < 0) g = 0; else if (g > 255) g = 255;
        if (b < 0) b = 0; else if (b > 255) b = 255;

        argb[outRow + i] = 0xFF000000 | (r << 16) | (g << 8) | b;
      }
    }
    return argb;
  }
  private void speak(String s) {
    if (!ttsReady || tts == null) return;
    tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "det");
  }
  private String distancePhrase(int mm) {
    if (mm <= 0) return "distance unknown";
    float m = mm / 1000f;
    float a = m / armLen;
    if (a < 0.4f) return "right in front of you";
    if (a < 0.8f) return "half an arm's length";
    if (a < 1.2f) return "about an arm's length";
    if (a < 2.0f) return "just out of reach";
    if (a < 4.0f) return "a couple of steps away";
    return Math.round(m * 3.28f / 2) * 2 + " feet away";
  }

  private String bearingPhrase(float cx) {
    if (cx < 160f) return "on your left";
    if (cx < 200f) return "slightly left";
    if (cx > 320f) return "on your right";
    if (cx > 280f) return "slightly right";
    return "straight ahead";
  }
  private int depthAt(Frame frame, Image depthImage, float xRot, float yRot, int sh) {
    cpuCoords[0] = yRot;
    cpuCoords[1] = sh - xRot;
    frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS, cpuCoords,
            Coordinates2d.TEXTURE_NORMALIZED, texCoords);
    if (texCoords[0] < 0 || texCoords[1] < 0 || texCoords[0] >= 1 || texCoords[1] >= 1) return 0;

    int w = depthImage.getWidth(), h = depthImage.getHeight();
    int dx = (int) (texCoords[0] * w), dy = (int) (texCoords[1] * h);
    if (dx < 0 || dy < 0 || dx >= w || dy >= h) return 0;

    Image.Plane plane = depthImage.getPlanes()[0];
    ByteBuffer b = plane.getBuffer().order(ByteOrder.nativeOrder());
    return Short.toUnsignedInt(
            b.getShort(dx * plane.getPixelStride() + dy * plane.getRowStride()));
  }
}

/**
 * Associates an Anchor with the trackable it was attached to. This is used to be able to check
 * whether or not an Anchor originally was attached to an {@link InstantPlacementPoint}.
 */
class WrappedAnchor {
  private Anchor anchor;
  private Trackable trackable;

  public WrappedAnchor(Anchor anchor, Trackable trackable) {
    this.anchor = anchor;
    this.trackable = trackable;
  }

  public Anchor getAnchor() {
    return anchor;
  }

  public Trackable getTrackable() {
    return trackable;
  }
}

