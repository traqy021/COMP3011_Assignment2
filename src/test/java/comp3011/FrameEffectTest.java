package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;

class FrameEffectTest {

    @Test
    void allElevenShortAndLongNamesResolveToTheSameEffect() {
        char[] shortNames = {'n', 's', 'f', 'w', 'y', 'v', 'd', 'j', 'm', 'b', 'p'};
        String[] longNames = {
                "number-frames", "scratch-frames", "flicker-frames",
                "black-and-white", "yellow-frames", "vignette-frames",
                "dust-frames", "jitter-frames", "mottle-frames",
                "bleed-frames", "pepper-frames"
        };

        FrameEffect[] effects = FrameEffect.values();
        assertEquals(11, effects.length);

        for (int i = 0; i < effects.length; i++) {
            assertSame(effects[i], FrameEffect.fromShortName(shortNames[i]));
            assertSame(effects[i], FrameEffect.fromLongName(longNames[i]));
        }
    }

    @Test
    void unknownOrIncorrectlyCasedNamesAreRejected() {
        assertNull(FrameEffect.fromShortName('z'));
        assertNull(FrameEffect.fromShortName('S'));
        assertNull(FrameEffect.fromLongName("Flicker-frames"));
        assertNull(FrameEffect.fromLongName("scratch"));
    }

    @Test
    void eachEffectCreatesTheCorrectProcessorClass() {
        List<Class<? extends FrameProcessor>> expected = List.of(
                FrameNumberer.class,
                FrameScratcher.class,
                FrameFlickerer.class,
                FrameBlackAndWhiter.class,
                FrameYellower.class,
                FrameVignetter.class,
                FrameDuster.class,
                FrameJitterer.class,
                FrameMottler.class,
                FrameBleeder.class,
                FramePepperer.class
        );

        FrameEffect[] effects = FrameEffect.values();
        for (int i = 0; i < effects.length; i++) {
            assertInstanceOf(expected.get(i), effects[i].createProcessor(0));
        }
    }

    @Test
    void factoriesCreateIndependentProcessorObjects() {
        FrameProcessor a = FrameEffect.SCRATCH.createProcessor(0);
        FrameProcessor b = FrameEffect.SCRATCH.createProcessor(0);
        assertNotSame(a, b);
    }

    @Test
    void seededProcessorConstructorsReceiveTheInstanceIndex() throws Exception {
        for (FrameEffect effect : List.of(
                FrameEffect.SCRATCH, FrameEffect.FLICKER, FrameEffect.DUST,
                FrameEffect.JITTER, FrameEffect.MOTTLE, FrameEffect.PEPPER)) {
            FrameProcessor first = effect.createProcessor(0);
            FrameProcessor third = effect.createProcessor(2);

            assertEquals(0, instanceIndex(first), effect.name());
            assertEquals(2, instanceIndex(third), effect.name());
            assertNotSame(first, third);
        }
    }

    /** Only the regression for explicit seed indices needs to inspect this private field. */
    private static int instanceIndex(FrameProcessor processor) throws Exception {
        Field field = processor.getClass().getDeclaredField("instanceIndex");
        field.setAccessible(true);
        return field.getInt(processor);
    }
}
