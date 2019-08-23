/*
 * Copyright (c) 2019 OpenFTC Team
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

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.util.List;

public class OpenCvInternalCamera extends OpenCvCameraBase implements Camera.PreviewCallback
{
    private Camera camera;
    private CameraDirection direction;
    private byte[] rawSensorBuffer;
    private Mat rawSensorMat;
    private Mat rgbMat;
    private SurfaceTexture bogusSurfaceTexture;

    public enum CameraDirection
    {
        FRONT(Camera.CameraInfo.CAMERA_FACING_FRONT),
        BACK(Camera.CameraInfo.CAMERA_FACING_BACK);

        public int id;

        CameraDirection(int id)
        {
            this.id = id;
        }
    }

    public OpenCvInternalCamera(CameraDirection direction)
    {
        this.direction = direction;
    }

    public OpenCvInternalCamera(CameraDirection direction, int containerLayoutId)
    {
        super(containerLayoutId);
        this.direction = direction;
    }

    @Override
    public OpenCvCameraRotation getDefaultRotation()
    {
        return OpenCvCameraRotation.UPRIGHT;
    }

    @Override
    protected int mapRotationEnumToOpenCvRotateCode(OpenCvCameraRotation rotation)
    {
        /*
         * The camera sensor in a phone is mounted sideways, such that the raw image
         * is only upright when the phone is rotated to the left. Therefore, we need
         * to manually rotate the image if the phone is in any other orientation
         */

        if(direction == CameraDirection.BACK)
        {
            if(rotation == OpenCvCameraRotation.UPRIGHT)
            {
                return Core.ROTATE_90_CLOCKWISE;
            }
            else if(rotation == OpenCvCameraRotation.UPSIDE_DOWN)
            {
                return Core.ROTATE_90_COUNTERCLOCKWISE;
            }
            else if(rotation == OpenCvCameraRotation.SIDEWAYS_RIGHT)
            {
                return Core.ROTATE_180;
            }
            else
            {
                return -1;
            }
        }
        else if(direction == CameraDirection.FRONT)
        {
            if(rotation == OpenCvCameraRotation.UPRIGHT)
            {
                return Core.ROTATE_90_COUNTERCLOCKWISE;
            }
            else if(rotation == OpenCvCameraRotation.UPSIDE_DOWN)
            {
                return Core.ROTATE_90_CLOCKWISE;
            }
            else if(rotation == OpenCvCameraRotation.SIDEWAYS_RIGHT)
            {
                return Core.ROTATE_180;
            }
            else
            {
                return -1;
            }
        }

        return -1;
    }

    @Override
    protected synchronized void openCameraDeviceImplSpecific()
    {
        if(camera == null)
        {
            camera = Camera.open(direction.id);
        }
    }

    @Override
    public synchronized void closeCameraDeviceImplSpecific()
    {
        if(camera != null)
        {
            stopStreaming();
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    @Override
    public synchronized void startStreamingImplSpecific(int width, int height)
    {
        rawSensorMat = new Mat(height + (height/2), width, CvType.CV_8UC1);
        rgbMat = new Mat(height + (height/2), width, CvType.CV_8UC1);

        if(camera != null)
        {
            Camera.Parameters parameters = camera.getParameters();
            parameters.setPreviewFormat(ImageFormat.NV21);
            parameters.setPreviewSize(width, height);

            /*
             * Not all cameras support all focus modes...
             */
            if(parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
            {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            else if(parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
            {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            else if(parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_FIXED))
            {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
            }

            boolean isRequestedSizeSupported = false;

            List<Camera.Size> cameraSupportedPreviewSizes = parameters.getSupportedPreviewSizes();

            for(Camera.Size size : cameraSupportedPreviewSizes)
            {
                if(size.width == width && size.height == height)
                {
                    isRequestedSizeSupported = true;
                    break;
                }
            }

            if(!isRequestedSizeSupported)
            {
                throw new OpenCvCameraException("Camera does not support requested resolution!");
            }

            camera.setParameters(parameters);

            int pixels = width * height;
            int bufSize  = pixels * ImageFormat.getBitsPerPixel(parameters.getPreviewFormat()) / 8;
            rawSensorBuffer = new byte[bufSize];

            bogusSurfaceTexture = new SurfaceTexture(10);

            camera.setPreviewCallbackWithBuffer(this);
            camera.addCallbackBuffer(rawSensorBuffer);

            try
            {
                camera.setPreviewTexture(bogusSurfaceTexture);
            }
            catch (IOException e)
            {
                e.printStackTrace();
                closeCameraDevice();
                return;
            }

            camera.startPreview();
        }
    }

    @Override
    public synchronized void stopStreamingImplSpecific()
    {
        if(camera != null)
        {
            camera.setPreviewCallback(null);
            camera.stopPreview();
        }

        if(rawSensorMat != null)
        {
            rawSensorMat.release();
            rawSensorMat = null;
        }

        if(rgbMat != null)
        {
            rgbMat.release();
            rgbMat = null;
        }
    }

    /*
     * This needs to be synchronized with stopStreamingImplSpecific()
     * because we touch objects that are destroyed in that method.
     */
    @Override
    public synchronized void onPreviewFrame(byte[] data, Camera camera)
    {
        notifyStartOfFrameProcessing();

        /*
         * Unfortunately, we can't easily create a Java byte[] that
         * references the native memory in a Mat, so we have to do
         * a memcpy from our Java byte[] to the native one in the Mat.
         * (If we could, then we could have the camera dump the preview
         * image directly into the Mat).
         *
         * TODO: investigate using a bit of native code to remove the need to do a memcpy
         */
        if(rawSensorMat != null)
        {
            rawSensorMat.put(0,0,data);

            Imgproc.cvtColor(rawSensorMat, rgbMat, Imgproc.COLOR_YUV2RGBA_NV21, 4);
            handleFrame(rgbMat);

            if(camera != null)
            {
                camera.addCallbackBuffer(rawSensorBuffer);
            }
        }
    }
}
