/**
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.pages.main.conversation.views.row.message.views;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.support.annotation.CallSuper;
import android.support.annotation.DrawableRes;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.view.LayoutInflater;
import android.view.View;
import com.waz.api.ImageAsset;
import com.waz.api.KindOfMedia;
import com.waz.api.LoadHandle;
import com.waz.api.MediaAsset;
import com.waz.api.Message;
import com.waz.api.UpdateListener;
import com.waz.zclient.R;
import com.waz.zclient.controllers.mediaplayer.DefaultMediaPlayer;
import com.waz.zclient.controllers.mediaplayer.MediaPlayerState;
import com.waz.zclient.controllers.selection.MessageActionModeController;
import com.waz.zclient.controllers.streammediaplayer.IStreamMediaPlayerController;
import com.waz.zclient.controllers.streammediaplayer.StreamMediaPlayerObserver;
import com.waz.zclient.core.stores.network.NetworkStoreObserver;
import com.waz.zclient.pages.main.conversation.views.MessageViewsContainer;
import com.waz.zclient.pages.main.conversation.views.row.message.RetryMessageViewController;
import com.waz.zclient.pages.main.conversation.views.row.separator.Separator;
import com.waz.zclient.ui.views.TouchFilterableLayout;
import com.waz.zclient.ui.views.TouchFilterableLinearLayout;
import com.waz.zclient.utils.MessageUtils;
import com.waz.zclient.utils.ThreadUtils;
import com.waz.zclient.utils.ViewUtils;
import com.waz.zclient.views.media.MediaPlayerView;

public abstract class MediaPlayerViewController extends RetryMessageViewController implements MediaPlayerView.MediaPlayerListener,
                                                                                              StreamMediaPlayerObserver,
                                                                                              NetworkStoreObserver,
                                                                                              View.OnLongClickListener,
                                                                                              MessageActionModeController.Selectable {

    private TouchFilterableLinearLayout view;
    private TextMessageWithTimestamp textWithTimestamp;
    protected MediaPlayerView mediaPlayerView;
    private boolean updateProgressEnabled;
    private LoadHandle loadHandle;
    private MediaAsset mediaAsset;
    private ImageAsset imageAsset;
    private final long refreshRate;
    private final UpdateListener imageAssetUpdateListener = new UpdateListener() {
        @Override
        public void updated() {
            if (mediaAsset == null ||
                imageAsset == null ||
                message == null) {
                return;
            }
            final int bitmapWidth = view.getMeasuredWidth() > 0 ?
                                    view.getMeasuredWidth() :
                                    ViewUtils.getOrientationIndependentDisplayWidth(context);
            loadHandle = imageAsset.getBitmap(bitmapWidth, new ImageAsset.BitmapCallback() {
                @Override
                public void onBitmapLoaded(Bitmap bitmap, boolean isPreview) {
                    if (isPreview ||
                        message == null) {
                        return;
                    }
                    updateMediaPlayerView(bitmap);
                }

                @Override
                public void onBitmapLoadingFailed() {
                    if (message == null) {
                        return;
                    }
                    showError();
                }
            });
        }
    };
    private final Runnable timeUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaAsset == null ||
                getPlayerController() == null) {
                return;
            }
            if (mediaPlayerView == null ||
                message == null ||
                !getPlayerController().isSelectedMessage(message) ||
                !getPlayerController().getMediaPlayerState(message).isTimeUpdateScheduleAllowed() ||
                mediaAsset.getDuration() == null) {
                return;
            }
            updateTime();
            mediaPlayerView.postDelayed(this, refreshRate);
        }
    };

    @SuppressLint("InflateParams")
    public MediaPlayerViewController(Context context, MessageViewsContainer messageViewsContainer) {
        super(context, messageViewsContainer);
        refreshRate = context.getResources().getInteger(R.integer.mediaplayer__time_refresh_rate_ms);
        updateProgressEnabled = true;
        LayoutInflater inflater = LayoutInflater.from(context);
        view = (TouchFilterableLinearLayout) inflater.inflate(R.layout.row_conversation_media_player, null);
        textWithTimestamp = ViewUtils.getView(view, R.id.tmwt__message_and_timestamp);
        textWithTimestamp.setMessageViewsContainer(messageViewsContainer);
        textWithTimestamp.setOnLongClickListener(this);
        mediaPlayerView = ViewUtils.getView(view, R.id.mpv__row_conversation__message_media_player);
        mediaPlayerView.setOnLongClickListener(this);
        resetMediaPlayerView(R.string.mediaplayer__artist__placeholder, getSource());
        afterInit();
    }

    @Override
    protected void onSetMessage(Separator separator) {
        super.onSetMessage(separator);
        textWithTimestamp.setMessage(message);
        messageViewsContainer.getStoreFactory().getNetworkStore().addNetworkControllerObserver(this);
        getPlayerController().addStreamMediaObserver(this);
        resetMediaPlayerView(R.string.mediaplayer__artist__placeholder, getSource());
        mediaPlayerView.setMediaPlayerListener(this);
        mediaPlayerView.setSourceImage(getSourceImage());
        updated();
    }

    @Override
    public void updated() {
        super.updated();
        final Message.Part mediaPart = MessageUtils.getFirstRichMediaPart(message);
        if (imageAsset != null) {
            imageAsset.removeUpdateListener(imageAssetUpdateListener);
            imageAsset = null;
        }
        if (mediaPart == null) {
            showError();
            return;
        }
        mediaAsset = mediaPart.getMediaAsset();
        if (mediaAsset == null ||
            mediaAsset.isEmpty()) {
            showError();
            return;
        }
        imageAsset = mediaAsset.getArtwork();
        imageAsset.addUpdateListener(imageAssetUpdateListener);
        imageAssetUpdateListener.updated();
    }

    @Override
    public void recycle() {
        unscheduleTimeUpdate();
        textWithTimestamp.recycle();
        mediaPlayerView.setSeekBarEnabled(false);
        if (getPlayerController() != null) {
            getPlayerController().removeStreamMediaObserver(this);
            messageViewsContainer.getStoreFactory().getNetworkStore().removeNetworkControllerObserver(this);
        }
        resetMediaPlayerView(R.string.mediaplayer__artist__placeholder, getSource());
        if (loadHandle != null) {
            loadHandle.cancel();
            loadHandle = null;
        }
        if (imageAsset != null) {
            imageAsset.removeUpdateListener(imageAssetUpdateListener);
            imageAsset = null;
        }
        mediaAsset = null;
        updateProgressEnabled = true;
        super.recycle();
    }

    @SuppressLint("ResourceAsColor")
    private void resetMediaPlayerView(@StringRes int artist, @StringRes int title) {
        mediaPlayerView.setMediaPlayerListener(null);
        mediaPlayerView.setTime(0, 100);
        mediaPlayerView.setSeekBarEnabled(false);
        mediaPlayerView.setImage(null);
        mediaPlayerView.setAllowControl(true);
        mediaPlayerView.updateControl(MediaPlayerState.Idle);
        mediaPlayerView.setControlVisibility(View.INVISIBLE);

        mediaPlayerView.setLoadingIndicatorEnabled(true);
        mediaPlayerView.setMusicIndicatorVisibility(View.GONE);
        mediaPlayerView.setCircleColor(R.color.mediaplayer__seekbar_placeholder_circle);
        mediaPlayerView.setCircleStrokeWidth(R.dimen.mediaplayer__seekbar_small_stroke_width);

        mediaPlayerView.setArtist(artist);
        mediaPlayerView.setTitle(title);
        mediaPlayerView.hideHint();
    }

    @Nullable
    public TouchFilterableLayout getView() {
        return view;
    }

    private void updateTime() {
        if (!updateProgressEnabled) {
            return;
        }
        ThreadUtils.checkMain();
        int currentPosition = getPlayerController().getPosition(message);
        if (currentPosition == DefaultMediaPlayer.PLAYING_NOT_STARTED) {
            currentPosition = 0;
        }
        mediaPlayerView.setTime(currentPosition, getDuration());
    }

    public void scheduleTimeUpdate() {
        unscheduleTimeUpdate();
        mediaPlayerView.postDelayed(timeUpdateRunnable, refreshRate);
    }

    public void unscheduleTimeUpdate() {
        if (mediaPlayerView == null ||
            mediaPlayerView.getHandler() == null) {
            return;
        }
        mediaPlayerView.removeCallbacks(timeUpdateRunnable);
        updateTime();
    }

    ///////////////////////////////////////////////////////////////////
    //
    //  Implementation of MediaPlayerView.MediaPlayerListener
    //
    ///////////////////////////////////////////////////////////////////

    @Override
    public void onSeekStart() {
        updateProgressEnabled = false;
    }

    @Override
    public void onSeekEnd(int positionMs) {
        updateProgressEnabled = true;
        getPlayerController().seekTo(message, positionMs);
    }

    @Override
    public void onControlClicked() {
        if (mediaAsset == null ||
            mediaAsset.getKind() != KindOfMedia.TRACK) {
            return;
        }
        final MediaPlayerState mediaPlayerState = getMediaPlayerState();
        if (mediaPlayerState == MediaPlayerState.Started) {
            pause();
        } else {
            play();
        }
    }

    @Override
    public void onOpenExternalClicked() {
        if (mediaAsset != null) {
            openExternal();
        } else {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(message.getBody())));
        }
    }

    ///////////////////////////////////////////////////////////////////
    //
    //  Implementation of StreamMediaPlayerManager().StreamMediaPlayerListener
    //
    ///////////////////////////////////////////////////////////////////

    @Override
    public void onPlay(Message m) {
        if (getPlayerController() == null ||
            !getPlayerController().isSelectedMessage(message)) {
            return;
        }
        scheduleTimeUpdate();
        mediaPlayerView.setAllowControl(true);
        mediaPlayerView.updateControl(MediaPlayerState.Started);
        if (getMediaPlayerState().isSeekToAllowed()) {
            mediaPlayerView.setSeekBarEnabled(isSeekingEnabled());
        }
    }

    @Override
    public void onPause(Message m) {
        if (getPlayerController() == null ||
            !getPlayerController().isSelectedMessage(message)) {
            return;
        }
        mediaPlayerView.updateControl(MediaPlayerState.Paused);
        unscheduleTimeUpdate();
    }

    @Override
    public void onStop(Message m) {
        if (getPlayerController() == null ||
            !getPlayerController().isSelectedMessage(message)) {
            return;
        }
        mediaPlayerView.updateControl(MediaPlayerState.Stopped);
        unscheduleTimeUpdate();
        mediaPlayerView.setSeekBarEnabled(false);
    }

    @Override
    public void onPrepared(Message m) {
        if (getPlayerController() == null ||
            !getPlayerController().isSelectedMessage(message)) {
            return;
        }
        mediaPlayerView.updateControl(MediaPlayerState.Prepared);
        mediaPlayerView.setSeekBarEnabled(isSeekingEnabled());
    }

    @Override
    public void onError(Message m) {
        if (getPlayerController() == null ||
            !getPlayerController().isSelectedMessage(message)) {
            return;
        }
        unscheduleTimeUpdate();
        if (!messageViewsContainer.getStoreFactory().getNetworkStore().hasInternetConnection()) {
            messageViewsContainer.getStoreFactory().getNetworkStore().notifyNetworkAccessFailed();
            return;
        }
        showError();
    }

    @Override
    @CallSuper
    public void onComplete(Message m) {
        if (getPlayerController() == null ||
            !getPlayerController().isSelectedMessage(message)) {
            return;
        }
        mediaPlayerView.updateControl(MediaPlayerState.PlaybackCompleted);
        unscheduleTimeUpdate();
        mediaPlayerView.setTime(0, 100);
    }

    @Override
    public void onTrackChanged(Message newMessage) {
        if (getPlayerController() == null ||
            getPlayerController().isSelectedMessage(message)) {
            scheduleTimeUpdate();
            return;
        }
        unscheduleTimeUpdate();
        mediaPlayerView.updateControl(MediaPlayerState.Stopped);
        mediaPlayerView.setSeekBarEnabled(false);
    }

    ///////////////////////////////////////////////////////////////////
    //
    //  Implementation of IMediaProvider.OnInformationAvailableListener
    //
    ///////////////////////////////////////////////////////////////////

    @SuppressLint("ResourceAsColor")
    protected void updateMediaPlayerView(Bitmap bitmap) {
        if (getPlayerController() == null || mediaAsset == null) {
            return;
        }
        mediaPlayerView.setLoadingIndicatorEnabled(false);
        mediaPlayerView.setImage(bitmap);
        mediaPlayerView.setCircleColor(R.color.mediaplayer__seekbar_circle_color);
        mediaPlayerView.setCircleStrokeWidth(R.dimen.mediaplayer__seekbar__stroke_width);
        mediaPlayerView.setArtist(mediaAsset.getArtistName());
        mediaPlayerView.setTitle(mediaAsset.getTitle());

        final MediaPlayerState mediaPlayerState = getMediaPlayerState();
        mediaPlayerView.setAllowControl(true);
        mediaPlayerView.updateControl(mediaPlayerState);
        if (mediaAsset.getKind() != KindOfMedia.TRACK) {
            mediaPlayerView.setSeekBarEnabled(false);
            mediaPlayerView.setLoadingIndicatorEnabled(false);
            mediaPlayerView.setCircleStrokeWidth(R.dimen.mediaplayer__seekbar_small_stroke_width);
            mediaPlayerView.setCircleColor(R.color.mediaplayer__seekbar_placeholder_circle);
            mediaPlayerView.setAllowControl(false);
            mediaPlayerView.showHint(R.string.mediaplayer__open_external);
        }
        updateTime();
        if (mediaPlayerState == MediaPlayerState.Started) {
            scheduleTimeUpdate();
        }
        if (mediaPlayerState.isSeekToAllowed()) {
            mediaPlayerView.setSeekBarEnabled(isSeekingEnabled());
        }
    }

    private MediaPlayerState getMediaPlayerState() {
        return getPlayerController().getMediaPlayerState(message);
    }

    @Override
    public void onPlaceholderTap() {
        final MediaPlayerState mediaPlayerState = getMediaPlayerState();
        if (mediaPlayerState.needInitialization()) {
            // Error case
            resetMediaPlayerView(R.string.mediaplayer__artist__error, getSource());
            mediaPlayerView.setMediaPlayerListener(this);
            mediaPlayerView.setSourceImage(getSourceImage());
            updated();
        } else {
            onControlClicked();
        }
    }

    @SuppressLint("ResourceAsColor")
    protected void showError() {
        mediaPlayerView.setSeekBarEnabled(false);
        mediaPlayerView.setControlVisibility(View.INVISIBLE);
        mediaPlayerView.setLoadingIndicatorEnabled(false);
        mediaPlayerView.setMusicIndicatorVisibility(View.VISIBLE);
        mediaPlayerView.setTime(0, 100);
        mediaPlayerView.setImage(null);
        mediaPlayerView.setCircleStrokeWidth(R.dimen.mediaplayer__seekbar_small_stroke_width);
        mediaPlayerView.setCircleColor(R.color.mediaplayer__seekbar_placeholder_circle);
        mediaPlayerView.setTitle(getSource());
        mediaPlayerView.setArtist(R.string.mediaplayer__artist__error);
        mediaPlayerView.setAllowControl(false);
    }

    protected IStreamMediaPlayerController getPlayerController() {
        if (messageViewsContainer == null ||
            messageViewsContainer.isTornDown()) {
            return null;
        }
        return messageViewsContainer.getControllerFactory().getStreamMediaPlayerController();
    }

    ///////////////////////////////////////////////////////////////////
    //
    //  Implementation of NetworkStoreObserver
    //
    ///////////////////////////////////////////////////////////////////


    @Override
    public void onConnectivityChange(boolean hasInternet) {
        if (messageViewsContainer == null ||
            messageViewsContainer.isTornDown()) {
            return;
        }
        if (!hasInternet) {
            if (getPlayerController().isSelectedMessage(message)) {
                pause();
            }
        } else {
            updated();
        }
    }

    @Override
    public void onNetworkAccessFailed() { }

    @Override
    public void onAccentColorHasChanged(Object sender, int color) {
        super.onAccentColorHasChanged(sender, color);
        if (mediaPlayerView == null) {
            return;
        }
        mediaPlayerView.setProgressColor(color);
    }

    protected void openExternal() {
        Intent intent = new Intent(Intent.ACTION_VIEW, mediaAsset.getLinkUri());
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    protected int getDuration() {
        return mediaAsset == null || mediaAsset.getDuration() == null ? 0 : (int) mediaAsset.getDuration().toMillis();
    }

    protected void play() {
        if (mediaPlayerView == null ||
            getPlayerController() == null) {
            return;
        }
        if (!messageViewsContainer.getStoreFactory().getNetworkStore().hasInternetConnection()) {
            messageViewsContainer.getStoreFactory().getNetworkStore().notifyNetworkAccessFailed();
            return;
        }
        mediaPlayerView.setAllowControl(false);
        getPlayerController().play(message, mediaAsset);
    }

    protected void pause() {
        mediaPlayerView.updateControl(getPlayerController().getMediaPlayerState(message));
        getPlayerController().pause(message.getConversationId());
    }

    @Override
    public boolean onHintClicked() {
        if (mediaAsset.getKind() != KindOfMedia.TRACK) {
            onOpenExternalClicked();
            return true;
        }
        return false;
    }

    @Override
    public boolean onLongClick(View v) {
        if (message == null ||
            messageViewsContainer == null ||
            messageViewsContainer.getControllerFactory() == null ||
            messageViewsContainer.getControllerFactory().isTornDown()) {
            return false;
        }
        messageViewsContainer.getControllerFactory().getMessageActionModeController().selectMessage(message);
        return true;
    }

    ///////////////////////////////////////////////////////////////////
    //
    //  Abstract methods
    //
    ///////////////////////////////////////////////////////////////////

    @StringRes protected abstract int getSource();

    @DrawableRes protected abstract int getSourceImage();

    protected abstract boolean isSeekingEnabled();
}
