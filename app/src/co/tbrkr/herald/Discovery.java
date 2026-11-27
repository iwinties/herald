package co.tbrkr.herald;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import java.util.Map;
import java.util.Objects;

/** All registration state lives on one looper, including callbacks from NsdManager. */
final class Discovery {
    private final NsdManager nsd;
    private final Handler handler;
    private final String id;
    private String wantedName, registeredName;
    private int wantedPort;
    private Registration current;
    private boolean busy, closed;

    Discovery(NsdManager nsd, Handler handler, String id) {
        this.nsd = nsd;
        this.handler = handler;
        this.id = id;
    }

    void advertise(String name, int port) {
        handler.post(() -> {
            if (closed) return;
            wantedName = name;
            wantedPort = port;
            reconcile();
        });
    }

    void close() {
        handler.post(() -> { closed = true; wantedPort = 0; reconcile(); });
    }

    String assignedName() { return registeredName; } // Read on the activity looper.

    private void reconcile() {
        if (busy) return;
        if (current != null && current.port == wantedPort && Objects.equals(current.name, wantedName)) return;
        if (current == null && wantedPort == 0) return;
        busy = true;
        try {
            if (current != null) nsd.unregisterService(current);
            else {
                NsdServiceInfo service = new NsdServiceInfo();
                service.setServiceName(wantedName);
                service.setServiceType("_herald._tcp");
                service.setPort(wantedPort);
                for (Map.Entry<String,Object> entry : MqttTopics.txt(id, HeraldService.VERSION).entrySet())
                    service.setAttribute(entry.getKey(), (String)entry.getValue());
                current = new Registration(wantedName, wantedPort);
                nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, current);
            }
        } catch (RuntimeException e) {
            // An unregistered listener can also fail synchronously.
            current = null;
            registeredName = null;
            busy = false;
            handler.postDelayed(this::reconcile, 5000);
        }
    }

    private final class Registration implements NsdManager.RegistrationListener {
        final String name;
        final int port;
        Registration(String name, int port) { this.name = name; this.port = port; }
        public void onServiceRegistered(NsdServiceInfo info) {
            handler.post(() -> {
                if (current != this) return;
                registeredName = info.getServiceName();
                busy = false;
                reconcile();
            });
        }
        public void onServiceUnregistered(NsdServiceInfo info) { complete(true, 0); }
        public void onRegistrationFailed(NsdServiceInfo info, int error) { complete(true, 5000); }
        public void onUnregistrationFailed(NsdServiceInfo info, int error) { complete(false, 5000); }

        private void complete(boolean removed, int delay) {
            handler.post(() -> {
                if (current != this) return;
                if (removed) { current = null; registeredName = null; }
                busy = false;
                handler.postDelayed(Discovery.this::reconcile, delay);
            });
        }
    }
}
