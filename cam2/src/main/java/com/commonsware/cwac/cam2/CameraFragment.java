/***
 Copyright (c) 2015-2016 CommonsWare, LLC

 Licensed under the Apache License, Version 2.0 (the "License"); you may
 not use this file except in compliance with the License. You may obtain
 a copy of the License at http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.commonsware.cwac.cam2;

import android.app.ActionBar;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.OrientationListener;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.SeekBar;
import com.github.clans.fab.FloatingActionButton;
import java.util.ArrayList;
import java.util.LinkedList;
import de.greenrobot.event.EventBus;

/**
 * Fragment for displaying a camera preview, with hooks to allow
 * you (or the user) to take a picture.
 */
public class CameraFragment extends Fragment {
  private static final String ARG_OUTPUT="output";
  private static final String ARG_UPDATE_MEDIA_STORE="updateMediaStore";
  private static final String ARG_SKIP_ORIENTATION_NORMALIZATION
    ="skipOrientationNormalization";
  private static final String ARG_IS_VIDEO="isVideo";
  private static final String ARG_QUALITY="quality";
  private static final String ARG_SIZE_LIMIT="sizeLimit";
  private static final String ARG_DURATION_LIMIT="durationLimit";
  private static final String ARG_ZOOM_STYLE="zoomStyle";
  private static final String ARG_FACING_EXACT_MATCH="facingExactMatch";
  private static final String ARG_CHRONOTYPE="chronotype";
  private static final int PINCH_ZOOM_DELTA=20;
  private CameraController ctlr;
  private ViewGroup previewStack;
  private ImageView fabPicture;
  //private FloatingActionButton fabPicture;
  //private FloatingActionButton fabFlashAuto;
  //private FloatingActionButton fabFlashOff;
  private FloatingActionButton fabFlashOn;
  private FloatingActionButton fabSwitchCamera;
  private FloatingActionButton fabBackToGallery;
  private View progress;
  private boolean mirrorPreview=false;
  private ScaleGestureDetector scaleDetector;
  private boolean inSmoothPinchZoom=true;
  private SeekBar zoomSlider;
  private OrientationListener mOrientationListener;
  private int prevOrientation;
  private boolean cameraSwitched = false;
  public static CameraFragment newPictureInstance(Uri output,
                                                  boolean updateMediaStore,
                                                  int quality,
                                                  ZoomStyle zoomStyle,
                                                  boolean facingExactMatch,
                                                  boolean skipOrientationNormalization) {
    CameraFragment f=new CameraFragment();
    Bundle args=new Bundle();

    args.putParcelable(ARG_OUTPUT, output);
    args.putBoolean(ARG_UPDATE_MEDIA_STORE, updateMediaStore);
    args.putBoolean(ARG_SKIP_ORIENTATION_NORMALIZATION,
      skipOrientationNormalization);
    args.putInt(ARG_QUALITY, quality);
    args.putBoolean(ARG_IS_VIDEO, false);
    args.putSerializable(ARG_ZOOM_STYLE, zoomStyle);
    args.putBoolean(ARG_FACING_EXACT_MATCH, facingExactMatch);
    f.setArguments(args);

    return(f);
  }

  public static CameraFragment newVideoInstance(Uri output,
                                                boolean updateMediaStore,
                                                int quality, int sizeLimit,
                                                int durationLimit,
                                                boolean facingExactMatch,
                                                ChronoType chronoType) {
    CameraFragment f=new CameraFragment();
    Bundle args=new Bundle();

    args.putParcelable(ARG_OUTPUT, output);
    args.putBoolean(ARG_UPDATE_MEDIA_STORE, updateMediaStore);
    args.putBoolean(ARG_IS_VIDEO, true);
    args.putInt(ARG_QUALITY, quality);
    args.putInt(ARG_SIZE_LIMIT, sizeLimit);
    args.putInt(ARG_DURATION_LIMIT, durationLimit);
    args.putBoolean(ARG_FACING_EXACT_MATCH, facingExactMatch);

    if (durationLimit>0 || chronoType!=ChronoType.COUNT_DOWN) {
      args.putSerializable(ARG_CHRONOTYPE, chronoType);
    }

    f.setArguments(args);

    return(f);
  }

