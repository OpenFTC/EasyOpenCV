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

import android.hardware.Camera;

public interface OpenCvInternalCamera extends OpenCvCamera
{
    enum CameraDirection
    {
        FRONT(Camera.CameraInfo.CAMERA_FACING_FRONT),
        BACK(Camera.CameraInfo.CAMERA_FACING_BACK);

        public int id;

        CameraDirection(int id)
        {
            this.id = id;
        }
    }

    /***
     * Sets the recording hint parameter of the camera.
     * This tells the camera API that the intent of the
     * application is to record a video. While this is
     * not true for OpenCV image processing, it does seem
     * to make the camera choose to boost ISO before lowering
     * the frame rate.
     *
     * @param hint the recording hint parameter of the camera
     */
    void setRecordingHint(boolean hint);

    /***
     * Set the FPS range the camera hardware should send
     * frames at. Note that only a few ranges are supported.
     * Usually, a device will support (30,30), which will allow
     * you to "lock" the camera into sending 30FPS. This will,
     * however, have the potential to cause the stream to be
     * dark in low light.
     *
     * @param frameTiming the frame timing range the hardware
     *                    should send frames at
     */
    void setHardwareFrameTimingRange(FrameTimingRange frameTiming);

    /***
     * Ask the camera hardware what frame timing ranges it supports.
     *
     * @return an array of FrameTimingRange objects which represents
     *         the frame timing ranges supported by the camera hardware.
     */
    FrameTimingRange[] getFrameTimingRangesSupportedByHardware();

    class FrameTimingRange
    {
        int min;
        int max;

        public FrameTimingRange(int min, int max)
        {
            this.min = min;
            this.max = max;
        }

        @Override
        public boolean equals(Object o)
        {
            if(o == null || o.getClass() != this.getClass())
            {
                return false;
            }

            FrameTimingRange objToCompare = (FrameTimingRange)o;

            return min == objToCompare.min && max == objToCompare.max;
        }
    }
}
