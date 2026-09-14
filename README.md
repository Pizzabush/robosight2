## Overview:
Robosight is an android app that identifies nearby objects and speaks their name, distance, and direction. It is built for visually impaired.

One points the phone at something, taps the screen, and hears a response such as "bottle, about an arm's length, slightly left"

Distance is expressed relative to the user's own arm length rather than in centimeters, because it was decided to be more intuitive.

## Status
Working prototype. The perception pipeline is complete and runs end to end. It has not yet been tested with blind users, and several design decisions have been somewhat arbitrarily set.
Developed and validated on a Samsung Galaxy A15 5G (Android 14).

## How it works
The app built on ARCore's HelloAR Java sample. The sample's AR rendering was stripped out and replaced by a detection-and-speech pipeline.
Each time the user taps the screen, one frame goes through 6 stages:

1. camera frame: (YUV_420_888, 640x480)

2.  yuvToArgb(): convert to ARGB, reusing a preallocated int[]

3. rotate + letterbox:  90 deg CW, scale to fit, pad to 416x416

4.  ONNX Runtime: YOLOE-26n-seg, output [1, 300, 38]

5. depthAt() x5:  sample ARCore's depth map inside each box, take the median

6.  select + phrase:  pick one detection, build a sentence, speak it

## The 3 coordinate spaces
Camera image
- 640 x 480 (landscape)
- appears in img.getWidth(), getHeight(), ARCore APIs
  
Rotated
- Rotated	480 x 640 (portrait)
- appears in Detection boxes, cx, cy, bearing logic
  
Model image
- 416 x 416 (letterboxed)
- Raw ONNX output, before px, py, and s are divided out
	
	
## Code structure
Everything lives in HelloArActivity.java. The file is organized in 3 sections
1. Lifecycle and ARCore setup — inherited from the sample
2. Settings dialogs — inherited, plus one new dialog
3. Detection pipeline — written for this project

### Methods added for this project
detect(Frame frame, Image img)

- The core of the app. Called once per tap from onDrawFrame(). Responsible for the entire system: preprocessing, inference, depth sampling, selection, and speech.
Calls yuvToArgb(), depthAt(), distancePhrase(), bearingPhrase(), speak(), and initOrt() if the session isn't ready.
Preprocessing reuses three preallocated buffers (canvasBmp, pixels, inputBuf) rather than allocating per frame. inputBuf is a direct FloatBuffer because ONNX Runtime copies non-direct buffers on every call.
Output is read via getFloatBuffer() rather than getValue(). The latter materializes a float[1][300][38] — hundreds of small heap objects per inference.
The model export includes NMS, so the output is a fixed 300 rows sorted by descending confidence, each 38 wide: 4 box coordinates, confidence, class index, and 32 mask coefficients (currently unused).

yuvToArgb(Image img)
- Converts ARCore's YUV_420_888 camera image to packed ARGB using fixed-point BT.601 coefficients. Writes into a reused argb[] field, resized only if the camera resolution changes.
Handles arbitrary rowStride and pixelStride, which vary by device — assuming tightly packed planes is a common source of messed up images on unfamiliar hardware.
Called only by detect().

depthAt(Frame frame, Image depthImage, float xRot, float yRot, int sh)
- Samples ARCore's 16-bit depth map at one point and returns millimeters, or 0 if no valid depth exists there.
- Three steps: convert rotated coordinates to camera coordinates, ask ARCore to map camera pixels to normalized texture coordinates via transformCoordinates2d(), then index the depth plane using its `pixelStride` and `rowStride`.
The depth image is a crop of the camera image, so not every camera pixel has a corresponding depth value. Negative or out-of-range texture coordinates mean "no data" and return `0`.
Called five times per detection by detect() — once at the box centroid and once at each of four offsets — with the median of the valid samples used as the distance. Median rather than mean because depth maps have holes, and a single zero or wild value would drag an average badly.
Note that ARCore's depth is the z-coordinate relative to the camera, not ray length. For an object well off-center, true straight-line distance is slightly longer than reported.

distancePhrase(int mm)

- Converts millimeters to a spoken phrase, scaled by the user's configured armLen:

 Multiple of arm length	Phrase
 0.4	"right in front of you"
0.4 – 0.8	"half an arm's length"
0.8 – 1.2	"about an arm's length"
1.2 – 2.0	"just out of reach"
2.0 – 4.0	"a couple of steps away"
 4.0	feet, rounded to the nearest 2
 
- The depth measurement is good to roughly ±5 cm. Returns "distance unknown" when depth was unavailable. Called by detect(). Depends on the armLen field, set by launchArmLengthDialog().

bearingPhrase(float cx)

- Maps a detection's horizontal position in the rotated frame (0–480) to one of five directions: "on your left", "slightly left", "straight ahead", "slightly right", "on your right". Bearing matters more than it first appears. Distance alone is an underdetermined instruction — a user told "0.57 meters" reaches straight forward and touches bare desk when the object was 30 cm to the left.
Called by detect().

speak(String s)

