/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing - which it all manages through cooperative multi-tasking
 * on a single thread. It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    private final List<FrameEffect> effects;
    private final Queue<PendingAudio> pendingAudio = new ArrayDeque<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private VideoFramePipeline pipeline;
    private VideoFramePipeline.VideoResult preparedFrame;
    private long nextSequence;
    private boolean playbackOpen;
    private boolean pauseRequested;
    private boolean audioOutputEnabled;
    private boolean audioLineChecked;
    private boolean metadataNotified;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private int intFrameRate;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameEffect> effects,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
    	
        audioOutputEnabled = audioEnabled;
        
        // Store the selected effects for the new pipeline.
        this.effects = List.copyOf(effects);

        
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        if (pipeline != null) {
            pipeline.clearAudio();
        }
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logical = clampSeekTimestamp(timestampUs);
        startPlayback(displayableSeekTimestamp(logical), pauseRequested, logical);
    }

 // Restarting creates a fresh session, so an old worker cannot corrupt a seek. //
    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long logicalTimestampUs) {
        closePlaybackResources();
        pauseRequested = initiallyPaused;
        currentTimestampUs = logicalTimestampUs != NO_SEEK_REQUEST
                ? logicalTimestampUs : startTimestampUs;
        logicalPlaybackBaseUs = currentTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        nextSequence = 0;
        audioLineChecked = false;
        metadataNotified = false;
        pendingAudio.clear();
        preparedFrame = null;

        notifyStatusChanged(videoFile.getName());
        pipeline = new VideoFramePipeline(videoFile, startTimestampUs, effects);
        playbackOpen = true;
        pipeline.start(); // starts background decoder; returns immediately
        playbackTimer.start();
        notifyPlaybackStateChanged();
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    /** Called by the JavaFX AnimationTimer; all work here must be non-blocking. */
    private void pumpPlayback(long now) {
        VideoFramePipeline session = pipeline;
        if (!playbackOpen || session == null) {
            return;
        }
        if (session.failure() != null) {
            handlePlaybackError(session.failure());
            return;
        }

        // Metadata is published safely by the decoder using a volatile field.
        InfoVideo info = session.videoInfo();
        if (info != null && !metadataNotified) {
            metadataNotified = true;
            videoFrameDurationUs = info.frameDurationUs();
            intFrameRate = info.intFrameRate();
            notifyVideoSizeChanged(info.imageWidth(), info.imageHeight());
        }
        long duration = session.durationUs();
        if (duration > 0) {
            videoDurationUs = duration;
        }
        configureAudioIfReady(session);
        drainNewAudio(session);

        // Only the *next* sequence number may be presented; fast jobs cannot
        // jump ahead of a slower job and break frame order.
        if (preparedFrame == null) {
            preparedFrame = session.takeFrame(nextSequence);
        }
        if (preparedFrame != null) {
            if (playbackStartNs == 0) {
                firstTimestampUs = preparedFrame.timestampUs();
                playbackStartNs = now;
                if (pauseRequested) {
                    pauseStartedNs = now;
                }
            }
            long frameOffsetUs = Math.max(0, preparedFrame.timestampUs() - firstTimestampUs);
            long targetNs = playbackStartNs + frameOffsetUs * 1_000L;
            boolean firstFrameWhilePaused = pauseRequested && nextSequence == 0;
            if (firstFrameWhilePaused || (!pauseRequested && now >= targetNs)) {
                displayPreparedFrame(preparedFrame, targetNs);
                preparedFrame = null;
                nextSequence++;
                session.releaseDisplayedFrame();
            }
        }

        writeDueAudio(now, session);
        // Decoder EOF is not enough: complete every submitted video and audio packet.
        if (session.decodingFinished()
                && nextSequence >= session.submittedFrameCount()
                && preparedFrame == null
                && !session.hasQueuedAudio()
                && pendingAudio.isEmpty()) {
            finishPlayback();
        }
    }

    private void displayPreparedFrame(VideoFramePipeline.VideoResult frame, long targetNs) {
        currentTimestampUs = logicalPlaybackBaseUs
                + Math.max(0, frame.timestampUs() - firstTimestampUs);
        relativeSeekBaseUs = NO_SEEK_REQUEST;
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (targetNs - frame.preparedAtNs()) / 1_000L;
            System.out.printf("Frame headroom is %5dus out of %dus spare.%n",
                    remainingUs, videoFrameDurationUs);
        }
        notifyFrameReady(frame.image());
    }

    private void configureAudioIfReady(VideoFramePipeline session) {
        if (audioLineChecked || session.audioFormat() == null) {
            return;
        }
        audioLineChecked = true;
        VideoFramePipeline.AudioFormatInfo format = session.audioFormat();
        try {
            if (format.sampleRate() <= 0 || format.channels() <= 0) {
                throw new IllegalArgumentException("Invalid audio format");
            }
            // JavaSound line ownership is the JavaFX thread, as are write/flush/close.
            session.audioPlayer().open(format.sampleRate(), format.channels());
        } catch (Exception e) {
            System.err.println("Audio unavailable: " + e.getMessage());
            audioOutputEnabled = false;
            notifyAudioOutputStateChanged();
        }
    }

    private void drainNewAudio(VideoFramePipeline session) {
        VideoFramePipeline.AudioPacket packet;
        while ((packet = session.pollAudio()) != null) {
            if (audioOutputEnabled) {
                pendingAudio.add(new PendingAudio(packet.timestampUs(), packet.samples()));
            }
        }
    }

    private void writeDueAudio(long now, VideoFramePipeline session) {
        if (!audioOutputEnabled) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs > dueTimestampUs) {
                return;
            }
            int written = session.audioPlayer().write(audio.samples, audio.offset, audio.remaining());
            if (written <= 0) {
                return;
            }
            audio.offset += written;
            if (audio.remaining() > 0) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs : currentTimestampUs;
        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;
        preparedFrame = null;
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        if (pipeline != null) {
            VideoFramePipeline oldSession = pipeline;
            pipeline = null;
            oldSession.cancel();
            oldSession.audioPlayer().close();
        }
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clamped = Math.max(0, timestampUs);
        return videoDurationUs == NO_SEEK_REQUEST
                ? clamped : Math.min(clamped, videoDurationUs);
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        if (videoDurationUs == NO_SEEK_REQUEST || logicalTimestampUs < videoDurationUs) {
            return logicalTimestampUs;
        }
        return Math.max(0, videoDurationUs - Math.max(1, videoFrameDurationUs));
    }

    private void flushAudioOutput() {
        if (pipeline != null) {
            pipeline.audioPlayer().flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    private static final class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        int remaining() {
            return samples.length - offset;
        }
    }
}
