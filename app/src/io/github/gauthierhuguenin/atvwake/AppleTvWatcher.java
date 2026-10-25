package io.github.gauthierhuguenin.atvwake;

import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.gauthierhuguenin.atvwake.companion.CompanionClient;

/** Stays connected to the Apple TV and turns the TV on when the Apple TV leaves sleep. */
final class AppleTvWatcher implements Runnable {
    private static final int UNKNOWN = 0;

    private final Context context;
    private final PowerManager power;
    private final PowerManager.WakeLock cpu;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = true;
    private volatile CompanionClient client;
    private Thread thread;
    private String host;
    private int port;
    private int lastState = UNKNOWN;

    AppleTvWatcher(Context context) {
        this.context = context.getApplicationContext();
        power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AppleTVWake:watch");
        cpu.setReferenceCounted(false);
    }

    void start() {
        thread = new Thread(this, "AppleTvWatcher");
        thread.start();
    }

    void stop() {
        running = false;
        CompanionClient c = client;
        if (c != null) c.close();
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        long backoff = 2_000;
        while (running) {
            // Keep the CPU awake while (re)connecting, even if the TV is in standby.
            cpu.acquire(60_000);
            CompanionClient c = new CompanionClient();
            client = c;
            try {
                if (host == null) resolve();
                c.connect(host, port, 8_000);
                lastState = UNKNOWN;
                c.start(new CompanionClient.Credentials(Prefs.string(context, Prefs.CREDENTIALS)), Prefs.RP_ID,
                        this::onState);
                Log.write(context, "connected to the Apple TV (" + host + ":" + port + ")");
                backoff = 2_000;
                cpu.release();
                c.loop();
            } catch (Throwable e) {
                // Never crash the service: log and retry later.
                if (running) Log.write(context, "Apple TV connection lost: " + e);
                host = null; // the Companion port changes when the Apple TV restarts
            } finally {
                c.close();
                cpu.release();
            }
            if (!running) break;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 60_000);
        }
    }

    private void onState(int state) {
        cpu.acquire(10_000);
        boolean screenOn = power.isInteractive();
        Log.write(context, "Apple TV state " + lastState + " -> " + state + ", TV " + (screenOn ? "on" : "in standby"));
        // Only a real wake-up counts, never the state read right after connecting.
        if (lastState == CompanionClient.ASLEEP && state != CompanionClient.ASLEEP && !screenOn) {
            Log.write(context, "turning the TV on");
            main.post(() -> context.startActivity(new Intent(context, WakeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }
        lastState = state;
    }

    /** Finds the Apple TV over Bonjour (its Companion port changes on every restart). */
    private void resolve() throws IOException {
        String name = Prefs.string(context, Prefs.APPLE_TV_NAME);
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        CountDownLatch done = new CountDownLatch(1);
        NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo info) {
                if (!info.getServiceName().equals(name)) return;
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override
                    public void onServiceResolved(NsdServiceInfo r) {
                        host = r.getHost().getHostAddress();
                        port = r.getPort();
                        done.countDown();
                    }

                    @Override
                    public void onResolveFailed(NsdServiceInfo r, int error) {
                        Log.write(context, "Bonjour resolve failed: " + error);
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {}

            @Override
            public void onDiscoveryStarted(String type) {}

            @Override
            public void onDiscoveryStopped(String type) {}

            @Override
            public void onStartDiscoveryFailed(String type, int error) {
                done.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String type, int error) {}
        };
        nsd.discoverServices("_companion-link._tcp", NsdManager.PROTOCOL_DNS_SD, discovery);
        try {
            done.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (RuntimeException ignored) {
            }
        }
        if (host == null) throw new IOException("Apple TV \"" + name + "\" not found on the network");
    }
}
