/*
 * Copyright (c) 2018 OpenFTC Team
 *
 * Note: credit where credit is due - some parts of OpenCv's
 *       JavaCameraView were used as a reference
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

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import org.firstinspires.ftc.robotcore.external.android.util.Size;
import org.firstinspires.ftc.robotcore.external.function.Consumer;
import org.firstinspires.ftc.robotcore.internal.collections.EvictingBlockingQueue;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

public class OpenCvViewport extends SurfaceView implements SurfaceHolder.Callback
{
    private Size size;
    private Bitmap bitmapFromMat;
    private RenderThread renderThread;
    private Canvas canvas = null;
    private double aspectRatio;
    private static final int VISION_PREVIEW_FRAME_QUEUE_CAPACITY = 2;
    private static final int FRAMEBUFFER_RECYCLER_CAPACITY = VISION_PREVIEW_FRAME_QUEUE_CAPACITY + 2; //So that the evicting queue can be full, and the render thread has one checked out (+1) and post() can still take one (+1).
    private EvictingBlockingQueue<MatRecycler.RecyclableMat> visionPreviewFrameQueue = new EvictingBlockingQueue<>(new ArrayBlockingQueue<MatRecycler.RecyclableMat>(VISION_PREVIEW_FRAME_QUEUE_CAPACITY));
    private MatRecycler framebufferRecycler;
    private volatile RenderingState internalRenderingState = RenderingState.STOPPED;
    private final Object syncObj = new Object();
    private volatile boolean userRequestedActive = false;
    private volatile boolean userRequestedPause = false;
    private boolean needToDeactivateRegardlessOfUser = false;
    private boolean surfaceExistsAndIsReady = false;
    private Paint fpsMeterBgPaint;
    private Paint fpsMeterTextPaint;
    private boolean fpsMeterEnabled = true;
    private float fps = 0;
    private int pipelineMs = 0;
    private int overheadMs = 0;
    private String TAG = "OpenCvViewport";
    private ReentrantLock renderThreadAliveLock = new ReentrantLock();

    public OpenCvViewport(Context context, OnClickListener onClickListener)
    {
        super(context);

        fpsMeterBgPaint = new Paint();
        fpsMeterBgPaint.setColor(Color.rgb(102, 20, 68));
        fpsMeterBgPaint.setStyle(Paint.Style.FILL);

        fpsMeterTextPaint = new Paint();
        fpsMeterTextPaint.setColor(Color.WHITE);
        fpsMeterTextPaint.setTextSize(30);

        getHolder().addCallback(this);

        visionPreviewFrameQueue.setEvictAction(new Consumer<MatRecycler.RecyclableMat>()
        {
            @Override
            public void accept(MatRecycler.RecyclableMat value)
            {
                /*
                 * If a Mat is evicted from the queue, we need
                 * to make sure to return it to the Mat recycler
                 */
                framebufferRecycler.returnMat(value);
            }
        });

        setOnClickListener(onClickListener);
    }

    private enum RenderingState
    {
        STOPPED,
        ACTIVE,
        PAUSED,
    }

    public void setSize(Size size)
    {
        synchronized (syncObj)
        {
            if(internalRenderingState != RenderingState.STOPPED)
            {
                throw new IllegalStateException("Cannot set size while renderer is active!");
            }

            //did they give us null?
            if(size == null)
            {
                //ugh, they did
                throw new IllegalArgumentException("size cannot be null!");
            }

            this.size = size;
            this.aspectRatio = (double)size.getWidth() / (double)size.getHeight();

            //Make sure we don't have any mats hanging around
            //from when we might have been running before
            visionPreviewFrameQueue.clear();

            framebufferRecycler = new MatRecycler(FRAMEBUFFER_RECYCLER_CAPACITY);
        }
    }

    public void post(Mat mat)
    {
        synchronized (syncObj)
        {
            //did they give us null?
            if(mat == null)
            {
                //ugh, they did
                throw new IllegalArgumentException("cannot post null mat!");
            }

            //Are we actually rendering to the display right now? If not,
            //no need to waste time doing a memcpy
            if(internalRenderingState == RenderingState.ACTIVE)
            {
                /*
                 * We need to copy this mat before adding it to the queue,
                 * because the pointer that was passed in here is only known
                 * to be pointing to a certain frame while we're executing.
                 */
                try
                {
                    /*
                     * Grab a framebuffer Mat from the recycler
                     * instead of doing a new alloc and then having
                     * to free it after rendering/eviction from queue
                     */
                    MatRecycler.RecyclableMat matToCopyTo = framebufferRecycler.takeMat();
                    mat.copyTo(matToCopyTo);
                    visionPreviewFrameQueue.offer(matToCopyTo);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /*
     * Called with syncObj held
     */
    public void checkState()
    {
        /*
         * If the surface isn't ready, don't do anything
         */
        if(!surfaceExistsAndIsReady)
        {
            Log.d(TAG, "CheckState(): surface not ready or doesn't exist");
            return;
        }

        /*
         * Does the user want us to stop?
         */
        if(!userRequestedActive || needToDeactivateRegardlessOfUser)
        {
            Log.d(TAG, "CheckState(): user requested that we deactivate");

            /*
             * We only need to stop the render thread if it's not
             * already stopped
             */
            if(internalRenderingState != RenderingState.STOPPED)
            {
                Log.d(TAG, "CheckState(): deactivating viewport");

                /*
                 * Interrupt him so he's not stuck looking at his
                 * frame queue.
                 */
                renderThread.notifyExitRequested();
                renderThread.interrupt();

                /*
                 * Wait for him to die non-interuptibly
                 */
                renderThreadAliveLock.lock();
                renderThreadAliveLock.unlock();

//                try
//                {
//                    /*
//                     * Wait for him to die
//                     */
//                    renderThread.join();
//                }
//                catch (InterruptedException e)
//                {
//                    e.printStackTrace();
//                }

                internalRenderingState = RenderingState.STOPPED;
            }
            else
            {
                Log.d(TAG, "CheckState(): already deactivated");
            }
        }

        /*
         * Does the user want us to start?
         */
        else if(userRequestedActive)
        {
            Log.d(TAG, "CheckState(): user requested that we activate");

            /*
             * We only need to start the render thread if it's
             * stopped.
             */
            if(internalRenderingState == RenderingState.STOPPED)
            {
                Log.d(TAG, "CheckState(): activating viewport");

                internalRenderingState = RenderingState.PAUSED;

                if(userRequestedPause)
                {
                    internalRenderingState = RenderingState.PAUSED;
                }
                else
                {
                    internalRenderingState = RenderingState.ACTIVE;
                }

                renderThread = new RenderThread();
                renderThread.start();
            }
            else
            {
                Log.d(TAG, "CheckState(): already activated");
            }
        }

        if(internalRenderingState != RenderingState.STOPPED)
        {
            if(userRequestedPause && internalRenderingState != RenderingState.PAUSED
                    || !userRequestedPause && internalRenderingState != RenderingState.ACTIVE)
            {
                if(userRequestedPause)
                {
                    Log.d(TAG, "CheckState(): pausing viewport");
                    internalRenderingState = RenderingState.PAUSED;
                }
                else
                {
                    Log.d(TAG, "CheckState(): resuming viewport");
                    internalRenderingState = RenderingState.ACTIVE;
                }

                /*
                 * Interrupt him so that he's not stuck looking at his frame queue.
                 * (We stop filling the frame queue if the user requested pause so
                 * we aren't doing pointless memcpys)
                 */
                renderThread.interrupt();
            }
        }
    }

    /***
     * Activate the render thread
     */
    public synchronized void activate()
    {
        synchronized (syncObj)
        {
            userRequestedActive = true;
            checkState();
        }
    }

    /***
     * Deactivate the render thread
     */
    public synchronized void deactivate()
    {
        synchronized (syncObj)
        {
            userRequestedActive = false;
            checkState();
        }
    }

    public synchronized void resume()
    {
        synchronized (syncObj)
        {
            userRequestedPause = false;
            checkState();
        }
    }

    public synchronized void pause()
    {
        synchronized (syncObj)
        {
            userRequestedPause = true;
            checkState();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder)
    {

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
    {
        synchronized (syncObj)
        {
            needToDeactivateRegardlessOfUser = false;
            surfaceExistsAndIsReady = true;

            checkState();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder)
    {
        synchronized (syncObj)
        {
            needToDeactivateRegardlessOfUser = true;
            checkState();
            surfaceExistsAndIsReady = false;
        }

    }

    public void setFpsMeterEnabled(boolean fpsMeterEnabled)
    {
        this.fpsMeterEnabled = fpsMeterEnabled;
    }

    public void notifyStatistics(float fps, int pipelineMs, int overheadMs)
    {
        this.fps = fps;
        this.pipelineMs = pipelineMs;
        this.overheadMs = overheadMs;
    }

    class RenderThread extends Thread
    {
        boolean shouldPaintOrange = true;
        volatile boolean exitRequested = false;
        private String TAG = "OpenCvViewportRenderThread";

        public void notifyExitRequested()
        {
            exitRequested = true;
        }

        @Override
        public void run()
        {
            renderThreadAliveLock.lock();

            //Make sure we don't have any mats hanging around
            //from when we might have been running before
            visionPreviewFrameQueue.clear();

            Log.d(TAG, "I am alive!");

            bitmapFromMat = Bitmap.createBitmap(size.getWidth(), size.getHeight(), Bitmap.Config.RGB_565);

            canvas = getHolder().lockCanvas();
            canvas.drawColor(Color.BLUE);
            getHolder().unlockCanvasAndPost(canvas);

            while (true)
            {
                /*
                 * Do we need to exit?
                 */
                if(exitRequested)
                {
                    break;
                }

                switch (internalRenderingState)
                {
                    case ACTIVE:
                    {
                        shouldPaintOrange = true;

                        MatRecycler.RecyclableMat mat;

                        try
                        {
                            //Grab a Mat from the frame queue
                            mat = visionPreviewFrameQueue.take();
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                            break;
                        }

                        //Get canvas object for rendering on
                        canvas = getHolder().lockCanvas();

                        /*
                         * For some reason, the canvas will very occasionally be null upon closing.
                         * Stack Overflow seems to suggest this means the canvas has been destroyed.
                         * However, surfaceDestroyed(), which is called right before the surface is
                         * destroyed, calls checkState(), which *SHOULD* block until we die. This
                         * works most of the time, but not always? We don't yet understand...
                         */
                        if(canvas != null)
                        {
                            //Convert that Mat to a bitmap we can render
                            Utils.matToBitmap(mat, bitmapFromMat);

                            //Draw the background black each time to prevent double buffering problems
                            canvas.drawColor(Color.BLACK);

                            //Landscape
                            if((canvas.getHeight() * aspectRatio) < canvas.getWidth())
                            {
                                //Draw the bitmap, scaling it to the maximum size that will fit in the viewport
                                canvas.drawBitmap(
                                        bitmapFromMat,
                                        new Rect(0,0,bitmapFromMat.getWidth(), bitmapFromMat.getHeight()),
                                        new Rect(0,0,(int) Math.round(canvas.getHeight() * aspectRatio), canvas.getHeight()),
                                        null);
                            }
                            //Portrait
                            else
                            {
                                //Draw the bitmap, scaling it to the maximum size that will fit in the viewport
                                canvas.drawBitmap(
                                        bitmapFromMat,
                                        new Rect(0,0,bitmapFromMat.getWidth(), bitmapFromMat.getHeight()),
                                        new Rect(0,0,canvas.getWidth(), (int) Math.round(canvas.getWidth() / aspectRatio)),
                                        null);
                            }

                            if(fpsMeterEnabled)
                            {
                                canvas.drawRect(0, canvas.getHeight()-120, 450, canvas.getHeight(), fpsMeterBgPaint);
                                canvas.drawText("OpenFTC EasyOpenCV v" + BuildConfig.VERSION_NAME, 5, canvas.getHeight() - 80, fpsMeterTextPaint);
                                canvas.drawText(getFpsString(), 5, canvas.getHeight() - 45, fpsMeterTextPaint);
                                canvas.drawText("Pipeline: " + pipelineMs + "ms" + " - Overhead: " + overheadMs + "ms", 5, canvas.getHeight() - 10, fpsMeterTextPaint);
                            }

                            getHolder().unlockCanvasAndPost(canvas);
                        }
                        else
                        {
                            Log.d(TAG, "Canvas was null");
                        }

                        //We're done with that Mat object; return it to the Mat recycler so it can be used again later
                        framebufferRecycler.returnMat(mat);

                        break;
                    }

                    case PAUSED:
                    {
                        if(shouldPaintOrange)
                        {
                            shouldPaintOrange = false;

                            canvas = getHolder().lockCanvas();

                            /*
                             * For some reason, the canvas will very occasionally be null upon closing.
                             * Stack Overflow seems to suggest this means the canvas has been destroyed.
                             * However, surfaceDestroyed(), which is called right before the surface is
                             * destroyed, calls checkState(), which *SHOULD* block until we die. This
                             * works most of the time, but not always? We don't yet understand...
                             */
                            if(canvas != null)
                            {
                                canvas.drawColor(Color.rgb(255, 166, 0));
                                canvas.drawRect(0, canvas.getHeight()-40, 450, canvas.getHeight(), fpsMeterBgPaint);
                                canvas.drawText("VIEWPORT PAUSED", 5, canvas.getHeight()-10, fpsMeterTextPaint);
                                getHolder().unlockCanvasAndPost(canvas);
                            }
                        }

                        try
                        {
                            Thread.sleep(50);
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                        }
                        break;
                    }
                }
            }

            Log.d(TAG, "About to exit");
            bitmapFromMat.recycle(); //Help the garbage collector :)
            renderThreadAliveLock.unlock();
        }
    }

    @SuppressLint("DefaultLocale")
    public String getFpsString()
    {
        return String.format("FPS@%dx%d: %.2f", size.getWidth(), size.getHeight(), fps);
    }
}