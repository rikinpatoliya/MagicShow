package com.zero.magicshow.core.widget;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;

import com.zero.magicshow.common.base.MagicBaseView;
import com.zero.magicshow.common.iface.GravityCallBack;
import com.zero.magicshow.common.utils.BaseUtil;
import com.zero.magicshow.common.utils.CameraBitmapUtil;
import com.zero.magicshow.common.utils.GravityUtil;
import com.zero.magicshow.common.utils.MagicParams;
import com.zero.magicshow.common.utils.OpenGlUtils;
import com.zero.magicshow.common.utils.Rotation;
import com.zero.magicshow.common.utils.SavePictureTask;
import com.zero.magicshow.common.utils.TextureRotationUtil;
import com.zero.magicshow.core.camera.CameraEngine;
import com.zero.magicshow.core.camera.utils.CameraInfo;
import com.zero.magicshow.core.encoder.video.TextureMovieEncoder;
import com.zero.magicshow.core.filter.advanced.MagicBeautyFilter;
import com.zero.magicshow.core.filter.base.MagicCameraInputFilter;
import com.zero.magicshow.core.filter.base.gpuimage.GPUImageFilter;
import com.zero.magicshow.core.filter.utils.MagicFilterType;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Created by why8222 on 2016/2/25.
 */
public class MagicCameraView extends MagicBaseView {

    private MagicCameraInputFilter cameraInputFilter;
    private MagicBeautyFilter beautyFilter;

    private SurfaceTexture surfaceTexture;

    public MagicCameraView(Context context) {
        this(context, null);
    }

    private boolean recordingEnabled;
    private int recordingStatus;

    private static final int RECORDING_OFF = 0;
    private static final int RECORDING_ON = 1;
    private static final int RECORDING_RESUMED = 2;
    private static TextureMovieEncoder videoEncoder = new TextureMovieEncoder();

    private File outputFile;
    private int afterShootDegree = 90;//默认必须是90,为什么？不告诉你
    private int frontShootDegree = -90;//默认必须是90,为什么？不告诉你

