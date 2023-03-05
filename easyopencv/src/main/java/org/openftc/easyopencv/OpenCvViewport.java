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

import android.graphics.Canvas;

import org.opencv.core.Mat;

interface OpenCvViewport
{
    enum OptimizedRotation
    {
        NONE(0),
        ROT_90_COUNTERCLOCWISE(90),
        ROT_90_CLOCKWISE(-90),
        ROT_180(180);

        int val;

        OptimizedRotation(int val)
        {
            this.val = val;
        }
    }

    interface RenderHook
    {
        void onDrawFrame(Canvas canvas, int onscreenWidth, int onscreenHeight, float scaleBmpPxToCanvasPx, float canvasDensityScale, Object userContext);
    }

    void setFpsMeterEnabled(boolean enabled);
    void pause();
    void resume();
    void activate();
    void deactivate();
    void setSize(int width, int height);
    void setOptimizedViewRotation(OptimizedRotation rotation);
    void notifyStatistics(float fps, int pipelineMs, int overheadMs);
    void setRecording(boolean recording);
    void post(Mat frame, Object userContext);
    void setRenderingPolicy(OpenCvCamera.ViewportRenderingPolicy policy);
    void setRenderHook(RenderHook renderHook);

    class FrameContext
    {
        OpenCvPipeline generatingPipeline;
        Object userContext;

        public FrameContext(OpenCvPipeline generatingPipeline, Object userContext)
        {
            this.generatingPipeline = generatingPipeline;
            this.userContext = userContext;
        }
    }
}
