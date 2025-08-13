package com.example.metrics;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;
import oshi.software.os.OperatingSystem.ProcessSorting;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Spring Boot controller that returns host metrics (CPU, memory, network Mbps)
 * and a small list of top processes. Uses OSHI (oshi-core) for reliable cross-platform metrics.
 *
 * Make sure you have the OSHI dependency in your pom.xml:
 * <dependency>
 *   <groupId>com.github.oshi</groupId>
 *   <artifactId>oshi-core</artifactId>
 *   <version>6.4.3</version>
 * </dependency>
 */
@CrossOrigin(origins = "*")
@RestController
public class MetricsController {

  private final SystemInfo si;
  private final CentralProcessor processor;
  private long[] prevTicks;

  private final GlobalMemory memory;
  private final OperatingSystem os;

  // network delta state (bytes)
  private long prevNetBytes = -1;
  private long prevNetTimeMs = -1;

  public MetricsController() {
    this.si = new SystemInfo();
    this.processor = si.getHardware().getProcessor();
    // initialize prevTicks so getSystemCpuLoadBetweenTicks has a baseline
    this.prevTicks = processor.getSystemCpuLoadTicks();
    this.memory = si.getHardware().getMemory();
    this.os = si.getOperatingSystem();
  }

  private static double round(double value, int decimals) {
    double factor = Math.pow(10, decimals);
    return Math.round(value * factor) / factor;
  }

  @GetMapping("/metrics")
  public Map<String, Object> getMetrics() {
    Map<String, Object> resp = new HashMap<>();

    // ---- CPU ----
    double cpuLoad;
    try {
      cpuLoad = processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100.0;
    } catch (Throwable t) {
      cpuLoad = 0.0;
    }
    // refresh baseline ticks for the next call
    try {
      prevTicks = processor.getSystemCpuLoadTicks();
    } catch (Throwable ignored) { }

    if (Double.isNaN(cpuLoad) || cpuLoad < 0) cpuLoad = 0.0;

    // ---- Memory ----
    long totalMem = memory.getTotal();
    long availMem = memory.getAvailable();
    double memPct = 0.0;
    if (totalMem > 0) {
      memPct = (1.0 - ((double) availMem / (double) totalMem)) * 100.0;
      if (Double.isNaN(memPct) || memPct < 0) memPct = 0.0;
    }

    // ---- Network (Mbps) ----
    long totalBytes = 0L;
    try {
      for (NetworkIF nif : si.getHardware().getNetworkIFs()) {
        try {
          nif.updateAttributes();
          long recv = nif.getBytesRecv();
          long sent = nif.getBytesSent();
          if (recv > 0) totalBytes += recv;
          if (sent > 0) totalBytes += sent;
        } catch (Throwable ignored) { }
      }
    } catch (Throwable ignored) { }

    long now = System.currentTimeMillis();
    double mbps = 0.0;
    if (prevNetBytes >= 0 && prevNetTimeMs > 0 && now > prevNetTimeMs) {
      long deltaBytes = totalBytes - prevNetBytes;
      double deltaSec = (now - prevNetTimeMs) / 1000.0;
      if (deltaSec > 0) {
        // bytes -> bits, then to megabits per second
        mbps = (deltaBytes * 8.0) / (deltaSec * 1_000_000.0);
      }
    }
    prevNetBytes = totalBytes;
    prevNetTimeMs = now;

    // ---- Top processes ----
    List<Map<String, Object>> procList = Collections.emptyList();
    try {
      // Use OSHI's ProcessSorting comparator and request a limited set.
      // Newer OSHI returns a List<OSProcess>.
      List<OSProcess> processes = os.getProcesses(null, ProcessSorting.CPU_DESC, 12);
      procList = processes.stream()
        .map(p -> {
          Map<String, Object> m = new HashMap<>();
          m.put("pid", p.getProcessID());
          m.put("name", p.getName());
          // cumulative CPU ratio (0..1) multiplied to percentage if available
          double procCpu = 0.0;
          try {
            procCpu = p.getProcessCpuLoadCumulative() * 100.0;
            if (Double.isNaN(procCpu) || procCpu < 0) procCpu = 0.0;
          } catch (Throwable ignored) { procCpu = 0.0; }
          m.put("cpu", round(procCpu, 2));

          long rss = 0L;
          try { rss = p.getResidentSetSize(); } catch (Throwable ignored) { rss = 0L; }
          double memPctProc = 0.0;
          if (totalMem > 0) {
            memPctProc = ((double) rss / (double) totalMem) * 100.0;
            if (Double.isNaN(memPctProc) || memPctProc < 0) memPctProc = 0.0;
          }
          m.put("memory", round(memPctProc, 2));

          String cmd = "";
          try { cmd = p.getCommandLine(); } catch (Throwable ignored) { cmd = ""; }
          m.put("cmd", cmd);

          return m;
        })
        .collect(Collectors.toList());
    } catch (Throwable t) {
      // fallback: empty list on error
      procList = Collections.emptyList();
    }

    // ---- assemble response ----
    resp.put("cpu", round(cpuLoad, 1));
    resp.put("memory", round(memPct, 1));
    resp.put("network", round(mbps, 3)); // Mbps with 3 decimals
    resp.put("processes", procList);

    return resp;
  }
}