    public MagicCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.getHolder().addCallback(this);
        outputFile = new File(MagicParams.videoPath,MagicParams.videoName);
        recordingStatus = -1;
        recordingEnabled = false;
        scaleType = ScaleType.CENTER_CROP;
        GravityUtil.getInstance().init(getContext(),gravityCallBack);
        GravityUtil.getInstance().start((Activity) getContext());
        setZOrderOnTop(true);
        setZOrderMediaOverlay(true);
    }
    private GravityCallBack gravityCallBack = new GravityCallBack() {
        @Override
        public void onGravityChange(int direction) {
            Log.e("HongLi","direction:" + direction);
            if(direction == GravityUtil.DIRECTION_LAND_LEFT){
                afterShootDegree = 0;
            }else if(direction == GravityUtil.DIRECTION_LAND_RIGHT){
                afterShootDegree = 0;
            }else if(direction == GravityUtil.DIRECTION_PORTRAIT_POSITIVE){
                afterShootDegree = 90;
            }else if(direction == GravityUtil.DIRECTION_PORTRAIT_NEGATIVE){
                afterShootDegree = -90;
            }
        }
    };
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        super.onSurfaceCreated(gl, config);
        recordingEnabled = videoEncoder.isRecording();
        if (recordingEnabled)
            recordingStatus = RECORDING_RESUMED;
        else
            recordingStatus = RECORDING_OFF;
        if(cameraInputFilter == null)
            cameraInputFilter = new MagicCameraInputFilter();
        cameraInputFilter.init();
        if (textureId == OpenGlUtils.NO_TEXTURE) {
            textureId = OpenGlUtils.getExternalOESTextureID();
            if (textureId != OpenGlUtils.NO_TEXTURE) {
                surfaceTexture = new SurfaceTexture(textureId);
                surfaceTexture.setOnFrameAvailableListener(onFrameAvailableListener);
            }
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        super.onSurfaceChanged(gl, width, height);
        openCamera();
    }
    @Override
    public void onDrawFrame(GL10 gl) {
        super.onDrawFrame(gl);
        if(surfaceTexture == null)
            return;
        surfaceTexture.updateTexImage();
        if (recordingEnabled) {
            switch (recordingStatus) {
                case RECORDING_OFF:
                    CameraInfo info = CameraEngine.getCameraInfo();
                    if(null == info){
                        return;
                    }
                    videoEncoder.setPreviewSize(info.previewWidth, info.pictureHeight);
                    videoEncoder.setTextureBuffer(gLTextureBuffer);
                    videoEncoder.setCubeBuffer(gLCubeBuffer);
                    videoEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
                            outputFile, info.previewWidth, info.pictureHeight,
                            1000000, EGL14.eglGetCurrentContext(),
                            info));
                    recordingStatus = RECORDING_ON;
                    break;
                case RECORDING_RESUMED:
                    videoEncoder.updateSharedContext(EGL14.eglGetCurrentContext());
                    recordingStatus = RECORDING_ON;
                    break;
                case RECORDING_ON:
                    break;
                default:
                    throw new RuntimeException("unknown status " + recordingStatus);
            }
        } else {
            switch (recordingStatus) {
                case RECORDING_ON:
                case RECORDING_RESUMED:
                    videoEncoder.stopRecording();
                    recordingStatus = RECORDING_OFF;
                    break;
                case RECORDING_OFF:
                    break;
                default:
                    throw new RuntimeException("unknown status " + recordingStatus);
            }
        }
        float[] mtx = new float[16];
        surfaceTexture.getTransformMatrix(mtx);
        cameraInputFilter.setTextureTransformMatrix(mtx);
        int id = textureId;
        if(filter == null){
            cameraInputFilter.onDrawFrame(textureId, gLCubeBuffer, gLTextureBuffer);
        }else{
            id = cameraInputFilter.onDrawToTexture(textureId);
            filter.onDrawFrame(id, gLCubeBuffer, gLTextureBuffer);
        }
        videoEncoder.setTextureId(id);
        videoEncoder.frameAvailable(surfaceTexture);
    }

    private SurfaceTexture.OnFrameAvailableListener onFrameAvailableListener = new SurfaceTexture.OnFrameAvailableListener() {

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            requestRender();
        }
    };

    @Override
    public void setFilter(MagicFilterType type) {
        super.setFilter(type);
        videoEncoder.setFilter(type);
    }

    private void openCamera(){
        if(CameraEngine.getCamera() == null)
            CameraEngine.openCamera();
        CameraInfo info = CameraEngine.getCameraInfo();
        if(info == null){
            return;
        }
        if(info.orientation == 90 || info.orientation == 270){
            imageWidth = info.previewHeight;
            imageHeight = info.previewWidth;
        }else{
            imageWidth = info.previewWidth;
            imageHeight = info.previewHeight;
        }
        cameraInputFilter.onInputSizeChanged(imageWidth, imageHeight);
        adjustSize(info.orientation, info.isFront, true);
        if(surfaceTexture != null)
            CameraEngine.startPreview(surfaceTexture);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        super.surfaceDestroyed(holder);
        CameraEngine.releaseCamera(true);
    }

    public void changeRecordingState(boolean isRecording) {
        recordingEnabled = isRecording;
    }

    protected void onFilterChanged(){
        super.onFilterChanged();
        cameraInputFilter.onDisplaySizeChanged(surfaceWidth, surfaceHeight);
        if(filter != null)
            cameraInputFilter.initCameraFrameBuffer(imageWidth, imageHeight);
        else
            cameraInputFilter.destroyFramebuffers();
    }

    @Override
    public void savePicture(final SavePictureTask savePictureTask) {
        final long startTakeTime = System.nanoTime() / 1000000;
        CameraEngine.takePicture(null, null, new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                CameraEngine.stopPreview();
//                CameraEngine.releaseCamera();
                final Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                Log.e("HongLi","end take:" + (System.nanoTime() / 1000000 - startTakeTime));
                queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        final long startDrawTime = System.nanoTime() / 1000000;
                        final Bitmap photo = drawPhoto(bitmap,null != CameraEngine.getCameraInfo() && CameraEngine.getCameraInfo().isFront);
                        Log.e("HongLi","end darw:" + (System.nanoTime() / 1000000 - startDrawTime));
                        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
                        if (photo != null){
                            savePictureTask.execute(photo);
                        }
                        CameraEngine.releaseCamera(true);
                    }
                });