  /**
   * Standard fragment entry point.
   *
   * @param savedInstanceState State of a previous instance
   */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setRetainInstance(true);
    scaleDetector=
      new ScaleGestureDetector(getActivity().getApplicationContext(),
        scaleListener);
  }

  /**
   * Standard lifecycle method, passed along to the CameraController.
   */
  @Override
  public void onStart() {
    super.onStart();

    EventBus.getDefault().register(this);

    if (ctlr!=null) {
      ctlr.start();
    }
  }

  @Override
  public void onHiddenChanged(boolean isHidden) {
    super.onHiddenChanged(isHidden);

    if (!isHidden) {
      ActionBar ab=getActivity().getActionBar();

      if (ab!=null) {
        ab.setBackgroundDrawable(getActivity()
            .getResources()
            .getDrawable(R.drawable.cwac_cam2_action_bar_bg_transparent));
        ab.setTitle("");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          ab.setDisplayHomeAsUpEnabled(false);
        }
        else {
          ab.setDisplayShowHomeEnabled(false);
          ab.setHomeButtonEnabled(false);
        }
      }

      if (fabPicture!=null) {
        fabPicture.setEnabled(true);
        fabSwitchCamera.setEnabled(canSwitchSources());
      }
    }
  }

  /**
   * Standard lifecycle method, for when the fragment moves into
   * the stopped state. Passed along to the CameraController.
   */
  @Override
  public void onStop() {
    if (ctlr!=null) {
      try {
        ctlr.stop();
      }
      catch (Exception e) {
        ctlr.postError(ErrorConstants.ERROR_STOPPING, e);
        Log.e(getClass().getSimpleName(), "Exception stopping controller", e);
      }
    }

    EventBus.getDefault().unregister(this);

    super.onStop();
  }

  /**
   * Standard lifecycle method, for when the fragment is utterly,
   * ruthlessly destroyed. Passed along to the CameraController,
   * because why should the fragment have all the fun?
   */
  @Override
  public void onDestroy() {
    if (ctlr!=null) {
      ctlr.destroy();
    }

    super.onDestroy();
    mOrientationListener.disable();
  }

  /**
   * Standard callback method to create the UI managed by
   * this fragment.
   *
   * @param inflater Used to inflate layouts
   * @param container Parent of the fragment's UI (eventually)
   * @param savedInstanceState State of a previous instance
   * @return the UI being managed by this fragment
   */
  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View v=inflater.inflate(R.layout.cwac_cam2_fragment, container, false);
    prevOrientation=0;
    previewStack=(ViewGroup)v.findViewById(R.id.cwac_cam2_preview_stack);
    progress=v.findViewById(R.id.cwac_cam2_progress);
    fabPicture=(ImageView)v.findViewById(R.id.cwac_cam2_picture);

    fabPicture.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        performCameraAction();
      }
    });
    fabFlashOn=(FloatingActionButton)v.findViewById(R.id.cwac_cam2_flash_button);

    fabSwitchCamera=(FloatingActionButton)v.findViewById(R.id.cwac_cam2_switch_camera);

    fabBackToGallery=(FloatingActionButton)v.findViewById(R.id.cwac_cam2_gallery);


    fabBackToGallery.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        getActivity().finish();
      }
    });

    fabSwitchCamera.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        progress.setVisibility(View.VISIBLE);
        fabSwitchCamera.setEnabled(true);
        if (fabFlashOn.isEnabled())
        {
          fabFlashOn.setEnabled(false);
        }
        else {
          fabFlashOn.setEnabled(true);
        }
        try {

          ctlr.switchCamera();
          if (cameraSwitched) {
            cameraSwitched=false;
          }
          else {
            cameraSwitched=true;
          }

        }
        catch (Exception e) {
          ctlr.postError(ErrorConstants.ERROR_SWITCHING_CAMERAS, e);
          Log.e(getClass().getSimpleName(), "Exception switching camera", e);
        }
      }

    });

    fabFlashOn.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        if (getController().getCurrentFlashMode()==FlashMode.TORCH)
        {
          try {
            getController().stop();
          } catch (Exception e) {
            e.printStackTrace();
          }
          ArrayList<FlashMode> newFlashMode = new ArrayList<>();
          newFlashMode.add(FlashMode.ALWAYS); // This is the flash mode you want to change to
          getController().getEngine().setPreferredFlashModes(newFlashMode);
          getController().start();
        }
        else
        {
          try {
            getController().stop();
          } catch (Exception e) {
            e.printStackTrace();
          }
          ArrayList<FlashMode> newFlashMode = new ArrayList<>();
          newFlashMode.add(FlashMode.TORCH); // This is the flash mode you want to change to
          getController().getEngine().setPreferredFlashModes(newFlashMode);
          getController().start();
        }
      }
    });

