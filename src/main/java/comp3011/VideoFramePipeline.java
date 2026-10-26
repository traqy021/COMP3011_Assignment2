package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.scene.image.Image;

public final class VideoFramePipeline {
    public record VideoResult(
            long sequence,
            int frameNumber,
            long timestampUs,
            Image image,
            long preparedAtNs) {
    }

    public record AudioPacket(long timestampUs, byte[] samples) {
    }

    public record AudioFormatInfo(int sampleRate, int channels) {
    }

    private final File videoFile;
    private final long startTimestampUs;
    private final List<FrameEffect> requestedEffects;
    private final AudioPlayer audioPlayer = new AudioPlayer();
    private final Queue<AudioPacket> audioPackets = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, VideoResult> completedFrames = new ConcurrentHashMap<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Semaphore availableFrameSlots;
    private final ExecutorService workers;
    private final ThreadLocal<WorkerState> workerStates;
    private final Thread decoderThread;

    private volatile InfoVideo videoInfo;
    private volatile AudioFormatInfo audioFormat;
    private volatile long durationUs = -1;
    private volatile boolean decodingFinished;
    private volatile Exception failure;
    private volatile long submittedFrameCount;

    public VideoFramePipeline(File videoFile, long startTimestampUs, List<FrameEffect> requestedEffects) {
        this.videoFile = videoFile;
        this.startTimestampUs = startTimestampUs;
        this.requestedEffects = List.copyOf(requestedEffects);

        // Leaving capacity for the JavaFX threads and avoid excessive memory use
        int workerCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        int frameSlots = Math.max(3, workerCount * 2);
        availableFrameSlots = new Semaphore(frameSlots);
        ThreadFactory workerFactory = task -> {
            Thread thread = new Thread(task, "VideoEffectWorker");
            thread.setDaemon(true);
            return thread;
        };
        workers = Executors.newFixedThreadPool(workerCount, workerFactory);
        workerStates = ThreadLocal.withInitial(this::createWorkerState);
        decoderThread = new Thread(this::decodeFrames, "VideoDecoder");
        decoderThread.setDaemon(true);
    }

    public void start() {
        decoderThread.start();
    }

    public InfoVideo videoInfo() { return videoInfo; }
    public AudioFormatInfo audioFormat() { return audioFormat; }
    public AudioPlayer audioPlayer() { return audioPlayer; }
    public long durationUs() { return durationUs; }
    public boolean decodingFinished() { return decodingFinished; }
    public Exception failure() { return failure; }
    public long submittedFrameCount() { return submittedFrameCount; }

    // Result can be completed out of order but not displayed out of order
    public VideoResult takeFrame(long expectedSequence) {
        return completedFrames.remove(expectedSequence);
    }

    // Unblocking bounded decoder read-ahead
    public void releaseDisplayedFrame() {
        availableFrameSlots.release();
    }

    public AudioPacket pollAudio() {
        return audioPackets.poll();
    }

    public void clearAudio() {
        audioPackets.clear();
    }

    public boolean hasQueuedAudio() {
        return !audioPackets.isEmpty();
    }

