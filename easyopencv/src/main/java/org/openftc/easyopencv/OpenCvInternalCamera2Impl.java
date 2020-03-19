package org.openftc.easyopencv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Range;
import android.view.Surface;

import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

@SuppressLint({"NewApi", "MissingPermission"})
public class OpenCvInternalCamera2Impl extends OpenCvCameraBase implements OpenCvInternalCamera2
{
    CameraDevice mCameraDevice;

    HandlerThread cameraHardwareHandlerThread;
    private Handler cameraHardwareHandler;

    HandlerThread frameWorkerHandlerThread;
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

    public OpenCvInternalCamera2Impl(OpenCvInternalCamera2.CameraDirection direction)
    {
        this.direction = direction;
    }

    public OpenCvInternalCamera2Impl(OpenCvInternalCamera2.CameraDirection direction, int containerLayoutId)
    {
        super(containerLayoutId);
        this.direction = direction;
    }

    @Override
    protected OpenCvCameraRotation getDefaultRotation()
    {
        return OpenCvCameraRotation.UPRIGHT;
    }

    @Override
    protected int mapRotationEnumToOpenCvRotateCode(OpenCvCameraRotation rotation)
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

    @Override
    public synchronized void openCameraDevice()
    {
        if(isOpen)
        {
            return;
        }

        if(mCameraDevice == null)
        {
            try
            {
                startCameraHardwareHandlerThread();

                CameraManager manager = (CameraManager) AppUtil.getInstance().getActivity().getSystemService(Context.CAMERA_SERVICE);
                String camList[] = manager.getCameraIdList();
                String camId = camList[0];
                cameraOpenedLatch = new CountDownLatch(1);
                manager.openCamera(camId, mStateCallback, cameraHardwareHandler);

                cameraOpenedLatch.await();
                isOpen = true;
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    @Override
    public synchronized void closeCameraDevice()
    {
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
    }

    @Override
    public void startStreaming(int width, int height)
    {
        startStreaming(width, height, OpenCvCameraRotation.UPRIGHT);
    }

    @Override
    public void startStreaming(int width, int height, OpenCvCameraRotation rotation)
    {
        System.out.println("startStreaming() ENTER");

        /*
         * If we're already streaming, then that's OK, but we need to stop
         * streaming in the old mode before we can restart in the new one.
         */
        if(isStreaming)
        {
            frameWorkerHandlerThread.quit();
            frameWorkerHandlerThread.interrupt();

            stopStreaming();
        }

        synchronized (this)
        {
            prepareForStartStreaming(width, height, rotation);

            try
            {
                rgbMat = new Mat(height, width, CvType.CV_8UC3);

                startFrameWorkerHandlerThread();

                imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
                imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener()
                {
                    @Override
                    public void onImageAvailable(ImageReader reader)
                    {
                        System.out.println("onImageAvailable() ENTER");

                        if(!frameWorkerHandlerThread.isInterrupted())
                        {
                            synchronized (OpenCvInternalCamera2Impl.this)
                            {
                                if(frameWorkerHandlerThread.isInterrupted())
                                {
                                    System.out.println("onImageAvailable() EXIT");
                                    return;
                                }

                                Image image = reader.acquireLatestImage();
                                if (image == null)
                                {
                                    return;
                                }

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
                        }

                        System.out.println("onImageAvailable() EXIT");

                    }
                }, frameWorkerHandler);

                surface = imageReader.getSurface();

                mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mPreviewRequestBuilder.addTarget(surface);

                streamingStartedLatch = new CountDownLatch(1);

                CameraCaptureSession.StateCallback callback = new CameraCaptureSession.StateCallback()
                {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session)
                    {
                        try
                        {
                            System.out.println("STATECALLBACK: onConfigured()");

                            if (null == mCameraDevice)
                            {
                                return; // camera is already closed
                            }
                            cameraCaptureSession = session;

                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(60,60));
                            mPreviewRequestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, (long)16666666);

                            //mPreviewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 250);
                            //mPreviewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, (long)(exposureTime*1000*1000*1000));

                            cameraCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, cameraHardwareHandler);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                        finally
                        {
                            System.out.println("STATECALLBACK: releasing latch");
                            streamingStartedLatch.countDown();
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session)
                    {
                        System.out.println("STATECALLBACK: onConfigureFailed()");
                    }
                };

                mCameraDevice.createCaptureSession(Arrays.asList(surface), callback, cameraHardwareHandler);

                System.out.println("Awaiting STATECALLBACK latch");
                streamingStartedLatch.await();

                isStreaming = true;
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            System.out.println("startStreaming() EXIT");
        }
    }

    @Override
    public synchronized void stopStreaming()
    {
        System.out.println("stopStreaming() ENTER");

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
            stopFrameWorkerHandlerThread();
            if (null != imageReader)
            {
                imageReader.close();
                imageReader = null;

                //stopFrameWorkerHandlerThread();
            }
        }

        System.out.println("stopStreaming() EXIT");
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

    private synchronized void startFrameWorkerHandlerThread()
    {
        frameWorkerHandlerThread = new HandlerThread("FrameWorker");
        frameWorkerHandlerThread.start();
        frameWorkerHandler = new Handler(frameWorkerHandlerThread.getLooper());
    }

    private synchronized void stopFrameWorkerHandlerThread()
    {
        System.out.println("stopFrameWorkerHandlerThread() ENTER");

        if (frameWorkerHandlerThread != null)
        {
            System.out.println("stopFrameWorkerHandlerThread() quit()");
            frameWorkerHandlerThread.quit();

            System.out.println("stopFrameWorkerHandlerThread() interrupt()");
            frameWorkerHandlerThread.interrupt();

            try
            {
                System.out.println("stopFrameWorkerHandlerThread() join()");
                frameWorkerHandlerThread.join();
                frameWorkerHandlerThread = null;
                frameWorkerHandler = null;
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        }

        System.out.println("stopFrameWorkerHandlerThread() EXIT");
    }

    private synchronized void startCameraHardwareHandlerThread()
    {
        cameraHardwareHandlerThread = new HandlerThread("OpenCVCameraBackground");
        cameraHardwareHandlerThread.start();
        cameraHardwareHandler = new Handler(cameraHardwareHandlerThread.getLooper());
    }

    private synchronized void stopCameraHardwareHandlerThread()
    {
        if (cameraHardwareHandlerThread == null)
            return;

        cameraHardwareHandlerThread.quitSafely();
        cameraHardwareHandlerThread.interrupt();

        try
        {
            cameraHardwareHandlerThread.join();
            cameraHardwareHandlerThread = null;
            cameraHardwareHandler = null;
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }
}
