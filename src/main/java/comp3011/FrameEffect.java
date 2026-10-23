package comp3011;

import java.util.function.IntFunction;

public enum FrameEffect {

    NUMBER('n', "number-frames",
            index -> new FrameNumberer()),

    SCRATCH('s', "scratch-frames",
            index -> new FrameScratcher(index)),

    FLICKER('f', "flicker-frames",
            index -> new FrameFlickerer(index)),

    BLACK_WHITE('w', "black-and-white",
            index -> new FrameBlackAndWhiter()),

    YELLOW('y', "yellow-frames",
            index -> new FrameYellower()),

    VIGNETTE('v', "vignette-frames",
            index -> new FrameVignetter()),

    DUST('d', "dust-frames",
            index -> new FrameDuster(index)),

    JITTER('j', "jitter-frames",
            index -> new FrameJitterer(index)),

    MOTTLE('m', "mottle-frames",
            index -> new FrameMottler(index)),

    BLEED('b', "bleed-frames",
            index -> new FrameBleeder()),

    PEPPER('p', "pepper-frames",
            index -> new FramePepperer(index));

    private final char shortName;
    private final String longName;
    private final IntFunction<FrameProcessor> factory;

    FrameEffect(char shortName, String longName, IntFunction<FrameProcessor> factory) {
        this.shortName = shortName;
        this.longName = longName;
        this.factory = factory;
    }

    public FrameProcessor createProcessor(int position) {
        return factory.apply(position);
    }

    public static FrameEffect fromShortName(char option) {
        for (FrameEffect effect : values()) {
            if (effect.shortName == option) {
                return effect;
            }
        }
        return null;
    }

    public static FrameEffect fromLongName(String name) {
        for (FrameEffect effect : values()) {
            if (effect.longName.equals(name)) {
                return effect;
            }
        }
        return null;
    }
}