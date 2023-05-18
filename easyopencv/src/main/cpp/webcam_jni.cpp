/*
 * Copyright (c) 2020 OpenFTC Team
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

#include <jni.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include "turbojpeg.h"

using namespace cv;

extern "C"
JNIEXPORT void JNICALL
Java_org_openftc_easyopencv_OpenCvWebcamImpl_yuy2BufToRgbaMat(JNIEnv *env, jclass clazz,
                                                              jlong buf, jint width, jint height, jlong rgbaMatPtr)
{
    Mat rawSensorMat(height, width, CV_8UC2, (void*)buf);
    Mat* rgba = (Mat*) rgbaMatPtr;

    cvtColor(rawSensorMat, *rgba, COLOR_YUV2RGBA_YUY2, 4);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_openftc_easyopencv_OpenCvWebcamImpl_mjpegBufToRgbaMat(JNIEnv *env, jclass clazz,
                                                              jlong buf, jint bufSize, jint width, jint height, jlong rgbaMatPtr)
{
    Mat* rgba = (Mat*) rgbaMatPtr;

    tjhandle decompressor = tj3Init(TJINIT_DECOMPRESS);

    // We decompress DIRECTLY into the image buffer of the mat
    tj3Decompress8(decompressor, (uint8_t*) buf, bufSize, rgba->data, 0, TJPF_RGBA);

    tj3Destroy(decompressor);
}