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

import com.qualcomm.robotcore.eventloop.opmode.OpMode;

import org.firstinspires.ftc.robotcore.internal.opmode.OpModeManagerImpl;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.opencv.core.Mat;

public abstract class OpenCvPipeline
{
    private boolean isFirstFrame = true;
    private OpModeNotifications opModeNotifications = new OpModeNotifications();

    public OpenCvPipeline()
    {
        OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().getActivity()).registerListener(opModeNotifications);
    }

    Mat processFrameInternal(Mat input)
    {
        if(isFirstFrame)
        {
            init(input);
            isFirstFrame = false;
        }

        return processFrame(input);
    }

    public abstract Mat processFrame(Mat input);
    public void onViewportTapped() {}

    public void init(Mat mat) {}
    public void cleanup() {}

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
