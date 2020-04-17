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

import android.os.Debug;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.util.MovingStatistics;

import org.firstinspires.ftc.robotcore.internal.opmode.OpModeManagerImpl;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.concurrent.Semaphore;

public abstract class OpenCvPipeline
{
    private boolean isFirstFrame = true;
    private OpModeNotifications opModeNotifications = new OpModeNotifications();
    private static final Semaphore saveSemaphore = new Semaphore(5);
    private static final String savePath = "/sdcard/EasyOpenCV";

    private long firstFrameTimestamp;
    private boolean MEMLEAK_DETECTION_ENABLED = true;
    private int MEMLEAK_THRESHOLD_MB = 100;
    private int MEMLEAK_DETECTION_PIPELINE_SETTLE_DELAY_SECONDS = 4;
    private long nativeAllocFirstMonitoredFrame;
    private boolean settled = false;
    private long currentAlloc;
    private long firstMonitoredFrameTimestamp;
    private String leakMsg = "";

    public OpenCvPipeline()
    {
        OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().getActivity()).registerListener(opModeNotifications);

        synchronized (saveSemaphore)
        {
            File saveDir = new File(savePath);

            if(!saveDir.exists())
            {
                saveDir.mkdir();
            }
        }
    }

    Mat processFrameInternal(Mat input)
    {
        if(isFirstFrame)
        {
            init(input);
            firstFrameTimestamp = System.currentTimeMillis();
            isFirstFrame = false;
        }

        Mat ret = processFrame(input);
        leakDetection();
        return ret;
    }

    private void leakDetection()
    {
        if(!MEMLEAK_DETECTION_ENABLED)
        {
            return;
        }

        currentAlloc = Debug.getNativeHeapAllocatedSize();

        if(!settled && System.currentTimeMillis() - firstFrameTimestamp < MEMLEAK_DETECTION_PIPELINE_SETTLE_DELAY_SECONDS*1000)
        {
            settled = true;
            nativeAllocFirstMonitoredFrame = Debug.getNativeHeapAllocatedSize();
            firstMonitoredFrameTimestamp = System.currentTimeMillis();
            return;
        }

        if(!settled)
        {
            return;
        }

        long absoluteDeltaAlloc = currentAlloc - nativeAllocFirstMonitoredFrame;
        double absoluteDeltaAllocMB = absoluteDeltaAlloc / (1024.0*1024.0);

        double timeSinceStartedMonitoring = (System.currentTimeMillis() - firstMonitoredFrameTimestamp)/1000.0;

        double leakRate = absoluteDeltaAllocMB / timeSinceStartedMonitoring;

        if(absoluteDeltaAllocMB > MEMLEAK_THRESHOLD_MB)
        {
            leakMsg = String.format("User pipeline is likely leaking memory at %dMB/sec; since start of pipeline %dMB has been leaked", (int)leakRate, (int)absoluteDeltaAllocMB);
        }
        else
        {
            leakMsg = "";
        }
    }

    String getLeakMsg()
    {
        return leakMsg;
    }

    public abstract Mat processFrame(Mat input);
    public void onViewportTapped() {}

    public void init(Mat mat) {}
    public void cleanup() {}

    public void saveMatToDisk(Mat mat, final String filename)
    {
        try
        {
            saveSemaphore.acquire();
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
            return;
        }

        final Mat clone = mat.clone();

        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    Imgproc.cvtColor(clone, clone, Imgproc.COLOR_RGB2BGR);
                    Imgcodecs.imwrite(String.format("%s/%s.png", savePath, filename), clone);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                finally
                {
                    clone.release();
                    saveSemaphore.release();
                }
            }
        }).start();
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
            OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().getActivity()).unregisterListener(opModeNotifications);
            cleanup();
        }
    }
}
