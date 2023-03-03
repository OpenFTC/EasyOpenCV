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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import com.qualcomm.robotcore.util.Device;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

public class OpenCvViewRenderer
{
    private final int statBoxW;
    private final int statBoxH;
    private final int statBoxTextLineSpacing;
    private final int statBoxTextFirstLineYFromBottomOffset;
    private final int statBoxLTxtMargin;
    private static final float referenceDPI = 443; // Nexus 5
    private final float metricsScale;
    private Paint fpsMeterNormalBgPaint;
    private Paint fpsMeterRecordingPaint;
    private Paint fpsMeterTextPaint;
    private final float fpsMeterTextSize;
    private Paint paintBlackBackground;
    private double aspectRatio;

    private boolean fpsMeterEnabled = true;
    private float fps = 0;
    private int pipelineMs = 0;
    private int overheadMs = 0;

    private int width;
    private int height;

    private volatile boolean isRecording;

    private volatile OpenCvViewport.OptimizedRotation optimizedViewRotation;

    private volatile OpenCvCamera.ViewportRenderingPolicy renderingPolicy = OpenCvCamera.ViewportRenderingPolicy.MAXIMIZE_EFFICIENCY;

    private Bitmap bitmapFromMat;

    public OpenCvViewRenderer(Context context)
    {
        if (Device.isRevControlHub())
        {
            // Control Hub reports a bogus dpi so use something that looks somewhat ok
            metricsScale = 0.5f;
        }
        else
        {
            metricsScale = context.getResources().getDisplayMetrics().xdpi / referenceDPI;
        }

        fpsMeterTextSize = 30 * metricsScale;
        statBoxW = (int) (450 * metricsScale);
        statBoxH = (int) (120 * metricsScale);
        statBoxTextLineSpacing = (int) (35 * metricsScale);
        statBoxLTxtMargin = (int) (5 * metricsScale);
        statBoxTextFirstLineYFromBottomOffset = (int) (80*metricsScale);

        fpsMeterNormalBgPaint = new Paint();
        fpsMeterNormalBgPaint.setColor(Color.rgb(102, 20, 68));
        fpsMeterNormalBgPaint.setStyle(Paint.Style.FILL);

        fpsMeterRecordingPaint = new Paint();
        fpsMeterRecordingPaint.setColor(Color.rgb(255, 0, 0));
        fpsMeterRecordingPaint.setStyle(Paint.Style.FILL);

        fpsMeterTextPaint = new Paint();
        fpsMeterTextPaint.setColor(Color.WHITE);
        fpsMeterTextPaint.setTextSize(fpsMeterTextSize);

        paintBlackBackground = new Paint();
        paintBlackBackground.setColor(Color.BLACK);
        paintBlackBackground.setStyle(Paint.Style.FILL);
    }

    private void drawOptimizingView(Canvas canvas)
    {
        /***
         * WE CAN ONLY LOOK AT THIS VARIABLE ONCE BECAUSE IT CAN BE CHANGED BEHIND
         * OUT BACKS FROM ANOTHER THREAD!
         *
         * Technically, we could synchronize with {@link #setOptimizedViewRotation(OpenCvViewport.OptimizedRotation)}
         * but drawing can sometimes take a long time (e.g. 30ms) so just caching seems to be better...
         */
        OpenCvViewport.OptimizedRotation optimizedViewRotationLocalCache = optimizedViewRotation;

        if(optimizedViewRotationLocalCache == OpenCvViewport.OptimizedRotation.NONE)
        {
            /*
             * Ignore this request to optimize the view, nothing to do
             */
            drawOptimizingEfficiency(canvas);
            return;
        }
        else if(optimizedViewRotationLocalCache == OpenCvViewport.OptimizedRotation.ROT_180)
        {
            /*
             * If we're rotating by 180, then we can just re-use the drawing code
             * from the efficient method
             */
            canvas.rotate(optimizedViewRotationLocalCache.val, canvas.getWidth()/2, canvas.getHeight()/2);
            drawOptimizingEfficiency(canvas);
            return;
        }

        drawOptimizingViewForQuarterRot(canvas, optimizedViewRotationLocalCache);
    }

