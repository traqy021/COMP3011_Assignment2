package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class VideoFramePipelineTest {

    @Test
    void differentThreadsGetIndependentEffectChainsWithPerTypeIndices() throws Exception {
        VideoFramePipeline pipeline = new VideoFramePipeline(
                new File("not-opened.mp4"), 0,
                List.of(FrameEffect.SCRATCH, FrameEffect.FLICKER,
                        FrameEffect.SCRATCH, FrameEffect.FLICKER, FrameEffect.DUST));
        ExecutorService otherThread = Executors.newSingleThreadExecutor();
        try {
            ThreadLocal<?> states = workerStates(pipeline);
            List<FrameProcessor> chainA = chain(states.get());
            List<FrameProcessor> chainB = otherThread.submit(() -> chain(states.get()))
                    .get(5, TimeUnit.SECONDS);

            assertEquals(List.of(FrameScratcher.class, FrameFlickerer.class,
                    FrameScratcher.class, FrameFlickerer.class, FrameDuster.class),
                    chainA.stream().map(Object::getClass).toList());
            assertEquals(chainA.size(), chainB.size());

            int[] indices = {0, 0, 1, 1, 0};
            for (int i = 0; i < indices.length; i++) {
                assertEquals(indices[i], instanceIndex(chainA.get(i)));
                assertEquals(indices[i], instanceIndex(chainB.get(i)));
                assertNotSame(chainA.get(i), chainB.get(i),
                        "A mutable processor must not be shared between workers");
            }
        } finally {
            otherThread.shutdownNow();
            pipeline.cancel();
        }
    }

    @Test
    void completedFramesCanBeTakenOnlyAtTheExpectedSequence() throws Exception {
        VideoFramePipeline pipeline = new VideoFramePipeline(
                new File("not-opened.mp4"), 0, List.of());
        try {
            ConcurrentHashMap<Long, VideoFramePipeline.VideoResult> completed = completed(pipeline);
            VideoFramePipeline.VideoResult second = new VideoFramePipeline.VideoResult(
                    1, 101, 4_000L, null, 0L);
            VideoFramePipeline.VideoResult first = new VideoFramePipeline.VideoResult(
                    0, 100, 0L, null, 0L);

            completed.put(1L, second); // Frame 101 finishes before frame 100
            assertNull(pipeline.takeFrame(0));
            assertEquals(second, completed.get(1L));
            completed.put(0L, first);

            assertSame(first, pipeline.takeFrame(0));
            assertSame(second, pipeline.takeFrame(1));
            assertNull(pipeline.takeFrame(1)); // Taken exactly once
        } finally {
            pipeline.cancel();
        }
    }

    @Test
    void cancellationDiscardsPendingFramesAndAudio() throws Exception {
        VideoFramePipeline pipeline = new VideoFramePipeline(
                new File("not-opened.mp4"), 0, List.of());
        completed(pipeline).put(0L,
                new VideoFramePipeline.VideoResult(0, 1, 0L, null, 0L));
        audio(pipeline).add(new VideoFramePipeline.AudioPacket(0L, new byte[] {1}));

        pipeline.cancel();
        assertNull(pipeline.takeFrame(0));
        assertFalse(pipeline.hasQueuedAudio());
        pipeline.cancel(); // Repeated cancellation should be safe
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<Long, VideoFramePipeline.VideoResult> completed(
            VideoFramePipeline pipeline) throws Exception {
        return (ConcurrentHashMap<Long, VideoFramePipeline.VideoResult>)
                field("completedFrames").get(pipeline);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Queue<VideoFramePipeline.AudioPacket> audio(
            VideoFramePipeline pipeline) throws Exception {
        return (java.util.Queue<VideoFramePipeline.AudioPacket>)
                field("audioPackets").get(pipeline);
    }

    private static ThreadLocal<?> workerStates(VideoFramePipeline pipeline) throws Exception {
        return (ThreadLocal<?>) field("workerStates").get(pipeline);
    }

    @SuppressWarnings("unchecked")
    private static List<FrameProcessor> chain(Object workerState) throws Exception {
        Field chain = workerState.getClass().getDeclaredField("chain");
        chain.setAccessible(true);
        return (List<FrameProcessor>) chain.get(workerState);
    }

    private static int instanceIndex(FrameProcessor processor) throws Exception {
        Field index = processor.getClass().getDeclaredField("instanceIndex");
        index.setAccessible(true);
        return index.getInt(processor);
    }

    private static Field field(String name) throws Exception {
        Field field = VideoFramePipeline.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
