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
import org.firstinspires.ftc.robotcore.internal.system.Deadline;
import org.firstinspires.ftc.robotcore.internal.vuforia.externalprovider.CameraMode;
import org.firstinspires.ftc.robotcore.internal.vuforia.externalprovider.FrameFormat;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("WeakerAccess")
class OpenCvWebcam extends OpenCvCameraBase
{
    private final CameraManagerInternal cameraManager;
    private final Executor serialThreadPool;
    private final int secondsPermissionTimeout = 1;
    private final CameraName cameraName;
    private CameraCharacteristics cameraCharacteristics = null;
    private Camera camera = null;
    private CameraCaptureSession cameraCaptureSession = null;
    private Mat mat = new Mat();

    //----------------------------------------------------------------------------------------------
    // Constructors
    //----------------------------------------------------------------------------------------------

    public OpenCvWebcam(CameraName cameraName)
    {
        this.cameraManager = (CameraManagerInternal) ClassFactory.getInstance().getCameraManager();
        this.serialThreadPool = cameraManager.getSerialThreadPool();
        this.cameraName = cameraName;
    }

    public OpenCvWebcam(CameraName cameraName, int containerLayoutId)
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
    public synchronized void openCameraDeviceImplSpecific() /*throws CameraException*/
    {
        try
        {
            camera = cameraManager.requestPermissionAndOpenCamera(new Deadline(secondsPermissionTimeout, TimeUnit.SECONDS), cameraName, null);

            if (camera != null) //Opening succeeded!
            {
                cameraCharacteristics = camera.getCameraName().getCameraCharacteristics();
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

    @Override
    public synchronized void closeCameraDeviceImplSpecific()
    {
        if (camera != null)
        {
            stopStreaming();
            camera.close();
            camera = null;
        }
    }

    public synchronized void startStreamingImplSpecific(final int width, final int height)
    {
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
            throw new OpenCvCameraException("Camera does not support requested resolution!");
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
                        CameraMode streamingMode = new CameraMode(width, height, 30, FrameFormat.YUYV);

                        //Indicate how we want to stream
                        final CameraCaptureRequest cameraCaptureRequest = camera.createCaptureRequest(
                                streamingMode.getAndroidFormat(),
                                streamingMode.getSize(),
                                streamingMode.getFramesPerSecond());

                        // Start streaming!
                        session.startCapture(cameraCaptureRequest,
                                new OpenCvWebcamCaptureCallback(cameraCaptureRequest),
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
    public synchronized void stopStreamingImplSpecific()
    {
        if (cameraCaptureSession != null)
        {
            cameraCaptureSession.stopCapture();
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
    }

    private class OpenCvWebcamCaptureCallback implements CameraCaptureSession.CaptureCallback
    {
        Bitmap bitmap;

        OpenCvWebcamCaptureCallback(CameraCaptureRequest cameraCaptureRequest)
        {
            bitmap = cameraCaptureRequest.createEmptyBitmap();
        }

        @Override
        public void onNewFrame(@NonNull CameraCaptureSession session, @NonNull CameraCaptureRequest request, @NonNull CameraFrame cameraFrame)
        {
            notifyStartOfFrameProcessing();

            /*
             * Unfortunately, we can't easily work with the native memory in
             * in CameraFrame, so we're stuck doing the incredibly inefficient
             * method of converting it to a bitmap, and then turning around
             * and converting that bitmap into a Mat. If we could find a way to
             * set the Mat's image buffer to a pointer to the CameraFrame's buffer,
             * or even just do a memcpy from one buffer to the other, it would
             * likely improve performance significantly.
             *
             * TODO: investigate using a bit of native code to improve efficiency
             */
            cameraFrame.copyToBitmap(bitmap);
            Utils.bitmapToMat(bitmap, mat);

            handleFrame(mat);
        }
    }
}