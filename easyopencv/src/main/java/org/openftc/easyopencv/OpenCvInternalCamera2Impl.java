package org.openftc.easyopencv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.view.Surface;

import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

@SuppressLint({"NewApi", "MissingPermission"})
public class OpenCvInternalCamera2Impl extends OpenCvCameraBase implements OpenCvInternalCamera2, ImageReader.OnImageAvailableListener
{
    CameraDevice mCameraDevice;

    FixedHandlerThread cameraHardwareHandlerThread;
    private Handler cameraHardwareHandler;

    FixedHandlerThread frameWorkerHandlerThread;
    Handler frameWorkerHandler;

    volatile CountDownLatch cameraOpenedLatch;
    volatile CountDownLatch streamingStartedLatch;
    ImageReader imageReader;
    CaptureRequest.Builder mPreviewRequestBuilder;
    CameraCaptureSession cameraCaptureSession;
    Mat rgbMat;
    OpenCvInternalCamera2.CameraDirection direction;
    private volatile boolean isOpen = false;
    public float exposureTime = 1/50f;
    private volatile boolean isStreaming = false;
    Surface surface;
    CameraManager cameraManager;
    CameraCharacteristics cameraCharacteristics;

    ReentrantLock sync = new ReentrantLock();

    @SuppressLint("WrongConstant")
    public OpenCvInternalCamera2Impl(OpenCvInternalCamera2.CameraDirection direction)
    {
        this.direction = direction;
        cameraManager = (CameraManager) AppUtil.getInstance().getActivity().getSystemService(Context.CAMERA_SERVICE);
    }

    @SuppressLint("WrongConstant")
    public OpenCvInternalCamera2Impl(OpenCvInternalCamera2.CameraDirection direction, int containerLayoutId)
    {
        super(containerLayoutId);
        this.direction = direction;
        cameraManager = (CameraManager) AppUtil.getInstance().getActivity().getSystemService(Context.CAMERA_SERVICE);
    }

    @Override
    protected OpenCvCameraRotation getDefaultRotation()
    {
        return OpenCvCameraRotation.UPRIGHT;
    }

    @Override
    protected int mapRotationEnumToOpenCvRotateCode(OpenCvCameraRotation rotation)
    {
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
        else
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
    }

