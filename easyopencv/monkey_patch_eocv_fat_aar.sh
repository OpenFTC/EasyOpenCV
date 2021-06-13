#! /bin/sh

pwd_tmp=$PWD

TMP_DIR=monkey_patch_tmp
OUTPUT_NAME=eocv-fataar.aar

OCV_REPACKAGED_ROOT_DIR=../../OpenCV-Repackaged
OCV_REPACKAGED_DIR=$OCV_REPACKAGED_ROOT_DIR/OpenCV-Andorid-SDK
OCV_AAR_EXTRACT_DIR=$TMP_DIR/opencv_repackaged_extracted
OCV_CLASSES_DIR=$TMP_DIR/opencv_repackaged_classes

EOCV_AAR_EXTRACT_DIR=$TMP_DIR/eocv_extracted

# Create temporary DIR
mkdir $TMP_DIR

# Remove any previous monkey patch output
rm $OUTPUT_NAME

# Grab the OpenCV Repackaged compiled classes out of the AAR
unzip -q $OCV_REPACKAGED_DIR/build/outputs/aar/OpenCV-Andorid-SDK-debug.aar -d $OCV_AAR_EXTRACT_DIR
unzip -q $OCV_AAR_EXTRACT_DIR/classes.jar -d $OCV_CLASSES_DIR

# Remove the dynamic native lib loading stuff
rm -rf $OCV_CLASSES_DIR/org/openftc/

# Unzip the EOCV AAR
unzip -q build/outputs/aar/easyopencv-debug.aar -d $EOCV_AAR_EXTRACT_DIR

# Copy in the OpenCV native libraries
cp $OCV_REPACKAGED_ROOT_DIR/doc/native_libs/armeabi-v7a/libopencv_java4.so $EOCV_AAR_EXTRACT_DIR/jni/armeabi-v7a/
cp $OCV_REPACKAGED_ROOT_DIR/doc/native_libs/arm64-v8a/libopencv_java4.so $EOCV_AAR_EXTRACT_DIR/jni/arm64-v8a/

# Copy the OpenCV classes into the classes.jar for EOCV
cd $OCV_CLASSES_DIR
zip -q -r $pwd_tmp/$EOCV_AAR_EXTRACT_DIR/classes.jar ./*

# Re-zip EOCV
cd $pwd_tmp/$EOCV_AAR_EXTRACT_DIR
zip -q -r $pwd_tmp/$OUTPUT_NAME ./*

# Nuke the TMP DIR
rm -rf $pwd_tmp/$TMP_DIR