/*
    fabFlashAuto=(FloatingActionButton)v.findViewById(R.id.cwac_cam2_auto_flash_button);
    fabFlashOff=(FloatingActionButton)v.findViewById(R.id.cwac_cam2_no_flash_button);
    fabFlashOn=(FloatingActionButton)v.findViewById(R.id.cwac_cam2_flash_button);

    fabFlashAuto.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        try {
              getController().stop();
          } catch (Exception e) {
              e.printStackTrace();
          }
        ArrayList<FlashMode> newFlashMode = new ArrayList<>();
        newFlashMode.add(FlashMode.AUTO); // This is the flash mode you want to change to
        getController().getEngine().setPreferredFlashModes(newFlashMode);
        getController().start();
      }
    });

    fabFlashOff.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
          try {
              getController().stop();
          } catch (Exception e) {
              e.printStackTrace();
          }
        ArrayList<FlashMode> newFlashMode = new ArrayList<>();
        newFlashMode.add(FlashMode.OFF); // This is the flash mode you want to change to
        getController().getEngine().setPreferredFlashModes(newFlashMode);
        getController().start();
      }
    });

    fabFlashOn.setOnClickListener(new View.OnClickListener() {
      @Override
              public void onClick(View view) {
          try {
              getController().stop();
          } catch (Exception e) {
              e.printStackTrace();
          }
        ArrayList<FlashMode> newFlashMode = new ArrayList<>();
        newFlashMode.add(FlashMode.ALWAYS); // This is the flash mode you want to change to
        getController().getEngine().setPreferredFlashModes(newFlashMode);
        getController().start();
      }
    });
*/


    //changeMenuIconAnimation((FloatingActionMenu)v.findViewById(R.id.cwac_cam2_settings));

    onHiddenChanged(false); // hack, since this does not get
                            // called on initial display
    
    fabPicture.setEnabled(true);
    fabSwitchCamera.setEnabled(true);

    if (ctlr!=null && ctlr.getNumberOfCameras()>0) {
      prepController();
    }

    mOrientationListener = new OrientationListener(getActivity().getApplicationContext()) {
      @Override
      public void onOrientationChanged(int orientation) {

        switch (orientation) {
          case 0:
            if (prevOrientation!=0)
            {
              rotate(prevOrientation,orientation);
              prevOrientation=orientation;
            }
            break;
          case 90:
            if (prevOrientation!=90)
            {
              rotate(prevOrientation,orientation);
              prevOrientation=orientation;
            }
            break;
          case 180:
            if (prevOrientation!=180)
            {
              rotate(prevOrientation,orientation);
              prevOrientation=orientation;
            }
            break;
          case 270:
            if (prevOrientation!=270)
            {
              rotate(prevOrientation,orientation);
              prevOrientation=orientation;
            }
            break;
          default:
            break;
        }
      }
    };
    mOrientationListener.enable();

    return(v);
  }

  private void rotate(int from, int to) {
    Animation fab_rotate = null;
    switch (from) {
      case 0:
        if (to==90)
        {
          fab_rotate = AnimationUtils.
                  loadAnimation(getActivity().
                          getApplicationContext(),R.anim.
                          rotate_0_to_90);
        }
        else
        {
          if (to==270)
          {
            fab_rotate = AnimationUtils.
                    loadAnimation(getActivity().
                            getApplicationContext(),R.anim.
                            rotate_0_to_270);
          }
          else
          {
            fab_rotate = AnimationUtils.
                    loadAnimation(getActivity().
                            getApplicationContext(),R.anim.
                            rotate_0_to_180);
          }

        }
        break;
      case 90:
        if (to==180)
        {
          fab_rotate = AnimationUtils.
                  loadAnimation(getActivity().
                          getApplicationContext(),R.anim.
                          rotate_90_to_180);
        }
        else
        {
          if (to==0)
          {
            fab_rotate = AnimationUtils.
                    loadAnimation(getActivity().
                            getApplicationContext(),R.anim.
                            rotate_90_to_0);
          }
          else
          {
            fab_rotate = AnimationUtils.
                    loadAnimation(getActivity().
                            getApplicationContext(),R.anim.
                            rotate_90_to_270);
          }
        }
        break;
      case 180:
        if (to==270)
        {
          fab_rotate = AnimationUtils.
                  loadAnimation(getActivity().
                          getApplicationContext(),R.anim.
                          rotate_180_to_270);
        }
        else
        {
          if (to==90)
          {
            fab_rotate = AnimationUtils.
                    loadAnimation(getActivity().
                            getApplicationContext(),R.anim.
                            rotate_180_to_90);
          }
          else
          {
            fab_rotate = AnimationUtils.
                    loadAnimation(getActivity().
                            getApplicationContext(),R.anim.
                            rotate_180_to_0);
          }
        }
        break;
      case 270:
        if (to==0)
        {
          fab_rotate = AnimationUtils.
                  loadAnimation(getActivity().
                          getApplicationContext(),R.anim.
                          rotate_270_to_0);
        }
        else
        {
          if (to==180)
          {
            fab_rotate = AnimationUtils.
                    loadAnimation(getActivity().
                            getApplicationContext(),R.anim.
                            rotate_270_to_180);
          }
          else
          {
            fab_rotate = AnimationUtils.
                    loadAnimation(getActivity().
                            getApplicationContext(),R.anim.
                            rotate_270_to_90);
          }
        }
        break;
    }
    fabBackToGallery.startAnimation(fab_rotate);
    fabFlashOn.startAnimation(fab_rotate);
    fabSwitchCamera.startAnimation(fab_rotate);
  }

  public void shutdown() {
      progress.setVisibility(View.VISIBLE);
      if (ctlr!=null) {
        try {
          ctlr.stop();
        }
        catch (Exception e) {
          ctlr.postError(ErrorConstants.ERROR_STOPPING, e);
          Log.e(getClass().getSimpleName(),
            "Exception stopping controller", e);
        }
      }
  }

  /**
   * @return the CameraController this fragment delegates to
   */
  public CameraController getController() {
    return(ctlr);
  }

  /**
   * Establishes the controller that this fragment delegates to
   *
   * @param ctlr the controller that this fragment delegates to
   */
  public void setController(CameraController ctlr) {
    int currentCamera=-1;

    if (this.ctlr!=null) {
      currentCamera=this.ctlr.getCurrentCamera();
    }

    this.ctlr=ctlr;
    ctlr.setQuality(getArguments().getInt(ARG_QUALITY, 1));

    if (currentCamera>-1) {
      ctlr.setCurrentCamera(currentCamera);
    }
  }

  /**
   * Indicates if we should mirror the preview or not. Defaults
   * to false.
   *
   * @param mirror true if we should horizontally mirror the
   *               preview, false otherwise
   */
  public void setMirrorPreview(boolean mirror) {
    this.mirrorPreview=mirror;
  }

  @SuppressWarnings("unused")
  public void onEventMainThread(CameraController.ControllerReadyEvent event) {
    if (event.isEventForController(ctlr)) {
      prepController();
    }
  }

  @SuppressWarnings("unused")
  public void onEventMainThread(CameraEngine.OpenedEvent event) {
    if (event.exception==null) {
      progress.setVisibility(View.GONE);
      fabSwitchCamera.setEnabled(canSwitchSources());
      fabPicture.setEnabled(true);
      zoomSlider=(SeekBar)getView().findViewById(R.id.cwac_cam2_zoom);

      if (ctlr.supportsZoom()) {
        if (getZoomStyle()==ZoomStyle.PINCH) {
          previewStack.setOnTouchListener(
            new View.OnTouchListener() {
              @Override
              public boolean onTouch(View v, MotionEvent event) {
                return (scaleDetector.onTouchEvent(event));
              }
            });
        }
        else if (getZoomStyle()==ZoomStyle.SEEKBAR) {
          zoomSlider.setVisibility(View.VISIBLE);
          zoomSlider.setOnSeekBarChangeListener(seekListener);
        }
      }
      else {
        previewStack.setOnTouchListener(null);
        zoomSlider.setVisibility(View.GONE);
      }
    }
    else {
      ctlr.postError(ErrorConstants.ERROR_OPEN_CAMERA, event.exception);
      getActivity().finish();
    }
  }

  @SuppressWarnings("unused")
  public void onEventMainThread(CameraEngine.VideoTakenEvent event) {

    if (event.exception==null) {
      if (getArguments().getBoolean(ARG_UPDATE_MEDIA_STORE, false)) {
        final Context app=getActivity().getApplicationContext();
        Uri output=getArguments().getParcelable(ARG_OUTPUT);
        final String path=output.getPath();

        new Thread() {
          @Override
          public void run() {
            SystemClock.sleep(2000);
            MediaScannerConnection.scanFile(app,
              new String[]{path}, new String[]{"video/mp4"},
              null);
          }
        }.start();
      }

    }
    else if (getActivity().isFinishing()) {
      shutdown();
    }
    else {
      ctlr.postError(ErrorConstants.ERROR_VIDEO_TAKEN, event.exception);
      getActivity().finish();
    }
  }

  public void onEventMainThread(CameraEngine.SmoothZoomCompletedEvent event) {
    inSmoothPinchZoom=false;
    zoomSlider.setEnabled(true);
  }

  protected void performCameraAction() {
      takePicture();
  }

  private void takePicture() {
    Uri output=getArguments().getParcelable(ARG_OUTPUT);

    PictureTransaction.Builder b=new PictureTransaction.Builder();

    if (output!=null) {
      b.toUri(getActivity(), output,
          getArguments().getBoolean(ARG_UPDATE_MEDIA_STORE, false),
          getArguments().getBoolean(ARG_SKIP_ORIENTATION_NORMALIZATION, false));
    }

    fabPicture.setEnabled(false);
    fabSwitchCamera.setEnabled(false);
    ctlr.takePicture(b.build());


  }

  private boolean canSwitchSources() {
    return(!getArguments().getBoolean(ARG_FACING_EXACT_MATCH, false));
  }

  private boolean isVideo() {
    return(getArguments().getBoolean(ARG_IS_VIDEO, false));
  }

  private ChronoType getChronoType() {
    ChronoType chronoType=
      (ChronoType)getArguments().getSerializable(ARG_CHRONOTYPE);

    if (chronoType==null) {
      chronoType=ChronoType.NONE;
    }

    return(chronoType);
  }

  private void prepController() {
    LinkedList<CameraView> cameraViews=new LinkedList<CameraView>();
    CameraView cv=(CameraView)previewStack.getChildAt(0);

    cv.setMirror(mirrorPreview);
    cameraViews.add(cv);

    for (int i=1; i < ctlr.getNumberOfCameras(); i++) {
      cv=new CameraView(getActivity());
      cv.setVisibility(View.INVISIBLE);
      cv.setMirror(mirrorPreview);
      previewStack.addView(cv);
      cameraViews.add(cv);
    }

    ctlr.setCameraViews(cameraViews);
  }


  private ZoomStyle getZoomStyle() {
    ZoomStyle result=(ZoomStyle)getArguments().getSerializable(ARG_ZOOM_STYLE);

    if (result==null) {
      result=ZoomStyle.NONE;
    }

    return(result);
  }

  public boolean getCameraSwitched() {
    return cameraSwitched;
  }

  private ScaleGestureDetector.OnScaleGestureListener scaleListener=
    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
      @Override
      public void onScaleEnd(ScaleGestureDetector detector) {
        float scale=detector.getScaleFactor();
        int delta;

        if (scale>1.0f) {
          delta=PINCH_ZOOM_DELTA;
        }
        else if (scale<1.0f) {
          delta=-1*PINCH_ZOOM_DELTA;
        }
        else {
          return;
        }

        if (!inSmoothPinchZoom) {
          if (ctlr.changeZoom(delta)) {
            inSmoothPinchZoom=true;
          }
        }
      }
    };

  private SeekBar.OnSeekBarChangeListener seekListener=
    new SeekBar.OnSeekBarChangeListener() {
      boolean fromUser;
      int progress;
      @Override
      public void onProgressChanged(SeekBar seekBar,
                                    int progress,
                                    boolean fromUser) {
        this.fromUser=fromUser;
        this.progress=progress;
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {
        // no-op
      }

      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {
        if (fromUser) {
          if (ctlr.setZoom(progress)) {
            seekBar.setEnabled(false);
          }
        }
      }
    };

}