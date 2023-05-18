/*
 * Copyright (c) 2023 OpenFTC Team
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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.firstinspires.ftc.robotcore.external.function.Consumer;
import org.firstinspires.ftc.robotcore.internal.collections.EvictingBlockingQueue;
import org.opencv.core.Mat;

import java.util.concurrent.ArrayBlockingQueue;

public class OpenCvNativeViewViewport extends View implements OpenCvViewport
{
    private final OpenCvViewRenderer renderer;

    private static final int VISION_PREVIEW_FRAME_QUEUE_CAPACITY = 2;
    private static final int FRAMEBUFFER_RECYCLER_CAPACITY = VISION_PREVIEW_FRAME_QUEUE_CAPACITY + 2; //So that the evicting queue can be full, and the render thread has one checked out (+1) and post() can still take one (+1).
    private final EvictingBlockingQueue<MatRecycler.RecyclableMat> visionPreviewFrameQueue = new EvictingBlockingQueue<>(new ArrayBlockingQueue<MatRecycler.RecyclableMat>(VISION_PREVIEW_FRAME_QUEUE_CAPACITY));
    private MatRecycler framebufferRecycler;

    private volatile boolean active = false;
    private volatile boolean wasJustActivated;
    private volatile boolean paused = false;

    private final Object activeSync = new Object();

    Handler handler = new Handler(Looper.getMainLooper());

    final Runnable invalidateRunnable;

    private volatile boolean isInvalidatePending = false;

    private volatile RenderHook renderHook;

    public OpenCvNativeViewViewport(Context context,  OnClickListener onClickListener)
    {
        super(context);

        renderer = new OpenCvViewRenderer(context, false);
        setOnClickListener(onClickListener);

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

        invalidateRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                // We don't synchronize this with onDraw() explicitly since
                // it all happens on the UI thread anyway.
                if (!isInvalidatePending)
                {
                    isInvalidatePending = true;
                    invalidate();
                }
            }
        };
    }

    @Override
    public void setOptimizedViewRotation(OptimizedRotation rotation)
    {
        renderer.setOptimizedViewRotation(rotation);
    }

    @Override
    public void notifyStatistics(float fps, int pipelineMs, int overheadMs)
    {
        renderer.notifyStatistics(fps, pipelineMs, overheadMs);
    }

    @Override
    public void setRecording(boolean recording)
    {
        renderer.setRecording(recording);
    }

    @Override
    public void post(Mat frame, Object context)
    {
        // Synchronized with activation/deactivation, but NOT with
        // anything else (we don't want to be blocked by onDraw() or something)
        synchronized (activeSync)
        {
            if (frame == null)
            {
                throw new IllegalArgumentException("cannot post null mat!");
            }

            if (active && !paused)
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
                    frame.copyTo(matToCopyTo);
                    matToCopyTo.setContext(context);
                    visionPreviewFrameQueue.offer(matToCopyTo);

                    handler.post(invalidateRunnable);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public void setFpsMeterEnabled(boolean enabled)
    {
        renderer.setFpsMeterEnabled(enabled);
    }

    @Override
    public void pause()
    {
        if (!paused)
        {
            synchronized (activeSync)
            {
                paused = true;

                if (active)
                {
                    handler.post(invalidateRunnable);
                }
            }
        }
    }

    @Override
    public void resume()
    {
        synchronized (activeSync)
        {
            if (paused)
            {
                paused = false;

                if (active)
                {
                    handler.post(invalidateRunnable);
                }
            }
        }
    }

    @Override
    public synchronized void activate() // synchronized w/ onDraw
    {
        synchronized (activeSync)
        {
            active = true;
            wasJustActivated = true;
            handler.post(invalidateRunnable);
        }
    }

    @Override
    public synchronized void deactivate() // synchronized w/ onDraw
    {
        synchronized (activeSync)
        {
            active = false;
            handler.post(invalidateRunnable);
        }
    }

    @Override
    public synchronized void setSize(int width, int height) // synchronized w/ onDraw
    {
        if (active)
        {
            throw new RuntimeException();
        }

        //Make sure we don't have any mats hanging around
        //from when we might have been running before
        visionPreviewFrameQueue.clear();

        framebufferRecycler = new MatRecycler(FRAMEBUFFER_RECYCLER_CAPACITY);
    }

    @Override
    public void setRenderingPolicy(OpenCvCamera.ViewportRenderingPolicy policy)
    {
        renderer.setRenderingPolicy(policy);
    }

    @Override
    public void setRenderHook(RenderHook renderHook)
    {
        this.renderHook = renderHook;
    }

    @Override
    public synchronized void onDraw(Canvas canvas) // synchronized with activate and deactivate
    {
        isInvalidatePending = false;

        if (!active)
        {
            canvas.drawColor(Color.BLACK);
        }
        else if (paused)
        {
            renderer.renderPaused(canvas);
        }
        else
        {
            MatRecycler.RecyclableMat mat = visionPreviewFrameQueue.poll();

            if (mat == null)
            {
                if (wasJustActivated)
                {
                    wasJustActivated = false;
                    canvas.drawColor(Color.BLUE);
                }
                else
                {
                    // This can happen if the layout changes and triggers a redraw
                    System.out.println("Invalidate w/ null mat " + System.currentTimeMillis());
                }

                return;
            }

            renderer.render(mat, canvas, renderHook, mat.getContext());

            //We're done with that Mat object; return it to the Mat recycler so it can be used again later
            framebufferRecycler.returnMat(mat);
        }
    }
}
