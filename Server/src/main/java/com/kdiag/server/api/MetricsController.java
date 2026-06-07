package com.kdiag.server.api;

import com.kdiag.server.metrics.MetricsCollector;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes the in-process {@link MetricsCollector} over HTTP for the thesis demo.
 *
 * <ul>
 *   <li>{@code GET  /v1/metrics}       — point-in-time snapshot of all counters + derived averages</li>
 *   <li>{@code POST /v1/metrics/reset} — zero all counters between demo runs</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/metrics")
public class MetricsController {

    private final MetricsCollector metrics;

    public MetricsController(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    /** Returns all counters and derived averages as a flat JSON object. */
    @GetMapping
    public Map<String, Object> snapshot() {
        return metrics.snapshot();
    }

    /** Zeroes every counter. Returns {@code {"status":"reset"}} on success. */
    @PostMapping("/reset")
    public Map<String, String> reset() {
        metrics.reset();
        return Map.of("status", "reset");
    }
}