//                CameraEngine.startPreview();
//                CameraEngine.releaseCamera();
            }
        });
    }

    private Bitmap drawPhoto(Bitmap bitmap,boolean isRotated){
//        if(afterShootDegree != 0 && !isRotated){
//            //需要旋转角度
//            Log.e("HongLi","需要旋转:" + afterShootDegree);
//            bitmap = BaseUtil.rotateBitmapByDegree(bitmap,afterShootDegree);
//        }else if(frontShootDegree !=0 && isRotated){
//            Log.e("HongLi","需要旋转:" + frontShootDegree);
//            bitmap = BaseUtil.rotateBitmapByDegree(bitmap,frontShootDegree);
//        }
        bitmap = CameraBitmapUtil.handlerCameraBitmap((Activity) getContext(),bitmap,CameraEngine.cameraID);
        BaseUtil.saveBitmap(bitmap,"/sdcard/DCIM/test3.jpg");
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        Log.e("HongLi","width:" + width + ";height:" + height);
        final int[] mFrameBuffers = new int[1];
        final int[] mFrameBufferTextures = new int[1];
//        if(beautyFilter == null)
//            beautyFilter = new MagicBeautyFilter();
        //TODO 此处不需要任何滤镜，只是使用父类即可
        GPUImageFilter beautyFilter = new GPUImageFilter();
        beautyFilter.init();
        beautyFilter.onDisplaySizeChanged(width, height);
        beautyFilter.onInputSizeChanged(width, height);

        if(filter != null) {
            filter.onInputSizeChanged(width, height);
            filter.onDisplaySizeChanged(width, height);
        }
        GLES20.glGenFramebuffers(1, mFrameBuffers, 0);
        GLES20.glGenTextures(1, mFrameBufferTextures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mFrameBufferTextures[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        final int textureId = OpenGlUtils.loadTexture(bitmap, OpenGlUtils.NO_TEXTURE, true);

        FloatBuffer gLCubeBuffer = ByteBuffer.allocateDirect(TextureRotationUtil.CUBE.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        FloatBuffer gLTextureBuffer = ByteBuffer.allocateDirect(TextureRotationUtil.TEXTURE_NO_ROTATION.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        gLCubeBuffer.put(TextureRotationUtil.CUBE).position(0);
//        if(isRotated)
//            gLTextureBuffer.put(TextureRotationUtil.getRotation(Rotation.NORMAL, false, false)).position(0);
//        else
//            gLTextureBuffer.put(TextureRotationUtil.getRotation(Rotation.NORMAL, false, true)).position(0);
        gLTextureBuffer.put(TextureRotationUtil.getRotation(Rotation.NORMAL, false, true)).position(0);

        GLES20.glViewport(0, 0, width, height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameBuffers[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, mFrameBufferTextures[0], 0);
        if(filter == null){
            beautyFilter.onDrawFrame(textureId, gLCubeBuffer, gLTextureBuffer);
        }else{
            beautyFilter.onDrawFrame(textureId);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameBuffers[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, mFrameBufferTextures[0], 0);
            filter.onDrawFrame(mFrameBufferTextures[0], gLCubeBuffer, gLTextureBuffer);
        }
        final IntBuffer ib = IntBuffer.allocate(width * height);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ib);
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.copyPixelsFromBuffer(ib);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
        GLES20.glDeleteFramebuffers(mFrameBuffers.length, mFrameBuffers, 0);
        GLES20.glDeleteTextures(mFrameBufferTextures.length, mFrameBufferTextures, 0);
        beautyFilter.destroy();
        beautyFilter = null;
        if(filter != null) {
            filter.onDisplaySizeChanged(surfaceWidth, surfaceHeight);
            filter.onInputSizeChanged(imageWidth, imageHeight);
        }
//        result = BaseUtil.rotateBitmapByDegree(result,-afterShootDegree);
        return result;
    }

    public void onBeautyLevelChanged() {
        cameraInputFilter.onBeautyLevelChanged();
    }
}