-Thin wrapper over TextToSpeech.speak() using QUEUE_FLUSH, so a new announcement replaces anything still speaking rather than queuing behind it.
Returns silently if TTS hasn't finished initializing. Called from detect() on the GL thread and from launchArmLengthDialog() on the main thread.
initOrt()
- Lazily creates the OrtEnvironment and OrtSession from the bundled .onnx asset, and caches the input tensor name. Called from onResume() and defensively from detect().
  
holdUncalibratedSensorsOpen()

- Serves two unrelated purposes, which is worth knowing before modifying it.
- First, it works around an ARCore 1.54 regression on Samsung devices. The Samsung sensor HAL routes EnableSensor through queueBatch when uncalibrated sensors aren't already streaming, which ARCore's `standard_event_provider` cannot handle. Registering a listener before ARCore initializes keeps the HAL in continuous mode so registration succeeds. Remove this and AR tracking fails to start on affected devices.
- Second, the listener reads gyroscope magnitude into the gyroMag field, which onDrawFrame() uses to skip detection while the phone is being swung. Motion-blurred frames produce unreliable detections, and speaking an unreliable detection as fact to someone who can't verify it is the worst failure mode this app has.
Note that TYPE_ACCELEROMETER_UNCALIBRATED is unavailable on some devices — including the primary test device — so the workaround holds only the gyroscope there. It still works.

launchArmLengthDialog()

- Presents three arm-length presets, writes the selection to SharedPreferences, and confirms aloud.
Presets rather than a numeric field: a blind user typing centimeters into a text box is a poor first-run experience. A future version could measure arm length directly — hold the phone, touch a wall at full extension, tap — since the depth pipeline already provides everything needed.
Called from settingsMenuClick(). Sets armLen, read by distancePhrase().

Modified sample methods
onCreate() — sets a content description and click listener on the GLSurfaceView (rather than a raw touch listener, so TalkBack's double-tap activates it), loads armLen from preferences, initializes TTS.
onDrawFrame() — stripped of plane rendering, point cloud, pawn placement, lighting estimation, and status snackbars. What remains: acquire frame, run detection if requested, update the depth texture, draw the camera background.
onDestroy() — shuts down TTS alongside the ARCore session.
configureSession() — enables DepthMode.AUTOMATIC where supported.

## Selection logic

- A frame typically yields several detections. Only one is spoken. Most centered wins. The user pointed the phone at something; the object nearest the frame center is most likely what they meant. Confidence fallback. If the most-centered detection is below SPEAK_CONF (0.40), fall back to the highest-confidence detection in the frame. Without this, a low-confidence blob near the center suppresses a perfectly good detection off to the side.
Cross-class dedup. If another detection sits within 40 px and has higher confidence, prefer it. The model sometimes fires two classes on one object — "cup" and "bottle", or "credit card" and "envelope" — and only one label should be spoken.
Nothing found. If no detection clears the threshold, say so. Silence is ambiguous: it could mean nothing was there, or that the tap never registered.
Note that step 3 does not merge genuinely distinct objects of the same class. Two pencils 200 px apart are two pencils, and only the more central one gets announced.

## constants
These are all informed guesses. May be changed based on user feedback.
- SZ, value of 416, refers to	model input size
- CONF, value of	0.25, refers to	Minimum confidence to record a detection
- SPEAK_CONF, value of	0.40, refers to	Minimum confidence to speak
- armLen, value of	0.65 m, refers to	default arm length, user-adjustable
- MAX_DET, value of	16, refers to	detections retained per frame
- gyro threshold, value of	0.8 rad/s, Above this, skip detection
- dedup radius, value of	40 px, 	Centers this close are treated as one object

## Performance
On the Samsung Galaxy A15 5G, a full detect cycle runs roughly 350–450 ms including depth sampling, with occasional spikes past 600 ms. The first inference after launch takes 1–1.5 s due to graph warmup.
Since detection is tap-triggered, this reads as a short pause after tapping rather than a frame-rate problem. Speaking the result takes about 2 seconds, which dominates the perceived latency anyway.
Known remaining inefficiencies, none currently blocking:
A Bitmap is allocated per call in detect() and could be hoisted
Rotation, scaling, and normalization make four passes over the pixels and could be folded into yuvToArgb()
Inference runs on the GL thread and could move to a background executor
The model computes 32 mask coefficients per detection that are never used; a detection-only export would be cheaper

## Known limitations
No height information. The app reports distance and bearing but not whether an object is on the floor or on a table. Distinguishing trip hazards from tabletop objects would require combining depth with camera pose and a floor estimate.
Single-device validation. Everything has been tested on one phone. Camera formats, depth resolution, and sensor behavior vary by manufacturer.
Untested with the target users. The phrasing, timing, and selection rules are reasoned rather than observed.
Repeat queries return the same object. A user who reaches and misses gets the identical announcement on the next tap, with no way to ask for the next candidate.

## License
This project is licensed under AGPL-3.0. See LICENSE.
This project utilizes code from Ultralytics YOLO, licensed under AGPL-3.0.
Derived from the ARCore `HelloAR` Java sample, Copyright 2017 Google LLC, licensed under Apache License 2.0. The sample's source files have been modified; the original Apache 2.0 notices are retained in those files.
