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

import android.content.ComponentCallbacks;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.qualcomm.robotcore.eventloop.EventLoopManager;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier;
import com.qualcomm.robotcore.robot.RobotState;
import com.qualcomm.robotcore.util.ElapsedTime;
import com.qualcomm.robotcore.util.GlobalWarningSource;
import com.qualcomm.robotcore.util.MovingStatistics;
import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.robotcore.external.android.util.Size;
import org.firstinspires.ftc.robotcore.external.function.Consumer;
import org.firstinspires.ftc.robotcore.external.function.Continuation;
import org.firstinspires.ftc.robotcore.external.function.ContinuationResult;
import org.firstinspires.ftc.robotcore.external.stream.CameraStreamServer;
import org.firstinspires.ftc.robotcore.external.stream.CameraStreamSource;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;

public abstract class OpenCvCameraBase implements OpenCvCamera, CameraStreamSource, GlobalWarningSource
{
    private OpenCvPipeline pipeline = null;
    private LinearLayout viewportContainerLayout;
    private MovingStatistics msFrameIntervalRollingAverage;
    private MovingStatistics msUserPipelineRollingAverage;
    private MovingStatistics msTotalFrameProcessingTimeRollingAverage;
    private ElapsedTime timer;
    private OpenCvViewport viewport;
    private int containerLayoutId;
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
    private Scalar brown = new Scalar(82, 61, 46, 255);
    private OpModeNotificationsForOrientation opModeNotificationsForOrientation= new OpModeNotificationsForOrientation();
    private ComponentCallbacksForRotation componentCallbacksForRotation = new ComponentCallbacksForRotation();
    private volatile boolean hasBeenCleanedUp = false;
    private final Object pipelineChangeLock = new Object();
    private final Object viewportLock = new Object();
    private MediaRecorder mediaRecorder;
    private Surface mediaRecorderSurface;
    private long mediaRecorderSurfaceNativeHandle;
    private int width;
    private int height;

    ViewportRenderer desiredViewportRenderer = ViewportRenderer.SOFTWARE;
    ViewportRenderingPolicy desiredRenderingPolicy = ViewportRenderingPolicy.MAXIMIZE_EFFICIENCY;
    boolean fpsMeterDesired = true;

    /*
     * NOTE: We cannot simply pass `new OpModeNotifications()` inline to the call
     * to register the listener, because the SDK stores the list of listeners in
     * a WeakReference set. This causes the object to be garbage collected because
     * nothing else is holding a reference to it.
     */
    private OpModeNotifications opModeNotifications = new OpModeNotifications();

    public OpenCvCameraBase()
    {
        System.out.println("OpenCvCameraBase ctor: EasyOpenCV version " + BuildConfig._VERSION_NAME);

        frameCount = 0;
        LIFO_OpModeCallbackDelegate.getInstance().add(opModeNotifications);
        RobotLog.registerGlobalWarningSource(this);


        /*
         * For preview on DS
         *
         * Note: this used to be done in prepareForStartStreaming() but now that we
         * close syncronously in the opmode listener, this could cause a deadlock
         * there if the opmode was asyncronously opening and starting streaming while
         * the user pressed the stop button.
         */
        CameraStreamServer.getInstance().setSource(this);
    }

