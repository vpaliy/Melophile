package com.vpaliy.melophile.ui.player;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.request.target.ImageViewTarget;
import com.google.gson.reflect.TypeToken;
import com.ohoussein.playpause.PlayPauseView;
import com.vpaliy.domain.playback.QueueManager;
import com.vpaliy.melophile.App;
import com.vpaliy.melophile.R;
import com.vpaliy.melophile.playback.PlaybackManager;
import com.vpaliy.melophile.playback.service.MusicPlaybackService;
import com.vpaliy.melophile.ui.base.BaseActivity;
import com.vpaliy.melophile.ui.utils.BundleUtils;
import com.vpaliy.melophile.ui.utils.Constants;
import com.vpaliy.melophile.ui.utils.PresentationUtils;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.v4.content.ContextCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import jp.wasabeef.blurry.Blurry;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import javax.inject.Inject;
import butterknife.BindView;
import butterknife.OnClick;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

public class PlayerActivity extends BaseActivity {

    private static final String TAG=PlayerActivity.class.getSimpleName();

    @BindView(R.id.background)
    protected ImageView background;

    @BindView(R.id.end_time)
    protected TextView endTime;

    @BindView(R.id.start_time)
    protected TextView startTime;

    @BindView(R.id.circle)
    protected ImageView smallImage;

    @BindView(R.id.artist)
    protected TextView artist;

    @BindView(R.id.track_name)
    protected TextView trackName;

    @BindView(R.id.progressView)
    protected SeekBar progress;

    @BindView(R.id.play_pause)
    protected PlayPauseView playPause;

    @BindView(R.id.pages)
    protected TextView pages;

    @BindView(R.id.shuffle)
    protected ImageView shuffle;

    @BindView(R.id.repeat)
    protected ImageView repeat;

    private boolean isInjected;

    private String lastArtUrl;

    private static final long PROGRESS_UPDATE_INTERNAL = 100;
    private static final long PROGRESS_UPDATE_INITIAL_INTERVAL = 10;

    private final ScheduledExecutorService executorService =
            Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> scheduledFuture;

    private PlaybackStateCompat lastState;
    private Handler handler=new Handler();

    private MediaBrowserCompat browserCompat;
    private MediaBrowserCompat.ConnectionCallback connectionCallback=new MediaBrowserCompat.ConnectionCallback(){
        @Override
        public void onConnected()  {
            super.onConnected();
            MediaSessionCompat.Token token=browserCompat.getSessionToken();
            try {
                MediaControllerCompat mediaController =new MediaControllerCompat(PlayerActivity.this, token);
                // Save the controller
                mediaController.registerCallback(controllerCallback);
                MediaControllerCompat.setMediaController(PlayerActivity.this, mediaController);
                //inject the passed query
                inject();
            }catch (RemoteException ex){
                ex.printStackTrace();
            }
        }

    };

