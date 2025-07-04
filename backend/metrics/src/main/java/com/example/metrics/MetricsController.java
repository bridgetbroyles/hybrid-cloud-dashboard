package com.example.metrics;

import org.springframework.web.bind.annotation.CrossOrigin;

import com.sun.management.OperatingSystemMXBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.Map;


@CrossOrigin(origins = "*")
@RestController
public class MetricsController {

  private final OperatingSystemMXBean osBean =
    ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

  @SuppressWarnings("deprecation")
  @GetMapping("/metrics")
  public Map<String, Double> getMetrics() {
    double cpu = osBean.getSystemCpuLoad() * 100;     
    long totalMem = osBean.getTotalPhysicalMemorySize();
    long freeMem  = osBean.getFreePhysicalMemorySize();
    double memPct = (1 - (double) freeMem / totalMem) * 100;

    return Map.of(
      "cpu", Math.round(cpu * 10) / 10.0,
      "memory", Math.round(memPct * 10) / 10.0
    );
  }
}