    // Interrupts background work without blocking the JavaFX application thread //
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            decoderThread.interrupt();
            workers.shutdownNow();
            completedFrames.clear();
            audioPackets.clear();
        }
    }

    private void decodeFrames() {
        FFmpegFrameGrabber grabber = null;
        try {
            grabber = new FFmpegFrameGrabber(videoFile);
            grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.start();

            double frameRate = grabber.getFrameRate();
            int intFrameRate = (int) Math.round(frameRate);
            int frameDurationUs = frameRate > 0
                    ? (int) Math.round(1_000_000.0 / frameRate)
                    : 0;
            int totalVideoFrames = grabber.getLengthInVideoFrames();
            long lengthUs = grabber.getLengthInTime();
            durationUs = lengthUs > 0 ? lengthUs : -1;

            boolean hasAudio = grabber.hasAudio();
            if (hasAudio) {
                audioFormat = new AudioFormatInfo(grabber.getSampleRate(), grabber.getAudioChannels());
            }
            if (startTimestampUs > 0) {
                grabber.setTimestamp(startTimestampUs);
            }

            long sequence = 0;
            while (!cancelled.get()) {
                Frame decoded = hasAudio ? grabber.grab() : grabber.grabImage();
                if (decoded == null) {
                    break;
                }
                long timestampUs = grabber.getTimestamp();

                // Audio payloads are copied on the decoder thread before the next grab
                if (hasAudio && decoded.samples != null) {
                    byte[] bytes = audioPlayer.copySamples(decoded);
                    if (bytes.length > 0 && !cancelled.get()) {
                        audioPackets.add(new AudioPacket(timestampUs, bytes));
                    }
                }
                if (decoded.image == null) {
                    continue;
                }

                if (videoInfo == null) {
                    videoInfo = new InfoVideo(
                            mediaName(videoFile), totalVideoFrames,
                            decoded.imageWidth, decoded.imageHeight,
                            decoded.imageDepth, decoded.imageChannels, decoded.imageStride,
                            frameRate, intFrameRate, frameDurationUs, grabber.getPixelFormat());
                }

                // The semaphore prevents unbounded images piling up in memory
                availableFrameSlots.acquire();
                if (cancelled.get()) {
                    availableFrameSlots.release();
                    break;
                }
                Frame privateCopy;
                try {
                    privateCopy = decoded.clone(); // Deep copy native image buffers
                } catch (RuntimeException e) {
                    availableFrameSlots.release();
                    throw e;
                }
                final long frameSequence = sequence++;
                final int frameNumber = grabber.getFrameNumber();
                final long frameTime = timestampUs;
                final InfoVideo metadata = videoInfo;
                try {
                    workers.execute(() -> processFrame(
                            privateCopy, metadata, frameSequence, frameNumber, frameTime));
                } catch (RejectedExecutionException e) {
                    availableFrameSlots.release();
                    if (!cancelled.get()) {
                        throw e;
                    }
                    break;
                }
                submittedFrameCount = sequence;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!cancelled.get()) {
                failure = e;
            }
        } finally {
            decodingFinished = true;
            if (grabber != null) {
                try { grabber.stop(); } catch (Exception ignored) { }
                try { grabber.close(); } catch (Exception ignored) { }
            }
        }
    }

    private WorkerState createWorkerState() {
        List<FrameProcessor> chain = new ArrayList<>();
        for (int position = 0; position < requestedEffects.size(); position++) {
            chain.add(requestedEffects.get(position).createProcessor(position));
        }
        return new WorkerState(chain);
    }

    private void processFrame(Frame privateCopy, InfoVideo metadata,
            long sequence, int frameNumber, long timestampUs) {
        try {
            if (cancelled.get()) {
                return;
            }
            WorkerState worker = workerStates.get();
            if (!worker.initialised) {
                for (FrameProcessor processor : worker.chain) {
                    processor.initialise(metadata);
                }
                worker.initialised = true;
            }
            InfoFrame info = new InfoFrame(frameNumber, timestampUs);
            // Effects on the same frame run in command-line order.
            for (FrameProcessor processor : worker.chain) {
                processor.process(privateCopy, info);
            }
            // A converter is never shared between threads or between frames.
            JavaFXFrameConverter converter = new JavaFXFrameConverter();
            Image image;
            try {
                image = converter.convert(privateCopy);
            } finally {
                converter.close();
            }
            if (!cancelled.get()) {
                completedFrames.put(sequence, new VideoResult(
                        sequence, frameNumber, timestampUs, image, System.nanoTime()));
            }
        } catch (Exception e) {
            if (!cancelled.get()) {
                failure = e;
            }
        }
    }

    private static String mediaName(File file) {
        String fileName = file.getName();
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static final class WorkerState {
        private final List<FrameProcessor> chain;
        private boolean initialised;

        WorkerState(List<FrameProcessor> chain) {
            this.chain = chain;
        }
    }
}