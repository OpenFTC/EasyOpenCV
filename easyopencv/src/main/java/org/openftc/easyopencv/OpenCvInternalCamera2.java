package org.openftc.easyopencv;

import android.hardware.Camera;
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
}