    @Override
    public void openCameraDevice()
    {
        sync.lock();

        if(!isOpen && mCameraDevice == null)
        {
            try
            {
                startCameraHardwareHandlerThread();

                String camList[] = cameraManager.getCameraIdList();

                String camId = null;

                for(String s : camList)
                {
                    if(cameraManager.getCameraCharacteristics(s).get(CameraCharacteristics.LENS_FACING) == direction.id)
                    {
                        camId = s;
                        break;
                    }
                }

                cameraOpenedLatch = new CountDownLatch(1);
                cameraCharacteristics = cameraManager.getCameraCharacteristics(camId);
                cameraManager.openCamera(camId, mStateCallback, cameraHardwareHandler);

                cameraOpenedLatch.await();
                isOpen = true;
            }
            catch (CameraAccessException e)
            {
                e.printStackTrace();
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }

        sync.unlock();
    }

    @Override
    public void closeCameraDevice()
    {
        sync.lock();

        cleanupForClosingCamera();

        if(isOpen)
        {
            if(mCameraDevice != null)
            {
                stopStreaming();

                mCameraDevice.close();
                stopCameraHardwareHandlerThread();
                mCameraDevice = null;
            }

            isOpen = false;
        }

        sync.unlock();
    }

    @Override
    public void startStreaming(int width, int height)
    {
        startStreaming(width, height, OpenCvCameraRotation.UPRIGHT);
    }

    @Override
    public void startStreaming(int width, int height, OpenCvCameraRotation rotation)
    {
        sync.lock();

        /*
         * If we're already streaming, then that's OK, but we need to stop
         * streaming in the old mode before we can restart in the new one.
         */
        if(isStreaming)
        {
            stopStreaming();
        }

        prepareForStartStreaming(width, height, rotation);

        try
        {
            rgbMat = new Mat(height, width, CvType.CV_8UC3);

            startFrameWorkerHandlerThread();

            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL);

            imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this, frameWorkerHandler);
            surface = imageReader.getSurface();
            mPreviewRequestBuilder.addTarget(surface);

            streamingStartedLatch = new CountDownLatch(1);


            CameraCaptureSession.StateCallback callback = new CameraCaptureSession.StateCallback()
            {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session)
                {
                    try
                    {
                        if (null == mCameraDevice)
                        {
                            return; // camera is already closed
                        }
                        cameraCaptureSession = session;

//                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
//                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
//                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(60,60));
//                        mPreviewRequestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, (long)16666666);

                        mPreviewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 250);
                        mPreviewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, (long)(exposureTime*1000*1000*1000));

                        cameraCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, cameraHardwareHandler);
                    }
                    catch (CameraAccessException e)
                    {
                        e.printStackTrace();
                    }
                    finally
                    {
                        streamingStartedLatch.countDown();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session)
                {
                }
            };

            mCameraDevice.createCaptureSession(Arrays.asList(surface), callback, cameraHardwareHandler);

            streamingStartedLatch.await();

            isStreaming = true;
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }

        sync.unlock();
    }

    @Override
    public void stopStreaming()
    {
        sync.lock();

        cleanupForEndStreaming();

        try {

            if (null != cameraCaptureSession) {
                cameraCaptureSession.close();
                cameraCaptureSession = null;
                isStreaming = false;
            }
        }
        finally
        {
            //stopCameraHardwareHandlerThread();
            //stopFrameWorkerHandlerThread();
            if (null != imageReader)
            {
                imageReader.close();
                imageReader = null;

                stopFrameWorkerHandlerThread();
            }

            sync.unlock();
        }
    }

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback()
    {
        @Override
        public void onOpened(CameraDevice cameraDevice)
        {
            mCameraDevice = cameraDevice;
            cameraOpenedLatch.countDown();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice)
        {
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error)
        {
            cameraDevice.close();
            mCameraDevice = null;
            cameraOpenedLatch.countDown();
        }

    };

    private void onPreviewFrame(Image image)
    {
        notifyStartOfFrameProcessing();

        int w = image.getWidth();
        int h = image.getHeight();

        Image.Plane[] planes = image.getPlanes();

        //assert(planes[0].getPixelStride() == 1);
        //assert(planes[2].getPixelStride() == 2);
        ByteBuffer y_plane = planes[0].getBuffer();
        ByteBuffer uv_plane1 = planes[1].getBuffer();
        ByteBuffer uv_plane2 = planes[2].getBuffer();
        Mat y_mat = new Mat(h, w, CvType.CV_8UC1, y_plane);
        Mat uv_mat1 = new Mat(h / 2, w / 2, CvType.CV_8UC2, uv_plane1);
        Mat uv_mat2 = new Mat(h / 2, w / 2, CvType.CV_8UC2, uv_plane2);
        long addr_diff = uv_mat2.dataAddr() - uv_mat1.dataAddr();
        if (addr_diff > 0)
        {
            //assert(addr_diff == 1);
            Imgproc.cvtColorTwoPlane(y_mat, uv_mat1, rgbMat, Imgproc.COLOR_YUV2RGBA_NV12);
        } else
        {
            //assert(addr_diff == -1);
            Imgproc.cvtColorTwoPlane(y_mat, uv_mat2, rgbMat, Imgproc.COLOR_YUV2RGBA_NV21);
        }

        image.close();

        handleFrame(rgbMat);
    }

    private void startFrameWorkerHandlerThread()
    {

        sync.lock();

        frameWorkerHandlerThread = new FixedHandlerThread("FrameWorkerHandlerThread");
        frameWorkerHandlerThread.start();

        frameWorkerHandler = new Handler(frameWorkerHandlerThread.getLooper());

        sync.unlock();
    }

    private void stopFrameWorkerHandlerThread()
    {
        sync.lock();

        if (frameWorkerHandlerThread != null)
        {
            frameWorkerHandlerThread.quit();
            frameWorkerHandlerThread.interrupt();
            joinUninterruptibly(frameWorkerHandlerThread);

            frameWorkerHandlerThread = null;
            frameWorkerHandler = null;
        }

        sync.unlock();
    }

    private void startCameraHardwareHandlerThread()
    {
        sync.lock();

        cameraHardwareHandlerThread = new FixedHandlerThread("CameraHardwareHandlerThread");
        cameraHardwareHandlerThread.start();
        cameraHardwareHandler = new Handler(cameraHardwareHandlerThread.getLooper());

        sync.unlock();
    }

    private void stopCameraHardwareHandlerThread()
    {
        sync.lock();

        if (cameraHardwareHandlerThread == null)
            return;

        cameraHardwareHandlerThread.quitSafely();
        cameraHardwareHandlerThread.interrupt();

        joinUninterruptibly(cameraHardwareHandlerThread);
        cameraHardwareHandlerThread = null;
        cameraHardwareHandler = null;

        sync.unlock();
    }

    @Override
    public void onImageAvailable(ImageReader reader)
    {
        try
        {
            /*
             * Note: this it is VERY important that we lock
             * interruptibly, because otherwise we can get
             * into a deadlock with the OpMode thread where
             * it's waiting for us to exit, but we can't exit
             * because we're waiting on this lock which the OpMode
             * thread is holding!!!
             */
            sync.lockInterruptibly();

            Image image = reader.acquireLatestImage();

            if(image != null)
            {
                /*
                 * For some reason, when we restart the streaming while live,
                 * the image returned from the image reader is somehow
                 * already closed and so onPreviewFrame() dies. Therefore,
                 * until we can figure out what's going on here, we simply
                 * catch and ignore the exception.
                 */
                try
                {
                    onPreviewFrame(image);
                }
                catch (IllegalStateException e)
                {
                    e.printStackTrace();
                }

                image.close();
            }

            sync.unlock();
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }
    }

    private void joinUninterruptibly(Thread thread)
    {
        boolean interrupted = false;

        while (true)
        {
            try
            {
                thread.join();
                break;
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
                interrupted = true;
            }
        }

        if(interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public int getMinSensorGain()
    {
        sync.lock();

        try
        {
            if(mCameraDevice == null)
            {
                throw new OpenCvCameraException("getMinSensorGain() called, but camera is not opened!");
            }

            return cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE).getLower();
        }
        finally
        {
            sync.unlock();
        }
    }

    @Override
    public int getMaxSensorGain()
    {
        sync.lock();

        try
        {
            if(mCameraDevice == null)
            {
                throw new OpenCvCameraException("getMinSensorGain() called, but camera is not opened!");
            }

            return cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE).getUpper();
        }
        finally
        {
            sync.unlock();
        }
    }

    @Override
    public void setSensorGain(int iso)
    {
        sync.lock();

        try
        {
            if(mCameraDevice == null)
            {
                throw new OpenCvCameraException("setSensorGain() called, but camera is not opened!");
            }

            mPreviewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            apply();
        }
        finally
        {
            sync.unlock();
        }
    }

    @Override
    public void setSensorFps(int sensorFps)
    {
        sync.lock();

        try
        {
            if(mCameraDevice == null)
            {
                throw new OpenCvCameraException("setSensorFps() called, but camera is not opened!");
            }

            long nanos = (long) ((1.0/sensorFps)*1e9);

            mPreviewRequestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, nanos);

            apply();
        }
        finally
        {
            sync.unlock();
        }
    }

    @Override
    public void setFlashlightEnabled(boolean enabled)
    {
        sync.lock();

        try
        {
            if(mCameraDevice == null)
            {
                throw new OpenCvCameraException("setSensorFps() called, but camera is not opened!");
            }

            if(enabled)
            {
                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            }
            else
            {
                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            }

            apply();
        }
        finally
        {
            sync.unlock();
        }
    }

    @Override
    public int getMinAutoExposureCompensation()
    {
        sync.lock();

        try
        {
            if(mCameraDevice == null)
            {
                throw new OpenCvCameraException("getMinAutoExposureCompensation() called, but camera is not opened!");
            }

            return cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE).getLower();
        }
        finally
        {
            sync.unlock();
        }
    }

    @Override
    public int getMaxAutoExposureCompensation()
    {
        sync.lock();

        try
        {
            if(mCameraDevice == null)
            {
                throw new OpenCvCameraException("getMaxAutoExposureCompensation() called, but camera is not opened!");
            }

            return cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE).getUpper();
        }
        finally
        {
            sync.unlock();
        }
    }

    @Override
    public void setAutoExposureCompensation(int aeCompensation)
    {
        sync.lock();

        try
        {
            if(mCameraDevice == null)
            {
                throw new OpenCvCameraException("setAutoExposureCompensation() called, but camera is not opened!");
            }

            if(aeCompensation < getMinAutoExposureCompensation())
            {
                throw new OpenCvCameraException("Auto exposure compensation must be >= the value returned by getMinAutoExposureCompensation()");
            }
            else if(aeCompensation > getMaxAutoExposureCompensation())
            {
                throw new OpenCvCameraException("Auto exposure compensation must be <= the value returned by getMaxAutoExposureCompensation()");
            }

            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeCompensation);

            apply();
        }
        finally
        {
            sync.unlock();
        }
    }

    @Override
    public void setExposureFractional(int denominator)
    {
        double exposureTimeSeconds = 1.0/denominator;
        long exposureTimeNanos = (long) (exposureTimeSeconds * (int) 1e9);

        setExposureNanos(exposureTimeNanos);
    }

    @Override
    public void setExposureNanos(long nanos)
    {
        sync.lock();

        try
        {
            if(mCameraDevice == null)
            {
                throw new OpenCvCameraException("setExposureNanos() called, but camera is not opened!");
            }

            mPreviewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, nanos);
            apply();
        }
        finally
        {
            sync.unlock();
        }
    }

    @Override
    public float getMinFocusDistance()
    {
        sync.lock();

        try
        {
            if(mCameraDevice == null)
            {
                throw new OpenCvCameraException("getMinFocusDistance() called, but camera is not opened!");
            }

            return cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        }
        finally
        {
            sync.unlock();
        }
    }

    @Override
    public void setFocusDistance(float diopters)
    {
        sync.lock();

        try
        {
            if(mCameraDevice == null)
            {
                throw new RuntimeException("setFocusDistance() called, but camera is not opened!");
            }
            else if(diopters < 0.0)
            {
                throw new RuntimeException("Focus distance must be >= 0.0!");
            }
            else if(diopters > getMinFocusDistance())
            {
                throw new RuntimeException("Focus distance must be <= the value returned by getMinFocusDistance()");
            }

            mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters);
            apply();
        }
        finally
        {
            sync.unlock();
        }
    }

    private void apply()
    {
        try
        {
            cameraCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, cameraHardwareHandler);
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }
}
