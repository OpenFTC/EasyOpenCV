package org.openftc.easyopencv;

public interface OpenCvWebcam extends OpenCvCamera
{
    //-----------------------------------------------------------------------
    // Exposure
    //-----------------------------------------------------------------------

    enum ExposureMode
    {
        Unknown,
        Auto,               // single trigger auto exposure
        ContinuousAuto,     // continuous auto exposure
        Manual,
        ShutterPriority,
        AperturePriority,   // Not in Vuforia
    }

    void setExposureMode(ExposureMode exposureMode);

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

    enum FocusMode
    {
        Unknown,
        Auto,
        ContinuousAuto,
        Macro,
        Infinity,
        Fixed
    }

    void setFocusMode(FocusMode focusMode);

    /***
     * The minimum distance the camera can focus at, in diopters.
     * See {@link #setFocusDistance(double)}
     *
     * @return The minimum distance the camera can focus at, in diopters
     */
    double getMinFocusDistance();

    /***
     * Set the distance the camera should focus at in diopters. A diopter is 1/meters.
     * For instance to focus at 13cm, you want to focus at 1/0.13 diopters.
     *
     * The reason for this is that it makes representing focusing at infinity very easy
     * (to focus at infinity just set 0 diopters)
     *
     * @param diopters See above. Must be between 0 and {@link #getMinFocusDistance()}
     */
    void setFocusDistance(double diopters);
}
