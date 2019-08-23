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
import org.opencv.core.Mat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;

public abstract class OpenCvCameraBase implements OpenCvCamera, CameraStreamSource {
    private boolean isStreaming = false;
    private boolean isOpen = false;
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
        OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().getActivity()).registerListener(opModeNotifications);
    }

    public OpenCvCameraBase(int containerLayoutId)
    {
        this();

        setupViewport(containerLayoutId);
    }

    @Override
    public synchronized final void openCameraDevice()
    {
        if(!isOpen)
        {
            openCameraDeviceImplSpecific();

            isOpen = true;
        }
    }

    @Override
    public synchronized final void closeCameraDevice()
    {
        /*
         * Viewport might be initialized even if the
         * camera isn't open, because if the camera
         * open throws an exception, isOpen will still
         * be false (viewport is created before cam
         * is opened)
         */
        if(viewport != null)
        {
            removeViewportAsync();
        }

        if(isOpen)
        {
            closeCameraDeviceImplSpecific();

            isOpen = false;
        }
    }

    @Override
    public synchronized final void startStreaming(int width, int height)
    {
        startStreaming(width, height, getDefaultRotation());
    }

    @Override
    public synchronized final void startStreaming(int width, int height, OpenCvCameraRotation rotation)
    {
        if(!isOpen)
        {
            throw new OpenCvCameraException("startStreaming() called, but camera is not opened!");
        }

        if(isStreaming)
        {
            stopStreaming();
        }

        this.rotation = rotation;
        isStreaming = true;
        msFrameIntervalRollingAverage = new MovingStatistics(30);
        msUserPipelineRollingAverage = new MovingStatistics(30);
        msTotalFrameProcessingTimeRollingAverage = new MovingStatistics(30);
        timer = new ElapsedTime();

        if(viewport != null)
        {
            viewport.setSize(getFrameSizeAfterRotation(width, height, rotation));
            viewport.activate();
        }

        startStreamingImplSpecific(width, height);

        /*
         * For preview on DS
         */
        CameraStreamServer.getInstance().setSource(this);
    }

    @Override
    public synchronized final void stopStreaming()
    {
        if(!isOpen)
        {
            throw new OpenCvCameraException("stopStreaming() called, but camera is not opened!");
        }

        if(viewport != null)
        {
            viewport.deactivate();
        }

        stopStreamingImplSpecific();

        isStreaming = false;
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

    protected void handleFrameUserCrashable(Mat frame)
    {
        msFrameIntervalRollingAverage.add(timer.milliseconds());
        timer.reset();
        double secondsPerFrame = msFrameIntervalRollingAverage.getMean() / 1000d;
        avgFps = (float) (1d/secondsPerFrame);
        Mat userProcessedFrame = null;

        int rotateCode = mapRotationEnumToOpenCvRotateCode(rotation);

        if(rotateCode != -1)
        {
            Core.rotate(frame, frame, rotateCode);
        }

        if(pipeline != null)
        {
            long pipelineStart = System.currentTimeMillis();
            userProcessedFrame = pipeline.processFrame(frame);
            msUserPipelineRollingAverage.add(System.currentTimeMillis() - pipelineStart);
        }

        if(viewport != null)
        {
            if(pipeline != null)
            {
                if(userProcessedFrame == null)
                {
                    throw new OpenCvCameraException("User pipeline returned null frame for viewport display");
                }
                else if(userProcessedFrame.cols() != frame.cols() || userProcessedFrame.rows() != frame.rows())
                {
                    throw new OpenCvCameraException("User pipeline returned frame of unexpected size");
                }

                viewport.post(userProcessedFrame);
            }
            else
            {
                viewport.post(frame);
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

    private class OpModeNotifications implements OpModeManagerImpl.Notifications
    {
        @Override
        public void onOpModePreInit(OpMode opMode)
        {

        }

        @Override
        public void onOpModePreStart(OpMode opMode)
        {

        }

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

            OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().getActivity()).unregisterListener(this);
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

    protected abstract void openCameraDeviceImplSpecific();
    protected abstract void closeCameraDeviceImplSpecific();
    protected abstract void startStreamingImplSpecific(int width, int height);
    protected abstract OpenCvCameraRotation getDefaultRotation();
    protected abstract int mapRotationEnumToOpenCvRotateCode(OpenCvCameraRotation rotation);
    protected abstract void stopStreamingImplSpecific();
}