    private void drawOptimizingViewForQuarterRot(Canvas canvas, OpenCvViewport.OptimizedRotation optimizedViewRotationLocalCache)
    {
        canvas.rotate(optimizedViewRotationLocalCache.val, canvas.getWidth()/2, canvas.getHeight()/2);

        // Swapped because of 90deg rotation
        int canvasWidth = canvas.getHeight();
        int canvasHeight = canvas.getWidth();

        // Calculate the new origin (top left) that will be visible onscreen
        int origin_x = (canvasHeight-canvasWidth)/2;
        int origin_y = (canvasWidth-canvasHeight)/2;

        // Calculate the aspect ratio of the canvas and bitmap
        double canvasAspect = (float) canvasWidth/canvasHeight;

        int y_offset_statbox = 0;

        // Image width is the factor we need to consider
        if(aspectRatio > canvasAspect)
        {
            // Width: we use the max we have, since horizontal bounds are hit before vertical bounds
            int scaledWidth = canvasWidth;

            // Height: calculate a scaled height assuming width is maxed for the canvas
            int scaledHeight = (int) Math.round(canvasWidth / aspectRatio);

            // We want to center the image in the viewport
            int topLeftX = origin_x;
            int topLeftY = origin_y + Math.abs(canvasHeight-scaledHeight)/2;
            y_offset_statbox = Math.abs(canvasHeight-scaledHeight)/2;

            Rect rect = createRect(
                    topLeftX,
                    topLeftY,
                    scaledWidth,
                    scaledHeight);

            // Draw black behind the bitmap to avoid alpha issues if usercode tries to draw
            // annotations and doesn't specify alpha 255. This wasn't an issue when we just
            // painted black behind the entire view, but now that we paint the RC background
            // color, it is an issue...
            canvas.drawRect(rect, paintBlackBackground);

            canvas.drawBitmap(
                    bitmapFromMat,
                    null,
                    rect,
                    null
            );
        }
        // Image height is the factor we need to consider
        else
        {
            // Height: we use the max we have, since vertical bounds are hit before horizontal bounds
            int scaledHeight = canvasHeight;

            // Width: calculate a scaled width assuming height is maxed for the canvas
            int scaledWidth = (int) Math.round(canvasHeight * aspectRatio);

            // We want to center the image in the viewport
            int topLeftY = origin_y;
            int topLeftX = origin_x + Math.abs(canvasWidth-scaledWidth)/2;

            Rect rect = createRect(
                    topLeftX,
                    topLeftY,
                    scaledWidth,
                    scaledHeight);

            // Draw black behind the bitmap to avoid alpha issues if usercode tries to draw
            // annotations and doesn't specify alpha 255. This wasn't an issue when we just
            // painted black behind the entire view, but now that we paint the RC background
            // color, it is an issue...
            canvas.drawRect(rect, paintBlackBackground);

            canvas.drawBitmap(
                    bitmapFromMat,
                    null,
                    rect,
                    null
            );
        }

        /*
         * If we don't need to draw the statistics, get out of dodge
         */
        if(!fpsMeterEnabled)
            return;

        Rect rect = null;

        if(optimizedViewRotationLocalCache == OpenCvViewport.OptimizedRotation.ROT_90_COUNTERCLOCWISE)
        {
            rect = createRect(
                    origin_x+canvasWidth-statBoxW,
                    origin_y+canvasHeight-statBoxH-y_offset_statbox,
                    statBoxW,
                    statBoxH);
        }
        else if(optimizedViewRotationLocalCache == OpenCvViewport.OptimizedRotation.ROT_90_CLOCKWISE)
        {
            rect = createRect(
                    origin_x,
                    origin_y+canvasHeight-statBoxH-y_offset_statbox,
                    statBoxW,
                    statBoxH);
        }

        drawStats(canvas, rect);
    }

    private void drawOptimizingEfficiency(Canvas canvas)
    {
        int x_offset_statbox = 0;
        int y_offset_statbox = 0;

        /*
         * We need to draw minding the HEIGHT we have to work with; width is not an issue
         */
        if((canvas.getHeight() * aspectRatio) < canvas.getWidth())
        {
            // Height: we use the max we have, since vertical bounds are hit before horizontal bounds
            int scaledHeight = canvas.getHeight();

            // Width: calculate a scaled width assuming height is maxed for the canvas
            int scaledWidth = (int) Math.round(canvas.getHeight() * aspectRatio);

            // We want to center the image in the viewport
            x_offset_statbox = Math.abs(canvas.getWidth()-scaledWidth)/2;
            int topLeftY = 0;
            int topLeftX = 0 + Math.abs(canvas.getWidth()-scaledWidth)/2;

            //Draw the bitmap, scaling it to the maximum size that will fit in the viewport
            Rect rect = createRect(
                    topLeftX,
                    topLeftY,
                    scaledWidth,
                    scaledHeight);

            // Draw black behind the bitmap to avoid alpha issues if usercode tries to draw
            // annotations and doesn't specify alpha 255. This wasn't an issue when we just
            // painted black behind the entire view, but now that we paint the RC background
            // color, it is an issue...
            canvas.drawRect(rect, paintBlackBackground);

            canvas.drawBitmap(
                    bitmapFromMat,
                    null,
                    rect,
                    null
            );
        }

        /*
         * We need to draw minding the WIDTH we have to work with; height is not an issue
         */
        else
        {
            // Width: we use the max we have, since horizontal bounds are hit before vertical bounds
            int scaledWidth = canvas.getWidth();

            // Height: calculate a scaled height assuming width is maxed for the canvas
            int scaledHeight = (int) Math.round(canvas.getWidth() / aspectRatio);

            // We want to center the image in the viewport
            int topLeftY = Math.abs(canvas.getHeight()-scaledHeight)/2;
            int topLeftX = 0;
            y_offset_statbox = Math.abs(canvas.getHeight()-scaledHeight)/2;

            //Draw the bitmap, scaling it to the maximum size that will fit in the viewport
            Rect rect = createRect(
                    topLeftX,
                    topLeftY,
                    scaledWidth,
                    scaledHeight);

            // Draw black behind the bitmap to avoid alpha issues if usercode tries to draw
            // annotations and doesn't specify alpha 255. This wasn't an issue when we just
            // painted black behind the entire view, but now that we paint the RC background
            // color, it is an issue...
            canvas.drawRect(rect, paintBlackBackground);

            canvas.drawBitmap(
                    bitmapFromMat,
                    null,
                    rect,
                    null
            );
        }

        /*
         * If we don't need to draw the statistics, get out of dodge
         */
        if(!fpsMeterEnabled)
            return;

        Rect rect = createRect(
                x_offset_statbox,
                canvas.getHeight()-statBoxH-y_offset_statbox,
                statBoxW,
                statBoxH
        );

        drawStats(canvas, rect);
    }

