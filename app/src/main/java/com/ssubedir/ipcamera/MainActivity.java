package com.ssubedir.ipcamera;

import android.Manifest;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.protobuf.ByteString;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.StrictMode;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

public class MainActivity extends AppCompatActivity {

    private ManagedChannel channel = ManagedChannelBuilder.forAddress("192.168.1.46",9000).usePlaintext().build();
    private IpCameraGrpc.IpCameraBlockingStub ipCameraBlockingStub =IpCameraGrpc.newBlockingStub(channel);
    private IpCameraGrpc.IpCameraStub asyncStub =IpCameraGrpc.newStub(channel);
    StreamObserver<StreamVideoRequest> requestObserver;

    private boolean isRecording = false;

    public static final String LOG_TAG = "IpCamera";
    public static Surface surface = null;

    CameraService[] myCameras = null;

    private CameraManager mCameraManager = null;
    private final int CAMERA1 = 0;

    public static TextureView mImageView = null;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler = null;

    private MediaCodec mCodec = null; // encoder
    Surface mEncoderSurface; // Surface as data input for encoder
    BufferedOutputStream outputStream;
    ByteBuffer outPutByteBuffer;

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }



    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        setContentView(R.layout.activity_main);


        Log.d(LOG_TAG, "Request permission");
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                ||
                (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
        ) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }

        mImageView = findViewById(R.id.textureView);
        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                if(!isRecording){
                    Snackbar.make(view, "Started Streaming", Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();
                    isRecording = true;

                    setUpMediaCodec();

                    if (myCameras[CAMERA1] != null) {
                        if (!myCameras[CAMERA1].isOpen()) myCameras[CAMERA1].openCamera();
                    }

                }else{
                    Snackbar.make(view, "Stopped Streaming", Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();

                    try {
                        requestObserver.onCompleted(); // close stream
                        channel.shutdown().awaitTermination(10, TimeUnit.SECONDS); // close connection
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    isRecording = false;
                    if (mCodec != null) {
                        myCameras[CAMERA1].stopStreamingVideo();
                    }
                }
            }
        });



        mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            // Getting a list of cameras from a device

            myCameras = new CameraService[mCameraManager.getCameraIdList().length];

            for (String cameraID : mCameraManager.getCameraIdList()) {
                Log.i(LOG_TAG, "cameraID: " + cameraID);
                int id = Integer.parseInt(cameraID);
                // create a handler for the camera
                myCameras[id] = new CameraService(mCameraManager, cameraID);

            }
        } catch (CameraAccessException e) {
            Log.e(LOG_TAG, e.getMessage());
            e.printStackTrace();
        }

    }


    public class CameraService {


        private String mCameraID;
        private CameraDevice mCameraDevice = null;
        private CameraCaptureSession mSession;
        private CaptureRequest.Builder mPreviewBuilder;


        public CameraService(CameraManager cameraManager, String cameraID) {

            mCameraManager = cameraManager;
            mCameraID = cameraID;

        }

        private CameraDevice.StateCallback mCameraCallback = new CameraDevice.StateCallback() {

            @Override
            public void onOpened(CameraDevice camera) {
                mCameraDevice = camera;
                Log.i(LOG_TAG, "Open camera  with id:" + mCameraDevice.getId());

                startCameraPreviewSession();
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                mCameraDevice.close();

                Log.i(LOG_TAG, "Disconnect camera  with id:" + mCameraDevice.getId());
                mCameraDevice = null;
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                Log.i(LOG_TAG, "error! camera id:" + camera.getId() + " error:" + error);
            }
        };

        private void startCameraPreviewSession() {
            SurfaceTexture texture = mImageView.getSurfaceTexture();
            texture.setDefaultBufferSize(320, 240);
            surface = new Surface(texture);


            try {

                mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mPreviewBuilder.addTarget(surface);
                mPreviewBuilder.addTarget(mEncoderSurface);


                mCameraDevice.createCaptureSession(Arrays.asList(surface, mEncoderSurface),

                        new CameraCaptureSession.StateCallback() {

                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                mSession = session;

                                try {
                                    mSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
                                } catch (CameraAccessException e) {
                                    e.printStackTrace();
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                            }
                        }, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

        }


        public boolean isOpen() {
            if (mCameraDevice == null) {
                return false;
            } else {
                return true;
            }
        }


        @RequiresApi(api = Build.VERSION_CODES.M)
        public void openCamera() {
            try {

                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {

                    mCameraManager.openCamera(mCameraID, mCameraCallback, mBackgroundHandler);

                }

            } catch (CameraAccessException e) {
                Log.i(LOG_TAG, e.getMessage());

            }
        }

        public void closeCamera() {

            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }

        public void stopStreamingVideo() {

            if (mCameraDevice != null & mCodec != null) {

                try {
                    mSession.stopRepeating();
                    mSession.abortCaptures();
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }

                mCodec.stop();
                mCodec.release();
                mEncoderSurface.release();
                closeCamera();
            }
        }
    }


    @RequiresApi(api = Build.VERSION_CODES.M)
    private void setUpMediaCodec() {
        requestObserver = asyncStub.withDeadlineAfter(5, TimeUnit.MINUTES)
                .streamVideo(new StreamObserver<StreamVideoResponse>() {

                    @Override
                    public void onNext(StreamVideoResponse value) {
                        Log.i("IpCamera", "received stream response");
                    }

                    @Override
                    public void onError(Throwable t) {
                        Log.i("IpCamera", "streaming failed");
                    }

                    @Override
                    public void onCompleted() {
                        Log.i("IpCamera", "streaming ended");
                    }
                });


        try {
            mCodec = MediaCodec.createEncoderByType("video/avc"); //H264 codec

        } catch (Exception e) {
            Log.i(LOG_TAG, "error creating media codec");
        }

        int width = 320; // video width
        int height = 240; // video height
        int colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface; // color input format
        int videoBitrate = 500000; // video bit rate in bps (bit per second)
        int videoFramePerSecond = 20; // FPS
        int iframeInterval = 3; // I-Frame interval in seconds

        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFramePerSecond);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframeInterval);


        mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); // configure the codec as an encoder
        mEncoderSurface = mCodec.createInputSurface();// get the encoder Surface
        mCodec.setCallback(new EncoderCallback());
        mCodec.start(); // run the encoder
        Log.i(LOG_TAG, "launched the codec");

    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private class EncoderCallback extends MediaCodec.Callback implements MediaCodec.OnFrameRenderedListener {

        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {

        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            outPutByteBuffer = mCodec.getOutputBuffer(index);
            byte[] outDate = new byte[info.size];
            outPutByteBuffer.get(outDate);
            try {
                StreamVideoRequest request = StreamVideoRequest.newBuilder()
                            .setVideo(ByteString.copyFrom(outDate))
                            .build();
                    requestObserver.onNext(request);
                    Log.i("IpCamera", "sent h264 chunk to server");
            } catch (Exception e) {
                Log.i("IpCamera", "unexpected error:"+ e.getMessage());
                requestObserver.onError(e);
            }
            
            mCodec.releaseOutputBuffer(index, false);
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Log.i(LOG_TAG, "Error: " + e);
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            Log.i(LOG_TAG, "encoder output format changed: " + format);
        }

        @Override
        public void onFrameRendered(@NonNull MediaCodec codec, long presentationTimeUs, long nanoTime) {

        }
    }

    @Override
    public void onPause() {
        if (myCameras[CAMERA1].isOpen()) {
            myCameras[CAMERA1].closeCamera();
        }

        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
