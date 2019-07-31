# EasyOpenCV

Finally, a straightforward and easy way to use OpenCV on your robot! With this library, you can **go from a stock SDK to running a sample OpenCV OpMode, with either an internal or external camera, in less than 2 minutes!**

## Installation instructions:

1. Open your FTC SDK Android Studio project
2. Open the `build.common.gradle` file:

    ![img-here](doc/images/build-common-gradle.png)

3. Add `jcenter()` to the `repositories` block at the bottom:

    ![img-here](doc/images/jcenter.png)

4. Open the `build.gradle` file for the TeamCode module:

    ![img-here](doc/images/teamcode-gradle.png)

5. At the bottom, add this:

        dependencies {
            implementation 'org.openftc:easyopencv:1.0-test'
         }

6. Now perform a Gradle Sync:

    ![img-her](doc/images/gradle-sync.png)

7. Because EasyOpenCv depends on [OpenCV-Repackaged](https://github.com/OpenFTC/OpenCV-Repackaged), you will also need to copy [`libOpenCvNative.so`](https://github.com/OpenFTC/OpenCV-Repackaged/blob/master/doc/libOpenCvNative.so) from the `/doc` folder of that repo into the `FIRST` folder on the internal storage of the Robot Controller.

8. Congrats, you're ready to go! Now check out the [example OpModes](https://github.com/OpenFTC/EasyOpenCV/tree/master/examples/src/main/java/org/openftc/easyopencv/examples).

## Known Issues:

Feel free to submit a pull request if you know how to fix any of these!

 - [Bug induced by SDK] On some devices, it has been reported that the entire RC app will crash if a webcam is disconnect while it is in use. However, this is a bug in the SDK's webcam driver, not a bug in this library. Devices that exhibit this behavior also suffer from the exact same issue when running the sample Vuforia webcam OpMode.
 - Webcam stream does not automatically restart if the USB link is disconnected and then reconnected during the same run of an OpMode
 - [???] Opening the internal camera very occasionally fails with a "Fail to connect to camera service" error from the Android OS. I think this may be a bug in the ROM of my test device rather than a bug with this library, because in that scenario the standard camera app also fails to open the camera with the same error.

## Changelog:

### v1.0

 - Initial release
