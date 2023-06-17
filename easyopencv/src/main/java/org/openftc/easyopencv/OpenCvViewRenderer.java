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
    private static final int OVERLAY_COLOR = Color.rgb(102, 20, 68);
    private static final int PAUSED_COLOR = Color.rgb(255, 166, 0);
    private static final int RC_ACTIVITY_BG_COLOR = Color.rgb(239,239,239);
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
    private final boolean offscreen;

    private volatile boolean isRecording;

    private volatile OpenCvViewport.OptimizedRotation optimizedViewRotation;

    private volatile OpenCvCamera.ViewportRenderingPolicy renderingPolicy = OpenCvCamera.ViewportRenderingPolicy.MAXIMIZE_EFFICIENCY;

    private Bitmap bitmapFromMat;

    public OpenCvViewRenderer(Context context, boolean renderingOffsceen)
    {
        offscreen = renderingOffsceen;

        if (offscreen)
        {
            // We use an offscreen canvas size of 1280x720 which is roughly the same size
            // as the canvas on the Nexus 5 so this looks okay-ish.
            metricsScale = 1.0f;
        }
        else if (Device.isRevControlHub())
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
        fpsMeterNormalBgPaint.setColor(OVERLAY_COLOR);
        fpsMeterNormalBgPaint.setStyle(Paint.Style.FILL);

        fpsMeterRecordingPaint = new Paint();
        fpsMeterRecordingPaint.setColor(Color.RED);
        fpsMeterRecordingPaint.setStyle(Paint.Style.FILL);

        fpsMeterTextPaint = new Paint();
        fpsMeterTextPaint.setColor(Color.WHITE);
        fpsMeterTextPaint.setTextSize(fpsMeterTextSize);
        fpsMeterTextPaint.setAntiAlias(true);

        paintBlackBackground = new Paint();
        paintBlackBackground.setColor(Color.BLACK);
        paintBlackBackground.setStyle(Paint.Style.FILL);
    }

    private void unifiedDraw(Canvas canvas, int onscreenWidth, int onscreenHeight, OpenCvViewport.RenderHook userHook, Object userCtx)
    {
        int x_offset_statbox = 0;
        int y_offset_statbox = 0;

        int topLeftX;
        int topLeftY;
        int scaledWidth;
        int scaledHeight;

        double canvasAspect = (float) onscreenWidth/onscreenHeight;

        if(aspectRatio > canvasAspect) /* Image is WIDER than canvas */
        {
            // Width: we use the max we have, since horizontal bounds are hit before vertical bounds
            scaledWidth = onscreenWidth;

            // Height: calculate a scaled height assuming width is maxed for the canvas
            scaledHeight = (int) Math.round(onscreenWidth / aspectRatio);

            // We want to center the image in the viewport
            topLeftY = Math.abs(onscreenHeight-scaledHeight)/2;
            topLeftX = 0;
            y_offset_statbox = topLeftY;
        }
        else /* Image is TALLER than canvas */
        {
            // Height: we use the max we have, since vertical bounds are hit before horizontal bounds
            scaledHeight = onscreenHeight;

            // Width: calculate a scaled width assuming height is maxed for the canvas
            scaledWidth = (int) Math.round(onscreenHeight * aspectRatio);

            // We want to center the image in the viewport
            topLeftY = 0;
            topLeftX = Math.abs(onscreenWidth - scaledWidth) / 2;
            x_offset_statbox = topLeftX;
        }

        //Draw the bitmap, scaling it to the maximum size that will fit in the viewport
        Rect bmpRect = createRect(
                topLeftX,
                topLeftY,
                scaledWidth,
                scaledHeight);

        // Draw black behind the bitmap to avoid alpha issues if usercode tries to draw
        // annotations and doesn't specify alpha 255. This wasn't an issue when we just
        // painted black behind the entire view, but now that we paint the RC background
        // color, it is an issue...
        canvas.drawRect(bmpRect, paintBlackBackground);

        canvas.drawBitmap(
                bitmapFromMat,
                null,
                bmpRect,
                null
        );

        // We need to save the canvas translation/rotation and such before we hand off to the user,
        // because if they don't put it back how they found it and then we go to draw the FPS meter,
        // it's... well... not going to draw properly lol
        int canvasSaveBeforeUserDraw = canvas.save();

        // Allow the user to do some drawing if they want
        if (userHook != null)
        {
            // Can either use width or height I guess ¯\_(ツ)_/¯
            float scaleBitmapPxToCanvasPx = (float) scaledWidth / bitmapFromMat.getWidth();

            // To make the user's life easy, we teleport the origin to the top
            // left corner of the bitmap we painted
            canvas.translate(topLeftX, topLeftY);
            userHook.onDrawFrame(canvas, scaledWidth, scaledHeight, scaleBitmapPxToCanvasPx, metricsScale, userCtx);
        }

        // Make sure the canvas translation/rotation is what we expect (see comment when we save state)
        canvas.restoreToCount(canvasSaveBeforeUserDraw);

        if (fpsMeterEnabled)
        {
            Rect statsRect = createRect(
                    x_offset_statbox,
                    onscreenHeight-y_offset_statbox-statBoxH,
                    statBoxW,
                    statBoxH
            );

            drawStats(canvas, statsRect);
        }
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
        canvas.drawText(String.format("OpenFTC EasyOpenCV v%s", BuildConfig._VERSION_NAME),        statBoxLTxtStart, textLine1Y, fpsMeterTextPaint);
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

    public void render(Mat mat, Canvas canvas, OpenCvViewport.RenderHook userHook, Object userCtx)
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

        if (!offscreen)
        {
            //Draw the background each time to prevent double buffering problems
            canvas.drawColor(RC_ACTIVITY_BG_COLOR);
        }

        // Cache current state, can change behind our backs
        OpenCvViewport.OptimizedRotation optimizedRotationSafe = optimizedViewRotation;

        if(renderingPolicy == OpenCvCamera.ViewportRenderingPolicy.MAXIMIZE_EFFICIENCY || optimizedRotationSafe == OpenCvViewport.OptimizedRotation.NONE)
        {
            unifiedDraw(canvas, canvas.getWidth(), canvas.getHeight(), userHook, userCtx);
        }
        else if(renderingPolicy == OpenCvCamera.ViewportRenderingPolicy.OPTIMIZE_VIEW)
        {
            if(optimizedRotationSafe == OpenCvViewport.OptimizedRotation.ROT_180)
            {
                // 180 is easy, just rotate canvas 180 about center and draw as usual
                canvas.rotate(optimizedRotationSafe.val, canvas.getWidth()/2, canvas.getHeight()/2);
                unifiedDraw(canvas, canvas.getWidth(), canvas.getHeight(), userHook, userCtx);
            }
            else // 90 either way
            {
                // Rotate the canvas +-90 about the center
                canvas.rotate(optimizedRotationSafe.val, canvas.getWidth()/2, canvas.getHeight()/2);

                // Translate the canvas such that 0,0 is in the top left corner (for this perspective) ONSCREEN.
                int origin_x = (canvas.getWidth()-canvas.getHeight())/2;
                int origin_y = -origin_x;
                canvas.translate(origin_x, origin_y);

                // Now draw as normal, but, the onscreen width and height are swapped
                unifiedDraw(canvas, canvas.getHeight(), canvas.getWidth(), userHook, userCtx);
            }
        }
    }

    public void setRenderingPolicy(OpenCvCamera.ViewportRenderingPolicy policy)
    {
        renderingPolicy = policy;
    }

    public void renderPaused(Canvas canvas)
    {
        canvas.drawColor(PAUSED_COLOR);

        Rect rect = createRect(
                0,
                canvas.getHeight()-statBoxH,
                statBoxW,
                statBoxH
        );

        // Draw the purple rectangle
        canvas.drawRect(rect, fpsMeterNormalBgPaint);

        // Some formatting stuff
        int statBoxLTxtStart = rect.left+statBoxLTxtMargin;
        int textLine1Y = rect.bottom - statBoxTextFirstLineYFromBottomOffset;
        int textLine2Y = textLine1Y + statBoxTextLineSpacing;
        int textLine3Y = textLine2Y + statBoxTextLineSpacing;

        // Draw the 3 text lines
        canvas.drawText(String.format("OpenFTC EasyOpenCV v%s", BuildConfig._VERSION_NAME), statBoxLTxtStart, textLine1Y, fpsMeterTextPaint);
        canvas.drawText("VIEWPORT PAUSED", statBoxLTxtStart, textLine2Y, fpsMeterTextPaint);
        //canvas.drawText("Hi", statBoxLTxtStart, textLine3Y, fpsMeterTextPaint);
    }
}