    private MediaControllerCompat.Callback controllerCallback=new MediaControllerCompat.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            updatePlaybackState(state);
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            super.onMetadataChanged(metadata);
            updateDuration(metadata);
            updateArt(metadata);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlist);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        postponeEnterTransition();
        loadArt();
        browserCompat=new MediaBrowserCompat(this,
                new ComponentName(this, MusicPlaybackService.class), connectionCallback,null);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                startTime.setText(DateUtils.formatElapsedTime(progress/1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                stopSeekBarUpdate();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                getControls().seekTo(seekBar.getProgress());
                startSeekBarUpdate();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if(browserCompat!=null) {
            browserCompat.connect();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        lastArtUrl=null;
        if(browserCompat!=null){
            browserCompat.disconnect();
        }
        MediaControllerCompat controllerCompat=MediaControllerCompat.getMediaController(this);
        if(controllerCompat!=null){
            controllerCompat.unregisterCallback(controllerCallback);
        }
    }

    private void startSeekBarUpdate(){
        scheduledFuture = executorService.scheduleAtFixedRate(()-> handler.post(PlayerActivity.this::updateProgress),
                PROGRESS_UPDATE_INITIAL_INTERVAL, PROGRESS_UPDATE_INTERNAL, TimeUnit.MILLISECONDS);
    }

    private void stopSeekBarUpdate(){
        lastState=null;
        if(scheduledFuture !=null) {
            scheduledFuture.cancel(false);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopSeekBarUpdate();
        executorService.shutdown();
    }

    @OnClick(R.id.play_pause)
    public void playPause(){
        lastState=null;
        MediaControllerCompat controllerCompat=MediaControllerCompat.getMediaController(this);
        PlaybackStateCompat stateCompat=controllerCompat.getPlaybackState();
        if(stateCompat!=null){
            MediaControllerCompat.TransportControls controls=
                    controllerCompat.getTransportControls();
            switch (stateCompat.getState()){
                case PlaybackStateCompat.STATE_PLAYING:
                case PlaybackStateCompat.STATE_BUFFERING:
                    controls.pause();
                    break;
                case PlaybackStateCompat.STATE_NONE:
                case PlaybackStateCompat.STATE_PAUSED:
                case PlaybackStateCompat.STATE_STOPPED:
                    controls.play();
                    break;
                default:
                    Log.d(TAG, "State "+stateCompat.getState());
            }
        }
    }

    public void updatePlaybackState(PlaybackStateCompat stateCompat){
        if(stateCompat==null) return;
        lastState=stateCompat;
        updateRepeatMode(isActionApplied(stateCompat.getActions(),
                PlaybackStateCompat.ACTION_SET_REPEAT_MODE));
        updateShuffleMode(isActionApplied(stateCompat.getActions(),
                PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE_ENABLED));
        //check the state
        switch (stateCompat.getState()){
            case PlaybackStateCompat.STATE_PLAYING:
                playPause.setVisibility(VISIBLE);
                if(playPause.isPlay()){
                    playPause.change(false,true);
                }
                startSeekBarUpdate();
                break;
            case PlaybackStateCompat.STATE_PAUSED:
                // mControllers.setVisibility(VISIBLE);
                // mLoading.setVisibility(INVISIBLE);
                playPause.setVisibility(VISIBLE);
                if(!playPause.isPlay()){
                    playPause.change(true,true);
                }
                stopSeekBarUpdate();
                break;
            case PlaybackStateCompat.STATE_NONE:
            case PlaybackStateCompat.STATE_STOPPED:
                playPause.setVisibility(VISIBLE);
                if(playPause.isPlay()){
                    playPause.change(false,true);
                }
                stopSeekBarUpdate();
                break;
            case PlaybackStateCompat.STATE_BUFFERING:
                playPause.setVisibility(INVISIBLE);
                stopSeekBarUpdate();
                break;
            default:
                Log.d(TAG, "Unhandled state "+stateCompat.getState());
        }
    }

    private boolean isActionApplied(long actions, long action){
        return (actions & action) !=0;
    }

    @Inject
    public void updateQueue(PlaybackManager manager){
        final Intent intent=getIntent();
        if(intent!=null) {
            QueueManager queueManager = BundleUtils.fetchHeavyObject(new TypeToken<QueueManager>() {}.
                    getType(), intent.getExtras(),Constants.EXTRA_QUEUE);
            if (queueManager != null) {
                manager.setQueueManager(queueManager);
                manager.handleResumeRequest();
                playPause.change(false);
            }
        }
    }

    @OnClick(R.id.next)
    public void playNext(){
        transportControls().skipToNext();
    }

    @OnClick(R.id.prev)
    public void playPrev(){
        transportControls().skipToPrevious();
    }

    private MediaControllerCompat.TransportControls transportControls(){
        MediaControllerCompat controllerCompat=MediaControllerCompat.getMediaController(this);
        return controllerCompat.getTransportControls();
    }

    private void updateProgress() {
        if (lastState == null) return;
        long currentPosition = lastState.getPosition();
        if (lastState.getState() == PlaybackStateCompat.STATE_PLAYING) {
            long timeDelta = SystemClock.elapsedRealtime() -
                    lastState.getLastPositionUpdateTime();
            currentPosition += (int) timeDelta * lastState.getPlaybackSpeed();
        }
        if (progress != null) {
            progress.setProgress((int) currentPosition);
            startTime.setText(DateUtils.formatElapsedTime(progress.getProgress() / 1000));
        }
    }

    @Override
    public void inject() {
        App.appInstance().appComponent().inject(this);
    }

    @Override
    public void handleEvent(@NonNull Object event) {
    }

    private void loadArt(){
        Bundle bundle=getIntent().getExtras();
        if(bundle!=null){
            String art=bundle.getString(Constants.EXTRA_DATA,null);
            if(art!=null){
                showArt(art);
            }
        }
    }

    private MediaControllerCompat.TransportControls getControls(){
        return MediaControllerCompat.getMediaController(this).getTransportControls();
    }

    public void showArt(String artUrl){
        if(!TextUtils.equals(lastArtUrl,artUrl)) {
            lastArtUrl=artUrl;
            Glide.with(this)
                    .load(artUrl)
                    .asBitmap()
                    .priority(Priority.IMMEDIATE)
                    .into(new ImageViewTarget<Bitmap>(smallImage) {
                        @Override
                        protected void setResource(Bitmap resource) {
                            smallImage.setImageBitmap(resource);
                            smallImage.post(()->{
                                Blurry.with(PlayerActivity.this)
                                        .async(bitmap->{
                                            background.setImageDrawable(bitmap);
                                            supportStartPostponedEnterTransition();
                                        })
                                        .from(resource)
                                        .into(background);
                            });
                        }
                    });
        }
    }

    private void updateShuffleMode(boolean isShuffled){
        int color=isShuffled ? ContextCompat.getColor(this,R.color.enabled_action) :
                ContextCompat.getColor(this,R.color.white_50);
        PresentationUtils.setDrawableColor(shuffle,color);
    }

    private void updateRepeatMode(boolean isRepeat){
        int color=isRepeat ? ContextCompat.getColor(this,R.color.enabled_action) :
                ContextCompat.getColor(this,R.color.white_50);
        PresentationUtils.setDrawableColor(repeat,color);
    }

    @OnClick(R.id.repeat)
    public void setRepeat(){
        transportControls().setRepeatMode(0);
    }

    @OnClick(R.id.shuffle)
    public void shuffle(){
        transportControls().setShuffleModeEnabled(true);
    }

    private void updateDuration(MediaMetadataCompat metadataCompat){
        if(metadataCompat==null) return;
        int duration=(int)metadataCompat.getLong(MediaMetadataCompat.METADATA_KEY_DISC_NUMBER);
        startTime.setText(DateUtils.formatElapsedTime(duration/1000));
        duration=(int)metadataCompat.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
        endTime.setText(DateUtils.formatElapsedTime(duration/1000));
        progress.setMax(duration);
    }

    private void updateArt(MediaMetadataCompat metadataCompat){
        if(metadataCompat==null) return;
        String text=Long.toString(metadataCompat.getLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER))
                +" of "+Long.toString(metadataCompat.getLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS));
        trackName.setText(metadataCompat.getText(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE));
        artist.setText(metadataCompat.getText(MediaMetadataCompat.METADATA_KEY_ARTIST));
        pages.setText(text);
        String imageUrl=metadataCompat.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI);
        showArt(imageUrl);
    }
}
