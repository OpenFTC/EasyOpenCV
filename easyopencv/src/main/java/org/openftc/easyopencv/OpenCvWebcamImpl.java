/*
 * Original work (WebcamExample.java) copyright (c) 2018 Robert Atkinson
 * Derived work copyright (c) 2019 OpenFTC Team
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted (subject to the limitations in the disclaimer below) provided that
 * the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this list
 * of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice, this
 * list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * Neither the name of Robert Atkinson nor the names of his contributors may be used to
 * endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE GRANTED BY THIS
 * LICENSE. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESSFOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 * TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.openftc.easyopencv;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.support.annotation.NonNull;

import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.robotcore.external.ClassFactory;
import org.firstinspires.ftc.robotcore.external.android.util.Size;
import org.firstinspires.ftc.robotcore.external.function.Continuation;
import org.firstinspires.ftc.robotcore.external.hardware.camera.Camera;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraCaptureRequest;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraCaptureSequenceId;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraCaptureSession;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraCharacteristics;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraException;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraFrame;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraName;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.ExposureControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.FocusControl;
import org.firstinspires.ftc.robotcore.internal.camera.CameraManagerInternal;
import org.firstinspires.ftc.robotcore.internal.camera.ImageFormatMapper;
import org.firstinspires.ftc.robotcore.internal.camera.RenumberedCameraFrame;
import org.firstinspires.ftc.robotcore.internal.camera.libuvc.api.UvcApiCameraFrame;
import org.firstinspires.ftc.robotcore.internal.camera.libuvc.nativeobject.UvcFrame;
import org.firstinspires.ftc.robotcore.internal.system.Deadline;
import org.firstinspires.ftc.robotcore.internal.vuforia.externalprovider.CameraMode;
import org.firstinspires.ftc.robotcore.internal.vuforia.externalprovider.FrameFormat;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("WeakerAccess")
class OpenCvWebcamImpl extends OpenCvCameraBase implements CameraCaptureSession.CaptureCallback
{
    private final CameraManagerInternal cameraManager;
    private final Executor serialThreadPool;
    private final int secondsPermissionTimeout = 1;
    private final CameraName cameraName;
    private CameraCharacteristics cameraCharacteristics = null;
    private Camera camera = null;
    private CameraCaptureSession cameraCaptureSession = null;
    private Mat rawSensorMat;
    private Mat rgbMat;
    private byte[] imgDat;
    private volatile boolean isOpen = false;
    private volatile boolean isStreaming = false;

    //----------------------------------------------------------------------------------------------
    // Constructors
    //----------------------------------------------------------------------------------------------

    public OpenCvWebcamImpl(CameraName cameraName)
    {
        this.cameraManager = (CameraManagerInternal) ClassFactory.getInstance().getCameraManager();
        this.serialThreadPool = cameraManager.getSerialThreadPool();
        this.cameraName = cameraName;
    }

    public OpenCvWebcamImpl(CameraName cameraName, int containerLayoutId)
    {
        super(containerLayoutId);
        this.cameraManager = (CameraManagerInternal) ClassFactory.getInstance().getCameraManager();
        this.serialThreadPool = cameraManager.getSerialThreadPool();
        this.cameraName = cameraName;
    }

    //----------------------------------------------------------------------------------------------
    // Opening and closing
    //----------------------------------------------------------------------------------------------

    public synchronized ExposureControl getExposureControl()
    {
        ExposureControl control = camera.getControl(ExposureControl.class);

        if(control == null)
        {
            throw new RuntimeException("Exposure control not supported!");
        }

        return control;
    }

    public synchronized FocusControl getFocusControl()
    {
        FocusControl control = camera.getControl(FocusControl.class);

        if(control == null)
        {
            throw new RuntimeException("Focus control not supported!");
        }

        return control;
    }

    public synchronized CameraCharacteristics getCameraCharacteristics()
    {
        return cameraCharacteristics;
    }

    @Override
    public synchronized void openCameraDevice() /*throws CameraException*/
    {
        if(!isOpen)
        {
            try
            {
                camera = cameraManager.requestPermissionAndOpenCamera(new Deadline(secondsPermissionTimeout, TimeUnit.SECONDS), cameraName, null);

                if (camera != null) //Opening succeeded!
                {
                    cameraCharacteristics = camera.getCameraName().getCameraCharacteristics();
                    isOpen = true;
                }
                else //Opening failed! :(
                {
                    cameraCharacteristics = cameraName.getCameraCharacteristics();
                }
            }
            catch (Exception e)
            {
                camera = null;
                throw e;
            }
        }
    }

    @Override
    public synchronized void closeCameraDevice()
    {
        cleanupForClosingCamera();

        if(isOpen)
        {
            if (camera != null)
            {
                stopStreaming();
                camera.close();
                camera = null;
            }

            isOpen = false;
        }
    }

    @Override
    public synchronized void startStreaming(int width, int height)
    {
        startStreaming(width, height, getDefaultRotation());
    }

    @Override
    public synchronized void startStreaming(final int width, final int height, OpenCvCameraRotation rotation)
    {
        if(!isOpen)
        {
            throw new OpenCvCameraException("startStreaming() called, but camera is not opened!");
        }

        /*
         * If we're already streaming, then that's OK, but we need to stop
         * streaming in the old mode before we can restart in the new one.
         */
        if(isStreaming)
        {
            stopStreaming();
        }

        /*
         * Prep the viewport
         */
        prepareForStartStreaming(width, height, rotation);

        final CountDownLatch captureStartResult = new CountDownLatch(1);

        boolean sizeSupported = false;
        for(Size s : cameraCharacteristics.getSizes(ImageFormat.YUY2))
        {
            if(s.getHeight() == height && s.getWidth() == width)
            {
                sizeSupported = true;
                break;
            }
        }

        if(!sizeSupported)
        {
            StringBuilder supportedSizesBuilder = new StringBuilder();

            for(Size s : cameraCharacteristics.getSizes(ImageFormat.YUY2))
            {
                supportedSizesBuilder.append(String.format("[%dx%d], ", s.getWidth(), s.getHeight()));
            }

            throw new OpenCvCameraException("Camera does not support requested resolution! Supported resolutions are " + supportedSizesBuilder.toString());
        }

        try
        {
            camera.createCaptureSession(Continuation.create(serialThreadPool, new CameraCaptureSession.StateCallback()
            {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session)
                {
                    try
                    {
                        CameraMode streamingMode = new CameraMode(
                                width,
                                height,
                                cameraCharacteristics.getMaxFramesPerSecond(
                                        ImageFormatMapper.androidFromVuforiaWebcam(FrameFormat.YUYV),
                                        new Size(width, height)),
                                        FrameFormat.YUYV);

                        //Indicate how we want to stream
                        final CameraCaptureRequest cameraCaptureRequest = camera.createCaptureRequest(
                                streamingMode.getAndroidFormat(),
                                streamingMode.getSize(),
                                streamingMode.getFramesPerSecond());

                        // Start streaming!
                        session.startCapture(cameraCaptureRequest,
                                OpenCvWebcamImpl.this,
                                Continuation.create(serialThreadPool, new CameraCaptureSession.StatusCallback()
                                {
                                    @Override
                                    public void onCaptureSequenceCompleted(
                                            @NonNull CameraCaptureSession session,
                                            CameraCaptureSequenceId cameraCaptureSequenceId,
                                            long lastFrameNumber)
                                    {
                                        RobotLog.d("capture sequence %s reports completed: lastFrame=%d", cameraCaptureSequenceId, lastFrameNumber);
                                    }
                                }));
                    }
                    catch (CameraException | RuntimeException e)
                    {
                        e.printStackTrace();
                        RobotLog.e("exception setting repeat capture request: closing session: %s", session);
                        session.close();
                        session = null;
                    }

                    System.out.println("OpenCvWebcam: onConfigured");
                    cameraCaptureSession = session;
                    captureStartResult.countDown();
                }

                @Override
                public void onClosed(@NonNull CameraCaptureSession session)
                {

                }
            }));
        }
        catch (CameraException | RuntimeException e)
        {
            System.out.println("OpenCvWebcam: exception starting capture");
            captureStartResult.countDown();
        }

        // Wait for the above to complete
        try
        {
            captureStartResult.await(1, TimeUnit.SECONDS);
            System.out.println("OpenCvWebcam: streaming started");
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        isStreaming = true;
    }

    @Override
    protected OpenCvCameraRotation getDefaultRotation()
    {
        return OpenCvCameraRotation.SIDEWAYS_LEFT;
    }

    @Override
    protected int mapRotationEnumToOpenCvRotateCode(OpenCvCameraRotation rotation)
    {
        /*
         * The camera sensor in a webcam is mounted in the logical manner, such
         * that the raw image is upright when the webcam is used in its "normal"
         * orientation. However, if the user is using it in any other orientation,
         * we need to manually rotate the image.
         */

        if(rotation == OpenCvCameraRotation.SIDEWAYS_LEFT)
        {
            return Core.ROTATE_90_COUNTERCLOCKWISE;
        }
        if(rotation == OpenCvCameraRotation.SIDEWAYS_RIGHT)
        {
            return Core.ROTATE_90_CLOCKWISE;
        }
        else if(rotation == OpenCvCameraRotation.UPSIDE_DOWN)
        {
            return Core.ROTATE_180;
        }
        else
        {
            return -1;
        }
    }

    /***
     * Stop streaming frames from the webcam, if we were
     * streaming in the first place. If not, we don't do
     * anything at all here.
     */
    @Override
    public synchronized void stopStreaming()
    {
        if(!isOpen)
        {
            throw new OpenCvCameraException("stopStreaming() called, but camera is not opened!");
        }

        cleanupForEndStreaming();

        imgDat = null;
        rgbMat = null;
        rawSensorMat = null;

        if (cameraCaptureSession != null)
        {
            cameraCaptureSession.stopCapture();
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }

        isStreaming = false;
    }

    /*
     * This needs to be synchronized with stopStreamingImplSpecific()
     * because we touch objects that are destroyed in that method.
     */
    @Override
    public synchronized void onNewFrame(@NonNull CameraCaptureSession session, @NonNull CameraCaptureRequest request, @NonNull CameraFrame cameraFrame)
    {
        notifyStartOfFrameProcessing();

        if(imgDat == null)
        {
            imgDat = new byte[cameraFrame.getImageSize()];
        }
        if(rgbMat == null)
        {
            rgbMat = new Mat(cameraFrame.getSize().getHeight(), cameraFrame.getSize().getWidth(), CvType.CV_8UC1);
        }
        if(rawSensorMat == null)
        {
            rawSensorMat = new Mat(cameraFrame.getSize().getHeight(), cameraFrame.getSize().getWidth(), CvType.CV_8UC2);
        }

        try
        {
            /*
             * v1.2 HOTFIX for renderscript crashes on some devices when using frame.copyToBitmap()
             *
             * Is it pretty? Heck no. Does it work? Yes! :)
             * Also it seems to be *considerably* more efficient than copyToBitmap(). Not entirely
             * sure why because one would think renderscript would be faster since it can run on
             * the GPU...
             */
            RenumberedCameraFrame renumberedCameraFrame = (RenumberedCameraFrame) cameraFrame;
            Field innerFrameField = RenumberedCameraFrame.class.getDeclaredField("innerFrame");
            innerFrameField.setAccessible(true);
            CameraFrame innerFrame = (CameraFrame) innerFrameField.get(renumberedCameraFrame);
            UvcApiCameraFrame uvcApiCameraFrame = (UvcApiCameraFrame) innerFrame;
            Field uvcFrameField = UvcApiCameraFrame.class.getDeclaredField("uvcFrame");
            uvcFrameField.setAccessible(true);
            UvcFrame uvcFrame = (UvcFrame) uvcFrameField.get(uvcApiCameraFrame);

            uvcFrame.getImageData(imgDat);
            rawSensorMat.put(0,0,imgDat);
            Imgproc.cvtColor(rawSensorMat, rgbMat, Imgproc.COLOR_YUV2RGBA_YUY2, 4);

            handleFrame(rgbMat);

        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}