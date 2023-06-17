# EasyOpenCV

NOTE: SDK v8.0+ is required to use this

NOTE: an OpenRC-based SDK is NOT required to use this

Finally, a straightforward and easy way to use OpenCV on an FTC robot! With this library, you can **go from a stock SDK to running a sample OpenCV OpMode, with either an internal or external camera, in just a few minutes!**

Features at a glance:

 - **Supports concurrent streaming from:**
     - An internal camera and a webcam
     - Two webcams
     - Two internal cameras *(select devices; internal cameras must not share the same bus)*
 - Supports Driver Station camera preview feature introduced in SDK v5.1
 - Supports tapping on the viewport to cycle through the various stages of a pipeline (see [PipelineStageSwitchingExample](https://github.com/OpenFTC/EasyOpenCV/blob/master/examples/src/main/java/org/firstinspires/ftc/teamcode/PipelineStageSwitchingExample.java))
 - Supports using webcams directly with OpenCV instead of going through a Vuforia instance
 - Supports changing pipelines on-the-fly (while a streaming session is in flight)
 - Supports dynamically pausing/resuming live viewport to save battery and CPU time
 -  Support for rotating stream based on physical camera orientation (e.g. use a webcam in portrait without having to mess with rotation yourself)
 - Loads 10MB native library for OpenCV from internal storage to prevent bloating the APK
 
## Device compatibility:

Unfortunately, due to a [known bug with OpenCV 4.x](https://github.com/opencv/opencv/issues/15389), EasyOpenCV is only compatible with devices that run Android 5.0 or higher. For FTC, this means that it is incompatible with the ZTE Speed. EasyOpenCV will work fine on all other FTC-legal devices (including the new Control Hub).

## Documentation:

 - [Camera Initialization Overview](https://github.com/OpenFTC/EasyOpenCV/blob/master/doc/user_docs/camera_initialization_overview.md)
 - [Pipelines Overview](https://github.com/OpenFTC/EasyOpenCV/blob/master/doc/user_docs/pipelines_overview.md)
 - [Javadocs](https://javadoc.io/doc/org.openftc/easyopencv/latest/index.html)
 - [Example programs](https://github.com/OpenFTC/EasyOpenCV/tree/master/examples/src/main/java/org/firstinspires/ftc/teamcode)
 
 **IMPORTANT NOTE:** EasyOpenCV delivers RGBA frames, but desktop OpenCV (what you may be used to) delivers BGR frames. Beware when porting code between the two!

## Installation instructions (OnBotJava):

1. Go to the [Releases page](https://github.com/OpenFTC/EasyOpenCV/releases), find the latest release, and download the OBJ AAR bundle file from the assets section
2. In the OnBotJava console, click the Upload Files button (to the left of the trash can), select the `.aar` file you just downloaded, and wait while OnBotJava processes the library
8. Congrats, you're ready to go! Now check out the example OpModes and other documentation in the [Documentation Section](https://github.com/OpenFTC/EasyOpenCV/tree/master#documentation).

## Installation instructions (Android Studio):

**IMPORTANT NOTE: These instructions assume you are starting with a clean SDK project. This library includes the OpenCV Android SDK, so if you have already installed OpenCV in your project through the traditional means, you will need to remove it first. Otherwise, you will get a compiler error that multiple files define the same class.**

**IMPORTANT NOTE #2: Do NOT locally clone and/or import this project unless you want to develop this library itself! If you're just a normal user, follow the below instructions verbatim.**

1. Open your FTC SDK Android Studio project

2. Open the `build.gradle` file for the TeamCode module:

    ![img-here](doc/images/teamcode-gradle.png)

3. At the bottom, add this:
    ```gradle
    dependencies {
        implementation 'org.openftc:easyopencv:1.7.0'
    } 
    ```
4. Now perform a Gradle Sync:

    ![img-here](doc/images/gradle-sync.png)

5. Congrats, you're ready to go! Now check out the example OpModes and other documentation in the [Documentation Section](https://github.com/OpenFTC/EasyOpenCV/tree/master#documentation).


## Changelog:

### v1.7.0

 - Adds new `NATIVE_VIEW` viewport renderer option
    - Attempts to provide a balance between the stability of the `SOFTWARE` renderer and the speedup seen with the `GPU_ACCELERATED` renderer
    - Drawing is done on the UI thread using the GPU accelerated main canvas, instead of making another canvas as the `GPU_ACCELERATED` renderer does
 - Uses anti-aliasing when drawing the statistics overlay to make text more readable on low resolution screens
 - Adds ability for user pipelines to hook into the Canvas rendering of frames to the live view
    - This is an alternate means besides OpenCV calls to draw annotations from a pipeline
    - Allows drawing annotations at the full screen resolution even if performing image processing at a much lower resolution (e.g. 320x240). This allows e.g. drawing actually legible text on a low res image feed.
    - Because live view rendering happens on a different thread than the image processing, in order to make use of this feature, pipelines must call `requestViewportDrawHook(object)` during `processFrame()`, providing any type of context object they may wish which encapsulates the data needed to draw the annotations. Pipelines then also need to override `onDrawFrame(...)` which can receive that same object back, and perform the annotation drawing there.
    - The image sent to the DriverStation is now rendered using an offsceen canvas to ensure that annotations rendered using the canvas will be visible on the DS as well.
 - Adds support for MJPEG streaming for webcams
    - Requires FTC SDK v8.2
    - Uses libjpeg-turbo for JPEG decompression routine
    - Allows for streaming at full frame rate at higher resolutions (e.g. 1280x720) which were previously limited to 10FPS due to bandwidth constraints
    - Improves ability to use multiple cameras simultaneously by reducing bandwidth usage
    - CPU load will be increased due to additional overhead from JPEG decompression
    - Uncompressed streaming is still the default; in order to request MJPEG, use the overloaded `startStreaming()` method in `OpenCvWebcam` which takes a `StreamFormat` argument
 - Fixes a deadlock when trying to switch cameras when using `OpenCvSwitchableWebcam`
 - Fixes cases where mutex might not be released in internal camera v2 implementation
 - Updates OpenCV-Repackaged transitive dependency to `4.7.0-A`

### v1.6.2

- Add generic `getControl()` method to OpenCvWebcam
- Fix corrupted camera frame delivery when using Camera2 API on some devices
- Prevent deadlock if pipeline tried to perform a synchronized UI thread operation and the device orientation was changed

### v1.6.1

 - Fixes bug where if using a webcam, frames were not delivered to user pipeline when calling `startStreaming()` after a previous call to `stopStreaming()` even though the stream was in fact restarted successfully (#65)
 - Scales viewport statistics overlay based on pixel density so that it's not overly large on some devices

### v1.6.0

 - Add support for getting WhiteBalanceControl for webcams
 - Handle pipeline returning empty Mat for viewport display with an error message instead of an unclear exception
 - Add SENSOR_NATIVE to camera rotation enum
 - Desynchronize setPipeline() from active pipeline frame processing (fixes #58)
 - Synchronize getting webcam controls with opening/closing camera
 - Add support for getting the CameraCalibrationIdentity for an OpenCvWebcam
 - Improve memory leak detection warning

### v1.5.3

 - Dependency on OpenCV-Repackaged has been changed to a version which bundles the OpenCV native library with the artifact instead of requiring it to be copied to external storage manually. This change was made because:
    1. The original reason for not bundling the native library was to reduce APK size for wireless deploy time, but that was something that it seems I cared about more than anyone else, and people often seem to have difficulty with setting it up properly for some reason
    2. There have been multiple requests for 64-bit support, which would have made the dynamic loading from external storage even more complicated
 - 64-bit support added
 - Increases default webcam permission timeout to 5 seconds
 - Removes app name resource strings which shouldn't have ever been there in the first place

### v1.5.2

 - Fixes compatibility with SDK v8.0. You MUST use v1.5.2 (or later) for SDK 8.0. Previous versions Will **not** work!! Backwards compatibility is NOT maintained for this release unfortunately!
 - Fixes possible leak of framebuffer when viewport render thread was restarted

### v1.5.1

 - Fixes crash with SDK v7.0 when memory leak warning was generated 

### v1.5.0

 - Fixes compatibility with SDK v7.0
   - You MUST use 1.5.0 (or later) for SDK 7.0. Previous versions Will **not** work!!
   - Backwards compatibility with SDK v6.1 is maintained.
 - First release supporting OnBotJava! (See setup instructions)  
 - **API CHANGE:** OpenCV core upgraded to OpenCV v4.5.3 (transitive dependency on `opencv-repackaged` updated to `4.5.3-B`)
   - This change also requires an updated native library to be copied to the device (see installation instructions above)
 - Failure to open the camera device is now properly handled (previously, the `onOpened()` callback would be called even in the case of failure)
   - **API CHANGE:** User-defined `AsyncCameraOpenListener` instances must now also implement the `void onError(int errorCode)` function
 - Change webcam opening timeout to be user-configurable (new function `void setMillisecondsPermissionTimeout(int ms)` added)
 - Fix race condition when closing camera which could cause the camera worker thread to crash with a null pointer when trying to send a frame to the viewport
 - Fix issue with viewport where user-drawn parts of the image (e.g. rect boxes) would not appear in the correct color unless an alpha parameter for the color was specified
 - Fix bug where Camera2 backend was broken on some devices due to reading the image timestamp after closing the Image object
 - Samples moved to `org.firstinspires.ftc.teamcode` package

### v1.4.4

 - Add support for Vuforia passthrough mode, which allows running Vuforia and OpenCV simultaneously on the same camera. Please see [OpenCvAndVuforiaOnSameCameraExample](https://github.com/OpenFTC/EasyOpenCV/blob/master/examples/src/main/java/org/openftc/easyopencv/examples/OpenCvAndVuforiaOnSameCameraExample.java).

### v1.4.3

 - **IMPORTANT NOTE:** SDK v6.1 or higher is now required!
 - Add support for additional webcam controls introduced in SDK v6.1
 - Add `saveMatToDiskFullPath()` method to pipeline class
 - Add `TimestampedOpenCvPipeline` class which extends `OpenCvPipeline` and delivers capture time timestamps along with frames. See new `TimestampedPipelineExample` file.

### v1.4.2

 - Add ability to set FocusMode to Internal camera v1 API
 - General improvements to Internal camera v2 API
     - Make startStreaming() check to make sure camera is opened
     - Make startStreaming() check to make sure requested resolution is supported
     - Fix potential failure to release mutex
     - Cleaned up the conversion of raw camera data into the Mat by using some native C++ code
 - Viewport improvements
     - Image is now centered inside the SurfaceView
     - Unused space of the surface view is set to the same color as the activity background color
     - Added optional GPU-accelerated rendering mode for viewport
     - Warn instead of throwing when setting viewport rendering policy for webcams
 - Optimized webcam frame delivery by using some native C++ code to avoid unnecessary hidden `memcpy` operations
 - Add API for recording pipelines to a video file
     - This is a **BETA** API and may be unstable, and subject to change! Caveat emptor.
 - Fix memory leak detector to trip only after settle delay, instead of only before settle delay.

### v1.4.1

 - Transitive dependency on OpenCV-Repackged updated to 4.1.0-C, which specifically handles error case of failure to load 32-bit library when FTC Robot Controller app has already loaded another native library as 64-bit
 - Fixes issue which prevented webcams from initializing in v1.4.0 which was found in prerelease testing, fixed, and yet somehow didn't make it into git...

### v1.4.0

 - Adds support for Android Camera2 API
     - New `OpenCvInternalCamera2` interface. Camera2 instances can be obtained from `OpenCvCameraFactory`, just like other types
     - Supports manual control over sensor parameters:
         - ISO (gain)
         - Exposure
         - Focus
         - White balance
         - Frame interval (FPS)
 - Make `OpenCvCamera` interface extend `CameraStreamSource` so that casting to implementation objects isn't required to use a camera as a stream source for something other than the DS
 - Adds `setViewportRenderingPolicy()` API to `OpenCvCamera interface`, provides option to:
     - `MAXIMIZE_EFFICIENCY` Keep viewport behavior as it always has been, OR
     - `OPTIMIZE_VIEW` At the expense of CPU time (and viewport smoothness), automatically orient preview image such that it's not constantly 90 degrees out from expected with an internal camera when the physical device orientation does not match the streaming orientation
 - Add memory leak detector for pipelines
     - Not 100% accurate but, seems to be fairly effective
     - Has a crude garbage collector run detector
     - Can be enabled/disabled or have parameters tweaked by modifying superclass variables from your pipeline constructor
 - Add `init(Mat m)` method to pipeline class, which will be called with the first frame from the camera, allowing you to initialize submats and the like for your pipeline
 - Adds pipeline utility function for saving Mats to disk.
     - Save function clones input mat and writes to disk asynchronously to prevent stalling pipeline
     - Up to 5 save operations can be running simultaneously; once this limit is reached, the pipeline will be stalled until one has completed
 - Adds APIs for closing and opening the camera asynchronously. This is now the recommended way to open and close, as it can help to prevent `stuckInXYZ()` issues and the like. Please consult the `OpenCvCamera` interface javadoc for details
 - Adds support for switchable webcams
     - New `OpenCvSwitchableWebcam` interface. Instances can be obtained from `OpenCvCameraFactory`, just like other types
 - Fix deadlock when closing webcams
 - Increase webcam open timeout to 2 seconds. This increases compatibility with random nobrand cameras.
 - Adds new `OpenCvWebcam` interface which exposes some additional functionality for webcams
     - Instances can be obtained from `OpenCvCameraFactory`, just like other types
     - Support for exposure control & focus control, using the SDK UVC driver's built-in interfaces
 - Adds new samples demonstrating some of the new functionality
 - Library version now printed to logcat when creating camera instance
 - Misc. bug fixes

### v1.3.2

 - Resolutions >480p are now possible with webcams (at reduced framerates)
 - Add exposure compensation and autoexposure lock APIs for internal camera
 - Fix blank display when user pipeline returned cropped mat of type CV_8UC1 (e.g. masks)
 - Print supported resolutions when user selects illegal resolution for camera

### v1.3.1

 - Transitive dependency on OpenCV-Repackged updated to 4.1.0-B, which drastically improves error handling when loading native library

### v1.3

 - Add official support for multiple concurrent camera streams (was possible before but required manual activity UI modifications)
   - Also allows for running Vuforia alongside EasyOpenCV
 - Add "TrackerAPI" classes (ability to run multiple OpenCV algorithms in the same pipeline, and switch between which output is rendered to the screen in realtime by tapping the viewport)
 - Add support for rendering cropped returns from user pipeline
 - A little internal code cleanup
 - Optimise viewport to re-use existing framebuffer memory
 - Fix issue where if a user pipeline created a submat from the input Mat, the submat would be de-linked from the input buffer on the next frame
 - Added ability to use some advanced features for internal cameras:
   - Added ability to set "recording hint"
   - Added ability to set "hardware frame timing range"
   - Added ability to control zoom
   - Added ability to control flashlight
   - Added support for using double buffering (default; can improve FPS)
 - API change: camera instances are now created by invoking `OpenCvCameraFactory.getInstance().create...`
 - Add examples:
   - InternalCameraAdvancedFeaturesExample
   - MultipleCameraExample
   - MultipleCameraExampleOpenCvAlongsideVuforia
   - TrackerApiExample

### v1.2

 - **HOTFIX:** implement workaround for SDK bug of RenderScript failing to initialize on some devices which prevented the webcam frames from being forwarded through the JNI to the Java side (See issue #1)

### v1.1

 - SDK v5.1 or higher now required
 - Add support for stream preview on Driver Station
 - Fix bug where internal camera was not correctly released
 - Fix bug where a null pipeline caused a crash
 - API change: user pipelines now need to `extends OpenCvPipeline` instead of `implements OpenCvPipeline`
 - Add ability for user pipeline to override `onViewportTapped()` to be notified if the user taps the viewport
 - Add `PipelineStageSwitchingExample` to show how to use `onViewportTapped()` to change which stage of your pipeline is drawn to the viewport for debugging purposes. It also shows how to get data from your pipeline to your OpMode.

### v1.0

 - Initial release
