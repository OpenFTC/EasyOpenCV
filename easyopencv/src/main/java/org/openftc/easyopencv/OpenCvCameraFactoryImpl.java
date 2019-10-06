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

import android.content.Context;
import android.support.annotation.IdRes;

import com.qualcomm.robotcore.eventloop.opmode.AnnotatedOpModeManager;
import com.qualcomm.robotcore.eventloop.opmode.OpModeRegistrar;

import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;

class OpenCvCameraFactoryImpl extends OpenCvCameraFactory
{
    static void init()
    {
        OpenCvCameraFactory.theInstance = new OpenCvCameraFactoryImpl();
    }

    @OpModeRegistrar
    public static void initOnSdkBoot(Context context, AnnotatedOpModeManager manager)
    {
        init();
    }

    @Override
    public OpenCvCamera createInternalCamera(OpenCvInternalCamera.CameraDirection direction)
    {
        return new OpenCvInternalCameraImpl(direction);
    }

    @Override
    public OpenCvCamera createInternalCamera(OpenCvInternalCamera.CameraDirection direction, int containerId)
    {
        return new OpenCvInternalCameraImpl(direction, containerId);
    }

    @Override
    public OpenCvCamera createWebcam(WebcamName webcamName)
    {
        return new OpenCvWebcamImpl(webcamName);
    }

    @Override
    public OpenCvCamera createWebcam(WebcamName webcamName, @IdRes int viewportContainerId)
    {
        return new OpenCvWebcamImpl(webcamName, viewportContainerId);
    }
}
