/*
 * Copyright (c) 2019 OpenFTC Team
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.openftc.easyopencv;

import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.qualcomm.robotcore.eventloop.EventLoopManager;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.robot.RobotState;
import com.qualcomm.robotcore.util.ElapsedTime;
import com.qualcomm.robotcore.util.MovingStatistics;
import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.robotcore.external.android.util.Size;
import org.firstinspires.ftc.robotcore.external.function.Consumer;
import org.firstinspires.ftc.robotcore.external.function.Continuation;
import org.firstinspires.ftc.robotcore.external.function.ContinuationResult;
import org.firstinspires.ftc.robotcore.external.stream.CameraStreamServer;
import org.firstinspires.ftc.robotcore.external.stream.CameraStreamSource;
import org.firstinspires.ftc.robotcore.internal.opmode.OpModeManagerImpl;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;

public abstract class OpenCvCameraBase implements OpenCvCamera, CameraStreamSource
{

    private OpenCvPipeline pipeline = null;
    private LinearLayout viewportContainerLayout;
    private MovingStatistics msFrameIntervalRollingAverage;
    private MovingStatistics msUserPipelineRollingAverage;
    private MovingStatistics msTotalFrameProcessingTimeRollingAverage;
    private ElapsedTime timer;
    private OpenCvViewport viewport;
    private OpenCvCameraRotation rotation;
    private int frameCount = 0;
    private float avgFps;
    private int avgPipelineTime;
    private int avgOverheadTime;
    private int avgTotalFrameTime;
    private long currentFrameStartTime;
    private final Object bitmapFrameLock = new Object();
    private Continuation<? extends Consumer<Bitmap>> bitmapContinuation;
    private Mat rotatedMat = new Mat();
    private Mat matToUseIfPipelineReturnedCropped;
    private Mat croppedColorCvtedMat = new Mat();
    private Scalar brown = new Scalar(82, 61, 46);

    /*
     * NOTE: We cannot simply pass `new OpModeNotifications()` inline to the call
     * to register the listener, because the SDK stores the list of listeners in
     * a WeakReference set. This causes the object to be garbage collected because
     * nothing else is holding a reference to it.
     */
    private OpModeNotifications opModeNotifications = new OpModeNotifications();

    public OpenCvCameraBase()
    {
        frameCount = 0;
        LIFO_OpModeCallbackDelegate.getInstance().add(opModeNotifications);
    }

    public OpenCvCameraBase(int containerLayoutId)
    {
        this();

        setupViewport(containerLayoutId);
    }

    public synchronized final void cleanupForClosingCamera()
    {
        if(viewport != null)
        {
            removeViewportAsync();
        }
    }

    public synchronized final void prepareForStartStreaming(int width, int height, OpenCvCameraRotation rotation)
    {
        this.rotation = rotation;
        msFrameIntervalRollingAverage = new MovingStatistics(30);
        msUserPipelineRollingAverage = new MovingStatistics(30);
        msTotalFrameProcessingTimeRollingAverage = new MovingStatistics(30);
        timer = new ElapsedTime();

        if(viewport != null)
        {
            viewport.setSize(getFrameSizeAfterRotation(width, height, rotation));
            viewport.activate();
        }

        /*
         * For preview on DS
         */
        CameraStreamServer.getInstance().setSource(this);
    }

    public synchronized final void cleanupForEndStreaming()
    {
        matToUseIfPipelineReturnedCropped = null;

        if(viewport != null)
        {
            viewport.deactivate();
        }
    }

    @Override
    public synchronized final void pauseViewport()
    {
        if(viewport != null)
        {
            viewport.pause();
        }
    }

    @Override
    public synchronized final void resumeViewport()
    {
        if(viewport != null)
        {
            viewport.resume();
        }
    }

    @Override
    public synchronized final void showFpsMeterOnViewport(boolean show)
    {
        if(viewport != null)
        {
            viewport.setFpsMeterEnabled(show);
        }
    }

    @Override
    public synchronized final void setPipeline(OpenCvPipeline pipeline)
    {
        this.pipeline = pipeline;
    }

    private void setupViewport(final int containerLayoutId)
    {
        final CountDownLatch latch = new CountDownLatch(1);

        //We do the viewport creation on the UI thread, but if there's an exception then
        //we need to catch it and rethrow it on the OpMode thread
        final RuntimeException[] exToRethrowOnOpModeThread = {null};

        AppUtil.getInstance().getActivity().runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    viewportContainerLayout = (LinearLayout) AppUtil.getInstance().getActivity().findViewById(containerLayoutId);

                    if(viewportContainerLayout == null)
                    {
                        throw new OpenCvCameraException("Viewport container specified by user does not exist!");
                    }
                    else if(viewportContainerLayout.getChildCount() != 0)
                    {
                        throw new OpenCvCameraException("Viewport container specified by user is not empty!");
                    }

                    viewport = new OpenCvViewport(AppUtil.getInstance().getActivity(), new View.OnClickListener()
                    {
                        @Override
                        public void onClick(View view)
                        {
                            synchronized (OpenCvCameraBase.this)
                            {
                                if(pipeline != null)
                                {
                                    pipeline.onViewportTapped();
                                }
                            }
                        }
                    });

                    viewport.setSize(new org.firstinspires.ftc.robotcore.external.android.util.Size(320, 240));

                    viewport.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                    viewportContainerLayout.setVisibility(View.VISIBLE);
                    viewportContainerLayout.addView(viewport);

                    latch.countDown();
                }
                catch (RuntimeException e)
                {
                    exToRethrowOnOpModeThread[0] = e;
                }

            }
        });

        if(exToRethrowOnOpModeThread[0] != null)
        {
            throw exToRethrowOnOpModeThread[0];
        }

        try
        {
            latch.await();
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
            Thread.currentThread().interrupt();
            viewport = null;
        }
    }

    private void removeViewportAsync()
    {
        AppUtil.getInstance().runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                viewportContainerLayout.removeView(viewport);
                viewport = null;
                viewportContainerLayout.setVisibility(View.GONE);
            }
        });
    }

    protected void notifyStartOfFrameProcessing()
    {
        currentFrameStartTime = System.currentTimeMillis();
    }

    protected synchronized void handleFrame(Mat frame)
    {
        try
        {
            handleFrameUserCrashable(frame);
        }
        catch (Exception e)
        {
            emulateEStop(e);
        }
    }

    protected synchronized void handleFrameUserCrashable(Mat frame)
    {
        msFrameIntervalRollingAverage.add(timer.milliseconds());
        timer.reset();
        double secondsPerFrame = msFrameIntervalRollingAverage.getMean() / 1000d;
        avgFps = (float) (1d/secondsPerFrame);
        Mat userProcessedFrame = null;

        int rotateCode = mapRotationEnumToOpenCvRotateCode(rotation);

        if(rotateCode != -1)
        {
            /*
             * Rotate onto another Mat rather than doing so in-place.
             *
             * This does two things:
             *     1) It seems that rotating by 90 or 270 in-place
             *        causes the backing buffer to be re-allocated
             *        since the width/height becomes swapped. This
             *        causes a problem for user code which makes a
             *        submat from the input Mat, because after the
             *        parent Mat is re-allocated the submat is no
             *        longer tied to it. Thus, by rotating onto
             *        another Mat (which is never re-allocated) we
             *        remove that issue.
             *
             *     2) Since the backing buffer does need need to be
             *        re-allocated for each frame, we reduce overhead
             *        time by about 1ms.
             */
            Core.rotate(frame, rotatedMat, rotateCode);
            frame = rotatedMat;
        }

        if(pipeline != null)
        {
            long pipelineStart = System.currentTimeMillis();
            userProcessedFrame = pipeline.processFrame(frame);
            msUserPipelineRollingAverage.add(System.currentTimeMillis() - pipelineStart);
        }

        if(viewport != null)
        {
            if(pipeline == null)
            {
                viewport.post(frame);
            }
            else if(userProcessedFrame == null)
            {
                /*
                 * Silly user, they returned null from their pipeline....
                 */
                throw new OpenCvCameraException("User pipeline returned null frame for viewport display");
            }
            else if(userProcessedFrame.cols() != frame.cols() || userProcessedFrame.rows() != frame.rows())
            {
                /*
                 * The user didn't return the same size image from their pipeline as we gave them,
                 * ugh. This makes our lives interesting because we can't just send an arbitrary
                 * frame size to the viewport. It re-uses framebuffers that are of a fixed resolution.
                 * So, we copy the user's Mat onto a Mat of the correct size, and then send that other
                 * Mat to the viewport.
                 */

                if(userProcessedFrame.cols() > frame.cols() || userProcessedFrame.rows() > frame.rows())
                {
                    /*
                     * What on earth was this user thinking?! They returned a Mat that's BIGGER in
                     * a dimension than the one we gave them!
                     */

                    throw new OpenCvCameraException("User pipeline returned frame of unexpected size");
                }

                //We re-use this buffer, only create if needed
                if(matToUseIfPipelineReturnedCropped == null)
                {
                    matToUseIfPipelineReturnedCropped = frame.clone();
                }

                //Set to brown to indicate to the user the areas which they cropped off
                matToUseIfPipelineReturnedCropped.setTo(brown);

                int usrFrmTyp = userProcessedFrame.type();

                if(usrFrmTyp == CvType.CV_8UC1)
                {
                    /*
                     * Handle 8UC1 returns (masks and single channels of images);
                     *
                     * We have to color convert onto a different mat (rather than
                     * doing so in place) to avoid breaking any of the user's submats
                     */
                    Imgproc.cvtColor(userProcessedFrame, croppedColorCvtedMat, Imgproc.COLOR_GRAY2RGBA);
                    userProcessedFrame = croppedColorCvtedMat; //Doesn't affect user's handle, only ours
                }
                else if(usrFrmTyp != CvType.CV_8UC4 && usrFrmTyp != CvType.CV_8UC3)
                {
                    /*
                     * Oof, we don't know how to handle the type they gave us
                     */
                    throw new OpenCvCameraException("User pipeline returned a frame of an illegal type. Valid types are CV_8UC1, CV_8UC3, and CV_8UC4");
                }

                //Copy the user's frame onto a Mat of the correct size
                userProcessedFrame.copyTo(matToUseIfPipelineReturnedCropped.submat(
                        new Rect(0,0,userProcessedFrame.cols(), userProcessedFrame.rows())));

                //Send that correct size Mat to the viewport
                viewport.post(matToUseIfPipelineReturnedCropped);
            }
            else
            {
                /*
                 * Yay, smart user! They gave us the frame size we were expecting!
                 * Go ahead and send it right on over to the viewport.
                 */
                viewport.post(userProcessedFrame);
            }
        }

        avgPipelineTime = (int) Math.round(msUserPipelineRollingAverage.getMean());
        avgTotalFrameTime = (int) Math.round(msTotalFrameProcessingTimeRollingAverage.getMean());
        avgOverheadTime = avgTotalFrameTime - avgPipelineTime;

        if(viewport != null)
        {
            viewport.notifyStatistics(avgFps, avgPipelineTime, avgOverheadTime);
        }

        frameCount++;

        msTotalFrameProcessingTimeRollingAverage.add(System.currentTimeMillis() - currentFrameStartTime);

        /*
         * For stream preview on DS
         */
        synchronized (bitmapFrameLock)
        {
            if (bitmapContinuation != null)
            {
                Mat matToCvt = null;

                if(userProcessedFrame == null)
                {
                    matToCvt = frame;
                }
                else
                {
                    matToCvt = userProcessedFrame;
                }

                final Bitmap bitmapFromMat = Bitmap.createBitmap(matToCvt.cols(), matToCvt.rows(), Bitmap.Config.RGB_565);

                Utils.matToBitmap(matToCvt, bitmapFromMat);

                if (bitmapFromMat != null)
                {
                    bitmapContinuation.dispatch(new ContinuationResult<Consumer<Bitmap>>()
                    {
                        @Override
                        public void handle(Consumer<Bitmap> bitmapConsumer)
                        {
                            bitmapConsumer.accept(bitmapFromMat);
                            bitmapFromMat.recycle();
                        }
                    });
                    bitmapContinuation = null;
                }
            }
        }
    }

    /*
     * For stream preview on DS
     */
    @Override
    public void getFrameBitmap(Continuation<? extends Consumer<Bitmap>> continuation)
    {
        synchronized (bitmapFrameLock)
        {
            bitmapContinuation = continuation;
        }
    }

    private void emulateEStop(Exception e)
    {
        RobotLog.ee("OpenCvCamera", e, "User code threw an uncaught exception");

        String errorMsg = e.getClass().getSimpleName() + (e.getMessage() != null ? " - " + e.getMessage() : "");
        RobotLog.setGlobalErrorMsg("User code threw an uncaught exception: " + errorMsg);

        OpModeManagerImpl mgr = OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().getActivity());
        mgr.initActiveOpMode(OpModeManagerImpl.DEFAULT_OP_MODE_NAME);

        try
        {
            Field eventLoopMgrField = OpModeManagerImpl.class.getDeclaredField("eventLoopManager");
            eventLoopMgrField.setAccessible(true);
            EventLoopManager eventLoopManager = (EventLoopManager) eventLoopMgrField.get(mgr);

            Method changeStateMethod = EventLoopManager.class.getDeclaredMethod("changeState", RobotState.class);
            changeStateMethod.setAccessible(true);
            changeStateMethod.invoke(eventLoopManager, RobotState.EMERGENCY_STOP);

            eventLoopManager.refreshSystemTelemetryNow();
        }
        catch (Exception e2)
        {
            e2.printStackTrace();
        }
    }

    @Override
    public int getFrameCount()
    {
        return frameCount;
    }

    @Override
    public float getFps()
    {
        return avgFps;
    }

    @Override
    public int getPipelineTimeMs()
    {
        return avgPipelineTime;
    }

    @Override
    public int getOverheadTimeMs()
    {
        return avgOverheadTime;
    }

    @Override
    public int getTotalFrameTimeMs()
    {
        return avgTotalFrameTime;
    }

    @Override
    public int getCurrentPipelineMaxFps()
    {
        if(avgTotalFrameTime != 0)
        {
            return 1000/avgTotalFrameTime;
        }
        else
        {
            return 0;
        }
    }

    private class OpModeNotifications implements LIFO_OpModeCallbackDelegate.OnOpModeStoppedListener
    {
        @Override
        public void onOpModePostStop(OpMode opMode)
        {
            /*
             * Closing the camera can take a while, so do it on another thread
             * so that there isn't visible "lag" when stopping an OpMode
             */
            new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    closeCameraDevice();
                }
            }).start();
        }
    }

    protected Size getFrameSizeAfterRotation(int width, int height, OpenCvCameraRotation rotation)
    {
        int screenRenderedWidth, screenRenderedHeight;
        int openCvRotateCode = mapRotationEnumToOpenCvRotateCode(rotation);

        if(openCvRotateCode == Core.ROTATE_90_CLOCKWISE || openCvRotateCode == Core.ROTATE_90_COUNTERCLOCKWISE)
        {
            //noinspection SuspiciousNameCombination
            screenRenderedWidth = height;
            //noinspection SuspiciousNameCombination
            screenRenderedHeight = width;
        }
        else
        {
            screenRenderedWidth = width;
            screenRenderedHeight = height;
        }

        return new Size(screenRenderedWidth, screenRenderedHeight);
    }

    protected abstract OpenCvCameraRotation getDefaultRotation();
    protected abstract int mapRotationEnumToOpenCvRotateCode(OpenCvCameraRotation rotation);
}