    private void drawStats(Canvas canvas, Rect rect)
    {
        // Draw the purple rectangle
        if(isRecording)
        {
            canvas.drawRect(rect, fpsMeterRecordingPaint);
        }
        else
        {
            canvas.drawRect(rect, fpsMeterNormalBgPaint);
        }

        // Some formatting stuff
        int statBoxLTxtStart = rect.left+statBoxLTxtMargin;
        int textLine1Y = rect.bottom - statBoxTextFirstLineYFromBottomOffset;
        int textLine2Y = textLine1Y + statBoxTextLineSpacing;
        int textLine3Y = textLine2Y + statBoxTextLineSpacing;

        // Draw the 3 text lines
        canvas.drawText(String.format("OpenFTC EasyOpenCV v%s", BuildConfig.VERSION_NAME),        statBoxLTxtStart, textLine1Y, fpsMeterTextPaint);
        canvas.drawText(String.format("FPS@%dx%d: %.2f", width, height, fps), statBoxLTxtStart, textLine2Y, fpsMeterTextPaint);
        canvas.drawText(String.format("Pipeline: %dms - Overhead: %dms", pipelineMs, overheadMs), statBoxLTxtStart, textLine3Y, fpsMeterTextPaint);
    }

    Rect createRect(int tlx, int tly, int w, int h)
    {
        return new Rect(tlx, tly, tlx+w, tly+h);
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

    public void setRecording(boolean recording)
    {
        isRecording = recording;
    }

    public void setOptimizedViewRotation(OpenCvViewport.OptimizedRotation optimizedViewRotation)
    {
        this.optimizedViewRotation = optimizedViewRotation;
    }

    public void render(Mat mat, Canvas canvas)
    {
        if (bitmapFromMat == null || bitmapFromMat.getWidth() != mat.width() || bitmapFromMat.getHeight() != mat.height())
        {
            if (bitmapFromMat != null)
            {
                bitmapFromMat.recycle();
            }

            bitmapFromMat = Bitmap.createBitmap(mat.width(), mat.height(), Bitmap.Config.ARGB_8888);
        }

        //Convert that Mat to a bitmap we can render
        Utils.matToBitmap(mat, bitmapFromMat);

        width = bitmapFromMat.getWidth();
        height = bitmapFromMat.getHeight();
        aspectRatio = (float) width / height;

        //Draw the background each time to prevent double buffering problems
        canvas.drawColor(Color.rgb(239,239,239)); // RC activity background color

        if(renderingPolicy == OpenCvCamera.ViewportRenderingPolicy.MAXIMIZE_EFFICIENCY)
        {
            drawOptimizingEfficiency(canvas);
        }
        else if(renderingPolicy == OpenCvCamera.ViewportRenderingPolicy.OPTIMIZE_VIEW)
        {
            drawOptimizingView(canvas);
        }
    }

    public void setRenderingPolicy(OpenCvCamera.ViewportRenderingPolicy policy)
    {
        renderingPolicy = policy;
    }

    public void renderPaused(Canvas canvas)
    {
        canvas.drawColor(Color.rgb(255, 166, 0));
        canvas.drawRect(0, canvas.getHeight()-40, 450, canvas.getHeight(), fpsMeterNormalBgPaint);
        canvas.drawText("VIEWPORT PAUSED", 5, canvas.getHeight()-10, fpsMeterTextPaint);
    }
}
