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
            compile 'org.openftc:easyopencv:1.0-test'
         }

6. Now perform a Gradle Sync:

    ![img-her](doc/images/gradle-sync.png)

7. Because EasyOpenCv depends on [OpenCV-Repackaged](https://github.com/OpenFTC/OpenCV-Repackaged), you will also need to copy `libOpenCvNative.so` from the `/doc/apk` folder of that repo into the `FIRST` folder on the internal storage of the Robot Controller.

8. Congrats, you're ready to go! Now check out either the [Internal Camera Example]() or the [External Camera Example]().