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

    //-----------------------------------------------------------------------
    // ISO
    //-----------------------------------------------------------------------

    /***
     * Get the minimum gain (ISO) supported by the image sensor
     *
     * @return see above
     */
    int getMinSensorGain();

    /***
     * Get the maximum gain (ISO) supported by the image sensor
     *
     * @return see above
     */
    int getMaxSensorGain();

    /***
     * Set the gain (ISO) of the image sensor
     * Must be between {@link #getMinSensorGain()}
     * and {@link #getMaxSensorGain()}
     *
     * @param iso the gain (ISO) the image sensor should use
     */
    void setSensorGain(int iso);

    //-----------------------------------------------------------------------
    // Exposure
    //-----------------------------------------------------------------------

    /***
     * Get the maximum exposure compensation value
     * supported by the auto exposure routine.
     *
     * @return see above
     */
    int getMinAutoExposureCompensation();

    /***
     * Get the maximum exposure compensation value
     * supported by the auto exposure routine.
     *
     * @return see above
     */
    int getMaxAutoExposureCompensation();

    /***
     * Set the exposure compensation value of the auto exposure
     * routine. This can allow for (limited) relative exposure
     * adjustment from the automatically determined value.
     *
     * @param aeCompensation see above
     */
    void setAutoExposureCompensation(int aeCompensation);

    /***
     * Set the exposure time the image sensor should use when capturing images.
     *
     * @param denominator the denominator of a traditional fractional exposure time notation
     *                    e.g. if you want to set exposure to 1/250s, this param should be 250
     */
    void setExposureFractional(int denominator);

    /***
     * Set the exposure time the image sensor should use when capturing images.
     *
     * @param nanos the amount of time the sensor should expose for, in nanoseconds
     */
    void setExposureNanos(long nanos);

    //-----------------------------------------------------------------------
    // Focus
    //-----------------------------------------------------------------------

    /***
     * The minimum distance the camera can focus at, in diopters.
     * See {@link #setFocusDistance(float)}
     *
     * @return The minimum distance the camera can focus at, in diopters
     */
    float getMinFocusDistance();

    /***
     * Set the distance the camera should focus at in diopters. A diopter is 1/meters.
     * For instance to focus at 13cm, you want to focus at 1/0.13 diopters.
     *
     * The reason for this is that it makes representing focusing at infinity very easy
     * (to focus at infinity just set 0 diopters)
     *
     * @param diopters See above. Must be between 0 and {@link #getMinFocusDistance()}
     */
    void setFocusDistance(float diopters);

    //-----------------------------------------------------------------------
    // Misc.
    //-----------------------------------------------------------------------

    /***
     * Set a specific number of frames per second the image sensor should capture.
     *
     * Note that your pipeline may not be fast enough to keep up with what you ask the sensor
     * to send, which will result in your pipeline FPS not matching what you set here.
     *
     * For instance, suppose you request 24FPS, but your pipeline requires an amount of
     * compute time that results in it only being able to run at 15FPS. In that case, your
     * pipeline will still run at 15FPS.
     *
     * Conversely, if your pipeline is capable of processing 100 frames per second, but
     * you request only 15FPS from the sensor, your pipeline will run at 15FPS.
     *
     * @param sensorFps see above
     */
    void setSensorFps(int sensorFps);

    /***
     * Set whether or not the camera's flash should be
     * put into flashlight ("torch") mode, i.e. where
     * it is always on.
     *
     * @param enabled see above
     */
    void setFlashlightEnabled(boolean enabled);
}