    public OpenCvCameraBase(int containerLayoutId)
    {
        this();

        this.containerLayoutId = containerLayoutId;

        AppUtil.getInstance().getApplication().registerComponentCallbacks(componentCallbacksForRotation);
        OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().getActivity()).registerListener(opModeNotificationsForOrientation);
    }

    public final void cleanupForClosingCamera()
    {
        synchronized (viewportLock)
        {
            if(viewport != null)
            {
                removeViewportAsync((View)viewport);
                viewport = null;
            }
        }
    }

    public synchronized boolean hasBeenCleanedUp()
    {
        return hasBeenCleanedUp;
    }

    public synchronized final void prepareForOpenCameraDevice()
    {
        if (containerLayoutId != 0 && viewport == null)
        {
            setupViewport(containerLayoutId);
            viewport.setRenderingPolicy(desiredRenderingPolicy);
        }
    }

    public synchronized final void prepareForStartStreaming(int width, int height, OpenCvCameraRotation rotation)
    {
        this.rotation = rotation;
        msFrameIntervalRollingAverage = new MovingStatistics(30);
        msUserPipelineRollingAverage = new MovingStatistics(30);
        msTotalFrameProcessingTimeRollingAverage = new MovingStatistics(30);
        timer = new ElapsedTime();

        Size sizeAfterRotation = getFrameSizeAfterRotation(width, height, rotation);

        this.width = sizeAfterRotation.getWidth();
        this.height = sizeAfterRotation.getHeight();

        if(viewport != null)
        {
            viewport.setSize(width, height);
            viewport.setOptimizedViewRotation(getOptimizedViewportRotation(rotation, AppUtil.getInstance().getActivity().getWindowManager().getDefaultDisplay().getRotation()));
            viewport.activate();
        }
    }

    public synchronized final void cleanupForEndStreaming()
    {
        if(mediaRecorder != null)
        {
            stopRecordingPipeline();
        }

        matToUseIfPipelineReturnedCropped = null;

        if(viewport != null)
        {
            viewport.deactivate();
        }
    }

    @Override
    public final void pauseViewport()
    {
        synchronized (viewportLock)
        {
            if(viewport != null)
            {
                viewport.pause();
            }
        }
    }

    @Override
    public final void resumeViewport()
    {
        synchronized (viewportLock)
        {
            if(viewport != null)
            {
                viewport.resume();
            }
        }
    }

    @Override
    public final void showFpsMeterOnViewport(boolean show)
    {
        synchronized (viewportLock)
        {
            fpsMeterDesired = show;

            if(viewport != null)
            {
                viewport.setFpsMeterEnabled(show);
            }
        }
    }

    @Override // NOT synchronized to avoid blocking if pipeline is slow
    public final void setPipeline(OpenCvPipeline pipeline)
    {
        synchronized (pipelineChangeLock)
        {
            this.pipeline = pipeline;
        }
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

                    View.OnClickListener onClickListener = new View.OnClickListener()
                    {
                        @Override
                        public void onClick(View v)
                        {
                            synchronized (pipelineChangeLock)
                            {
                                if(pipeline != null)
                                {
                                    pipeline.onViewportTapped();
                                }
                            }
                        }
                    };

                    synchronized (viewportLock)
                    {
                        switch (desiredViewportRenderer)
                        {
                            case SOFTWARE:
                            case GPU_ACCELERATED:
                            {
                                viewport = new OpenCvSurfaceViewViewport(AppUtil.getInstance().getActivity(), onClickListener);
                                ((OpenCvSurfaceViewViewport)viewport).setUseGpuCanvas(desiredViewportRenderer == ViewportRenderer.GPU_ACCELERATED);
                                break;
                            }

                            case NATIVE_VIEW:
                            {
                                viewport = new OpenCvNativeViewViewport(AppUtil.getInstance().getActivity(), onClickListener);
                                break;
                            }
                        }

                        viewport.setFpsMeterEnabled(fpsMeterDesired);
                        viewport.setRenderHook(new OpenCvViewport.RenderHook()
                        {
                            @Override
                            public void onDrawFrame(Canvas canvas, int onscreenWidth, int onscreenHeight, float scaleBmpPxToCanvasPx, float scaleCanvasDensity, Object context)
                            {
                                OpenCvViewport.FrameContext frameContext = (OpenCvViewport.FrameContext) context;

                                // We must make sure that we call onDrawFrame() for the same pipeline which set the
                                // context object when requesting a draw hook. (i.e. we can't just call onDrawFrame()
                                // for whatever pipeline happens to be currently attached; it might have an entirely
                                // different notion of what to expect in the context object)
                                if (frameContext.generatingPipeline != null)
                                {
                                    frameContext.generatingPipeline.onDrawFrame(canvas, onscreenWidth, onscreenHeight, scaleBmpPxToCanvasPx, scaleCanvasDensity, frameContext.userContext);
                                }
                            }
                        });

                        viewport.setSize(320, 240);

                        ((View)viewport).setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                        viewportContainerLayout.setVisibility(View.VISIBLE);
                        viewportContainerLayout.addView((View)viewport);
                    }

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

            synchronized (viewportLock)
            {
                viewport = null;
            }
        }
    }

    private void removeViewportAsync(final View viewport)
    {
        AppUtil.getInstance().runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                viewportContainerLayout.removeView(viewport);
                viewportContainerLayout.setVisibility(View.GONE);
            }
        });
    }

    protected void notifyStartOfFrameProcessing()
    {
        currentFrameStartTime = System.currentTimeMillis();
    }

    @Override
    public synchronized void startRecordingPipeline(PipelineRecordingParameters parameters)
    {
        System.out.println("startRecordingPipeline()");

        try
        {
            if(!isStreaming())
            {
                throw new IllegalStateException("A recording session may only be started once a streaming session is already in flight");
            }

            if(mediaRecorder != null)
            {
                throw new IllegalStateException("A recording session is already ongoing!");
            }

            mediaRecorderSurface = MediaCodec.createPersistentInputSurface();
            mediaRecorderSurfaceNativeHandle = nativeGetSurfaceHandle(mediaRecorderSurface);

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setInputSurface(mediaRecorderSurface);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(parameters.outputFormat.format);
            mediaRecorder.setVideoSize(width, height);
            mediaRecorder.setVideoEncoder(parameters.encoder.format);
            mediaRecorder.setVideoEncodingBitRate(parameters.bitrate);
            mediaRecorder.setOutputFile(parameters.path);
            mediaRecorder.setCaptureRate(parameters.frameRate);
            mediaRecorder.prepare();
            mediaRecorder.start();

            if(viewport != null)
            {
                viewport.setRecording(true);
            }
        }
        catch (IOException e)
        {
            nativeReleaseSurfaceHandle(mediaRecorderSurfaceNativeHandle);
            mediaRecorderSurfaceNativeHandle = 0;
            mediaRecorderSurface.release();
            mediaRecorderSurface = null;
            mediaRecorder = null;

            throw new OpenCvCameraException("Unable to begin recording");
        }
        catch (Exception e)
        {
            nativeReleaseSurfaceHandle(mediaRecorderSurfaceNativeHandle);
            mediaRecorderSurfaceNativeHandle = 0;
            mediaRecorderSurface.release();
            mediaRecorderSurface = null;
            mediaRecorder = null;

            throw e;
        }
        finally
        {
            System.out.println("...startRecordingPipeline()");
        }
    }

    @Override
    public synchronized void stopRecordingPipeline()
    {
        System.out.println("stopRecordingPipeline()");
        try
        {
            if(mediaRecorder != null)
            {
                mediaRecorder.stop();
                nativeReleaseSurfaceHandle(mediaRecorderSurfaceNativeHandle);
                mediaRecorderSurfaceNativeHandle = 0;
                mediaRecorderSurface.release();
                mediaRecorderSurface = null;
                mediaRecorder = null;

                if(viewport != null)
                {
                    viewport.setRecording(false);
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        finally
        {
            System.out.println("...stopRecordingPipeline()");
        }
    }

    protected synchronized void handleFrame(Mat frame, long timestamp)
    {
        try
        {
            handleFrameUserCrashable(frame, timestamp);
        }
        catch (Exception e)
        {
            emulateEStop(e);
        }
    }

    protected synchronized void handleFrameUserCrashable(Mat frame, long timestamp)
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

        final OpenCvPipeline pipelineSafe;

        // Grab a safe reference to what the pipeline currently is,
        // since the user is allowed to change it at any time
        synchronized (pipelineChangeLock)
        {
            pipelineSafe = pipeline;
        }

        if(pipelineSafe != null)
        {
            if(pipelineSafe instanceof TimestampedOpenCvPipeline)
            {
                ((TimestampedOpenCvPipeline) pipelineSafe).setTimestamp(timestamp);
            }

            long pipelineStart = System.currentTimeMillis();
            userProcessedFrame = pipelineSafe.processFrameInternal(frame);
            msUserPipelineRollingAverage.add(System.currentTimeMillis() - pipelineStart);
        }

        // Will point to whatever mat we end up deciding to send to the screen
        final Mat matForDisplay;

        if (pipelineSafe == null)
        {
            matForDisplay = frame;
        }
        else if(userProcessedFrame == null)
        {
            throw new OpenCvCameraException("User pipeline returned null");
        }
        else if(userProcessedFrame.empty())
        {
            throw new OpenCvCameraException("User pipeline returned empty mat");
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
            matForDisplay = matToUseIfPipelineReturnedCropped;
        }
        else
        {
            /*
             * Yay, smart user! They gave us the frame size we were expecting!
             * Go ahead and send it right on over to the viewport.
             */
            matForDisplay = userProcessedFrame;
        }

        if(mediaRecorder != null)
        {
            nativeCopyMatToSurface(mediaRecorderSurfaceNativeHandle, matForDisplay.nativeObj);
        }

        if (viewport != null)
        {
            viewport.post(matForDisplay, new OpenCvViewport.FrameContext(pipelineSafe, pipelineSafe != null ? pipelineSafe.getUserContextForDrawHook() : null));
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
                OpenCvViewRenderer renderer = new OpenCvViewRenderer(AppUtil.getInstance().getActivity(), true);
                OpenCvViewport.OptimizedRotation optimizedRotation = getOptimizedViewportRotation(rotation, AppUtil.getInstance().getActivity().getWindowManager().getDefaultDisplay().getRotation());
                renderer.setOptimizedViewRotation(optimizedRotation);

                renderer.notifyStatistics(avgFps, avgPipelineTime, avgOverheadTime);
                renderer.setRenderingPolicy(desiredRenderingPolicy);

                final Bitmap bitmapForDs;

                if (pipelineSafe != null)
                {
                    // We try to exactly re-render the local view using an offscreen canvas so that the DS preview
                    // looks just like the local one. But, we use a fixed resolution rather than trying to match the
                    // local canvas resolution.

                    int fixedWidth = 1280;
                    int fixedHeight = 720;

                    // However, we don't want to waste space with black borders, so we crop down that fixed resolution
                    // to be the same aspect ratio as the target image.
                    float imageAspect = (float) matForDisplay.width() / matForDisplay.height();
                    float fixedAspect = (float) fixedWidth / fixedHeight;

                    if (imageAspect > fixedAspect) /* Image is WIDER than canvas */
                    {
                        fixedHeight = (int) Math.round(fixedWidth / imageAspect);
                    }
                    else /* Image is TALLER than canvas */
                    {
                        fixedWidth = (int) Math.round(fixedHeight * imageAspect);
                    }

                    // If the image is going to be rotated, then swap the width and height of the fixed res bitmap used to back the canvas
                    if (desiredRenderingPolicy == ViewportRenderingPolicy.OPTIMIZE_VIEW && (optimizedRotation == OpenCvViewport.OptimizedRotation.ROT_90_CLOCKWISE || optimizedRotation == OpenCvViewport.OptimizedRotation.ROT_90_COUNTERCLOCWISE))
                    {
                        int tmp = fixedWidth;
                        fixedWidth = fixedHeight;
                        fixedHeight = tmp;
                    }

                    bitmapForDs = Bitmap.createBitmap(fixedWidth, fixedHeight, Bitmap.Config.RGB_565);
                    renderer.setFpsMeterEnabled(fpsMeterDesired);

                    Canvas canvas = new Canvas(bitmapForDs);

                    renderer.render(
                            matForDisplay,
                            canvas,
                            new OpenCvViewport.RenderHook()
                            {
                                @Override
                                public void onDrawFrame(Canvas canvas, int onscreenWidth, int onscreenHeight, float scaleBmpPxToCanvasPx, float canvasDensityScale, Object userContext)
                                {
                                    pipelineSafe.onDrawFrame(canvas, onscreenWidth, onscreenHeight, scaleBmpPxToCanvasPx, canvasDensityScale, userContext);
                                }
                            },
                            pipelineSafe.getUserContextForDrawHook()
                    );
                }
                else
                {
                    bitmapForDs = Bitmap.createBitmap(matForDisplay.cols(), matForDisplay.rows(), Bitmap.Config.RGB_565);
                    Utils.matToBitmap(matForDisplay, bitmapForDs);
                }

                bitmapContinuation.dispatch(new ContinuationResult<Consumer<Bitmap>>()
                {
                    @Override
                    public void handle(Consumer<Bitmap> bitmapConsumer)
                    {
                        bitmapConsumer.accept(bitmapForDs);
                        bitmapForDs.recycle();
                    }
                });
                bitmapContinuation = null;
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

    protected void emulateEStop(Exception e)
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

    @Override
    public void setViewportRenderingPolicy(ViewportRenderingPolicy policy)
    {
        synchronized (viewportLock)
        {
            if(!cameraOrientationIsTiedToDeviceOrientation())
            {
                RobotLog.addGlobalWarningMessage("Setting viewport rendering policy is not applicable for this type of camera - ignoring.");
            }
            else
            {
                if (viewport == null)
                {
                    desiredRenderingPolicy = policy;
                }
                else
                {
                    throw new IllegalStateException("setViewportRenderingPolicy() must only be called before opening the camera.");
                }
            }
        }
    }

    @Override
    public synchronized void setViewportRenderer(ViewportRenderer renderer)
    {
        if(viewport != null)
        {
            throw new IllegalStateException("setViewportRenderer() must only be called before opening the camera.");
        }

        desiredViewportRenderer = renderer;
    }

    private class OpModeNotificationsForOrientation implements OpModeManagerNotifier.Notifications
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
            AppUtil.getInstance().getApplication().unregisterComponentCallbacks(componentCallbacksForRotation);

            OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().getActivity()).unregisterListener(this);
        }
    }

    @Override
    public String getGlobalWarning()
    {
        synchronized (pipelineChangeLock)
        {
            if(pipeline != null)
            {
                return pipeline.getLeakMsg();
            }
            else
            {
                return "";
            }
        }
    }

    @Override
    public boolean shouldTriggerWarningSound()
    {
        return false;
    }

    @Override
    public void suppressGlobalWarning(boolean suppress)
    {

    }

    @Override
    public void setGlobalWarning(String warning)
    {

    }

    @Override
    public void clearGlobalWarning()
    {

    }

    private class ComponentCallbacksForRotation implements ComponentCallbacks
    {
        @Override
        public void onConfigurationChanged(Configuration newConfig)
        {
            int displayRot = AppUtil.getInstance().getActivity().getWindowManager().getDefaultDisplay().getRotation();

            synchronized (viewportLock)
            {
                if(viewport != null)
                {
                    viewport.setOptimizedViewRotation(getOptimizedViewportRotation(rotation, displayRot));
                }
            }
        }

        @Override
        public void onLowMemory()
        {

        }
    }

    private class OpModeNotifications implements LIFO_OpModeCallbackDelegate.OnOpModeStoppedListener
    {
        @Override
        public void onOpModePostStop(OpMode opMode)
        {
            hasBeenCleanedUp = true;

            RobotLog.unregisterGlobalWarningSource(OpenCvCameraBase.this);

            closeCameraDevice();
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

    protected OpenCvViewport.OptimizedRotation getOptimizedViewportRotation(OpenCvCameraRotation streamRotation, int windowRotation)
    {
        if(!cameraOrientationIsTiedToDeviceOrientation())
        {
            return OpenCvViewport.OptimizedRotation.NONE;
        }

        if(windowRotation == Surface.ROTATION_0)
        {
            if(streamRotation == OpenCvCameraRotation.SIDEWAYS_LEFT || streamRotation == OpenCvCameraRotation.SENSOR_NATIVE)
            {
                return OpenCvViewport.OptimizedRotation.ROT_90_COUNTERCLOCWISE;
            }
            else if(streamRotation == OpenCvCameraRotation.SIDEWAYS_RIGHT)
            {
                return OpenCvViewport.OptimizedRotation.ROT_90_CLOCKWISE;
            }
            else if(streamRotation == OpenCvCameraRotation.UPSIDE_DOWN)
            {
                return OpenCvViewport.OptimizedRotation.ROT_180;
            }
            else
            {
                return OpenCvViewport.OptimizedRotation.NONE;
            }
        }
        else if(windowRotation == Surface.ROTATION_90)
        {
            if(streamRotation == OpenCvCameraRotation.SIDEWAYS_RIGHT)
            {
                return OpenCvViewport.OptimizedRotation.ROT_180;
            }
            else if(streamRotation == OpenCvCameraRotation.UPRIGHT)
            {
                return OpenCvViewport.OptimizedRotation.ROT_90_CLOCKWISE;
            }
            else if(streamRotation == OpenCvCameraRotation.UPSIDE_DOWN)
            {
                return OpenCvViewport.OptimizedRotation.ROT_90_COUNTERCLOCWISE;
            }
            else
            {
                return OpenCvViewport.OptimizedRotation.NONE;
            }
        }
        else if(windowRotation == Surface.ROTATION_270)
        {
            if(streamRotation == OpenCvCameraRotation.SIDEWAYS_LEFT || streamRotation == OpenCvCameraRotation.SENSOR_NATIVE)
            {
                return OpenCvViewport.OptimizedRotation.ROT_180;
            }
            else if(streamRotation == OpenCvCameraRotation.UPRIGHT)
            {
                return OpenCvViewport.OptimizedRotation.ROT_90_COUNTERCLOCWISE;
            }
            else if(streamRotation == OpenCvCameraRotation.UPSIDE_DOWN)
            {
                return OpenCvViewport.OptimizedRotation.ROT_90_CLOCKWISE;
            }
            else
            {
                return OpenCvViewport.OptimizedRotation.NONE;
            }
        }
        else
        {
            return OpenCvViewport.OptimizedRotation.NONE;
        }
    }

    protected abstract OpenCvCameraRotation getDefaultRotation();
    protected abstract int mapRotationEnumToOpenCvRotateCode(OpenCvCameraRotation rotation);
    protected abstract boolean cameraOrientationIsTiedToDeviceOrientation();
    protected abstract boolean isStreaming();

    private native long nativeGetSurfaceHandle(Surface surface);
    private native void nativeReleaseSurfaceHandle(long handle);
    private native void nativeCopyMatToSurface(long handle, long matPtr);

    static
    {
        System.loadLibrary("EasyOpenCV");
    }
}
