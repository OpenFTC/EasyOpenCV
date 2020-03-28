package org.openftc.easyopencv;

import android.hardware.camera2.CameraCharacteristics;

public interface OpenCvInternalCamera2 extends OpenCvCamera
{
    enum CameraDirection
    {
        FRONT(CameraCharacteristics.LENS_FACING_FRONT),
        BACK(CameraCharacteristics.LENS_FACING_BACK);

        public int id;

        CameraDirection(int id)
        {
            this.id = id;
        }
    }

    int getMinSensorGain();
    int getMaxSensorGain();

    void setSensorGain(int iso);

    void setExposureFractional(int denominator);
    void setExposureNanos(long nanos);

    float getMinFocusDistance();

    void setFocusDistance(float diopters);

    void setSensorFps(int sensorFps);
}
