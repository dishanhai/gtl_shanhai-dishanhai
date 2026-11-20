package com.dishanhai.gt_shanhai.testutil;

import java.util.regex.Pattern;

public final class WireProtocolAssertions {
    private static final Pattern VERSION = Pattern.compile("private static final String PROTOCOL_VERSION = \"([0-9]+)\";");

    private WireProtocolAssertions() {}

    public static boolean atLeast(String source, int minimum) {
        var version = VERSION.matcher(source);
        return version.find() && Integer.parseInt(version.group(1)) >= minimum;
    }
}
