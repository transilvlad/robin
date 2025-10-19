package com.mimecast.robin.endpoints.utils;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.io.IOException;
import java.lang.management.ManagementFactory;

/**
 * HotSpotDiagnostic interface.
 */
public class HotSpotDiagnostic {

    /**
     * Gets the HotSpotDiagnostic.
     *
     * @return HotSpotDiagnosticMXBean instance.
     * @throws IOException If the bean cannot be obtained.
     */
    public static HotSpotDiagnosticMXBean getDiagnostic() throws IOException {
        return ManagementFactory.newPlatformMXBeanProxy(
                ManagementFactory.getPlatformMBeanServer(),
                "com.sun.management:type=HotSpotDiagnostic",
                HotSpotDiagnosticMXBean.class
        );
    }
}
