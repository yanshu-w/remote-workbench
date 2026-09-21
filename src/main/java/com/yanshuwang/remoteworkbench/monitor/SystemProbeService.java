package com.yanshuwang.remoteworkbench.monitor;

import com.yanshuwang.remoteworkbench.connection.ConnectionProfile;
import com.yanshuwang.remoteworkbench.ssh.SshConnectionService;
import javafx.application.Platform;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Agentless, zero-overhead Linux system health probe.
 * Periodically polls /proc/stat, /proc/meminfo, and /proc/net/dev via the active SSH session.
 */
public final class SystemProbeService implements AutoCloseable {
    private static final int PROBE_INTERVAL_SECONDS = 3;
    private static final String PROBE_COMMAND = "cat /proc/stat /proc/meminfo /proc/net/dev 2>/dev/null; echo '---CWD---'; for p in $(pgrep -u $(whoami) -x 'bash|zsh|sh' 2>/dev/null); do [ -d /proc/$p/cwd ] && readlink /proc/$p/cwd; done | tail -n 1";

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "system-probe-worker");
        thread.setDaemon(true);
        return thread;
    });

    private ScheduledFuture<?> currentTask;
    private ConnectionProfile activeProfile;
    private SshConnectionService sshService;
    private Consumer<ServerMetrics> listener;

    private long prevIdleTime = -1;
    private long prevTotalTime = -1;
    private long prevRxBytes = -1;
    private long prevTxBytes = -1;
    private long prevTimestampMs = -1;

    public synchronized void start(ConnectionProfile profile, SshConnectionService service, Consumer<ServerMetrics> metricsListener) {
        stop();

        this.activeProfile = Objects.requireNonNull(profile, "profile");
        this.sshService = Objects.requireNonNull(service, "service");
        this.listener = Objects.requireNonNull(metricsListener, "metricsListener");

        resetState();

        currentTask = scheduler.scheduleWithFixedDelay(this::probeOnce, 500, PROBE_INTERVAL_SECONDS * 1000L, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (currentTask != null) {
            currentTask.cancel(true);
            currentTask = null;
        }
        activeProfile = null;
        sshService = null;
        listener = null;
        resetState();
    }

    private void resetState() {
        prevIdleTime = -1;
        prevTotalTime = -1;
        prevRxBytes = -1;
        prevTxBytes = -1;
        prevTimestampMs = -1;
    }

    private void probeOnce() {
        ConnectionProfile profile = this.activeProfile;
        SshConnectionService service = this.sshService;
        Consumer<ServerMetrics> callback = this.listener;

        if (profile == null || service == null || callback == null || !service.isConnected(profile)) {
            return;
        }

        long startTime = System.currentTimeMillis();
        try {
            service.execute(profile, PROBE_COMMAND).whenComplete((output, error) -> {
                long latency = Math.max(1, System.currentTimeMillis() - startTime);
                if (error != null || output == null || output.isBlank() || !output.contains("cpu")) {
                    notifyMetrics(callback, ServerMetrics.unavailable());
                    return;
                }

                try {
                    ServerMetrics metrics = parseMetrics(output, latency);
                    notifyMetrics(callback, metrics);
                } catch (Exception parseException) {
                    notifyMetrics(callback, ServerMetrics.unavailable());
                }
            });
        } catch (Exception ignored) {
            notifyMetrics(callback, ServerMetrics.unavailable());
        }
    }

    private void notifyMetrics(Consumer<ServerMetrics> callback, ServerMetrics metrics) {
        if (callback != null) {
            Platform.runLater(() -> callback.accept(metrics));
        }
    }

    private ServerMetrics parseMetrics(String raw, long latencyMs) {
        long now = System.currentTimeMillis();

        String cwd = "";
        int cwdIndex = raw.indexOf("---CWD---");
        if (cwdIndex >= 0) {
            String cwdBlock = raw.substring(cwdIndex + "---CWD---".length()).trim();
            if (!cwdBlock.isEmpty()) {
                String[] cwdLines = cwdBlock.split("\\R");
                for (String line : cwdLines) {
                    line = line.trim();
                    if (line.startsWith("/")) {
                        cwd = line;
                    }
                }
            }
            raw = raw.substring(0, cwdIndex);
        }

        double cpuPercent = 0.0;
        long memTotalBytes = 0;
        long memUsedBytes = 0;
        double memPercent = 0.0;
        double rxBytesPerSec = 0.0;
        double txBytesPerSec = 0.0;

        long currentRx = 0;
        long currentTx = 0;
        boolean hasNet = false;

        long memTotalKb = 0;
        long memFreeKb = 0;
        long memAvailKb = 0;
        long buffersKb = 0;
        long cachedKb = 0;
        boolean hasMemAvail = false;

        String[] lines = raw.split("\\R");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            // 1. /proc/stat CPU line
            if (line.startsWith("cpu ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 5) {
                    try {
                        long user = Long.parseLong(parts[1]);
                        long nice = Long.parseLong(parts[2]);
                        long system = Long.parseLong(parts[3]);
                        long idle = Long.parseLong(parts[4]);
                        long iowait = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
                        long irq = parts.length > 6 ? Long.parseLong(parts[6]) : 0;
                        long softirq = parts.length > 7 ? Long.parseLong(parts[7]) : 0;
                        long steal = parts.length > 8 ? Long.parseLong(parts[8]) : 0;

                        long totalTime = user + nice + system + idle + iowait + irq + softirq + steal;
                        long idleTime = idle + iowait;

                        if (prevTotalTime > 0 && prevIdleTime > 0) {
                            long deltaTotal = totalTime - prevTotalTime;
                            long deltaIdle = idleTime - prevIdleTime;
                            if (deltaTotal > 0) {
                                cpuPercent = Math.max(0.0, Math.min(100.0, (1.0 - (double) deltaIdle / (double) deltaTotal) * 100.0));
                            }
                        }
                        prevTotalTime = totalTime;
                        prevIdleTime = idleTime;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            // 2. /proc/meminfo
            if (line.startsWith("MemTotal:")) {
                memTotalKb = parseMemoryKb(line);
            } else if (line.startsWith("MemFree:")) {
                memFreeKb = parseMemoryKb(line);
            } else if (line.startsWith("MemAvailable:")) {
                memAvailKb = parseMemoryKb(line);
                hasMemAvail = true;
            } else if (line.startsWith("Buffers:")) {
                buffersKb = parseMemoryKb(line);
            } else if (line.startsWith("Cached:")) {
                cachedKb = parseMemoryKb(line);
            }

            // 3. /proc/net/dev
            if (line.contains(":") && !line.startsWith("Inter-") && !line.startsWith("face")) {
                int colonIdx = line.indexOf(':');
                String iface = line.substring(0, colonIdx).trim();
                if (!iface.equals("lo")) {
                    String rest = line.substring(colonIdx + 1).trim();
                    String[] tokens = rest.split("\\s+");
                    if (tokens.length >= 9) {
                        try {
                            long rx = Long.parseLong(tokens[0]);
                            long tx = Long.parseLong(tokens[8]);
                            currentRx += rx;
                            currentTx += tx;
                            hasNet = true;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }

        // Compute Memory
        if (memTotalKb > 0) {
            memTotalBytes = memTotalKb * 1024L;
            long usedKb = hasMemAvail ? Math.max(0, memTotalKb - memAvailKb)
                    : Math.max(0, memTotalKb - (memFreeKb + buffersKb + cachedKb));
            memUsedBytes = usedKb * 1024L;
            memPercent = Math.max(0.0, Math.min(100.0, ((double) memUsedBytes / (double) memTotalBytes) * 100.0));
        }

        // Compute Network Rate
        if (hasNet) {
            if (prevTimestampMs > 0 && prevRxBytes >= 0 && prevTxBytes >= 0) {
                double dt = (now - prevTimestampMs) / 1000.0;
                if (dt > 0.1) {
                    rxBytesPerSec = Math.max(0.0, (currentRx - prevRxBytes) / dt);
                    txBytesPerSec = Math.max(0.0, (currentTx - prevTxBytes) / dt);
                }
            }
            prevRxBytes = currentRx;
            prevTxBytes = currentTx;
            prevTimestampMs = now;
        }

        return new ServerMetrics(true, cpuPercent, memTotalBytes, memUsedBytes, memPercent, rxBytesPerSec, txBytesPerSec, latencyMs, cwd);
    }

    private static long parseMemoryKb(String line) {
        try {
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                return Long.parseLong(parts[1]);
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    @Override
    public void close() {
        stop();
        scheduler.shutdownNow();
    }

    public record ServerMetrics(
            boolean available,
            double cpuPercent,
            long memTotalBytes,
            long memUsedBytes,
            double memPercent,
            double rxBytesPerSec,
            double txBytesPerSec,
            long latencyMs,
            String currentDirectory
    ) {
        public static ServerMetrics unavailable() {
            return new ServerMetrics(false, 0, 0, 0, 0, 0, 0, -1, "");
        }
    }
}
