package android.car;

import android.car.hardware.property.CarPropertyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;

/** Independent test contract for Car service adapters. */
public final class Car {
    public static boolean available;
    public static Car last;
    /** Property values the next car's manager starts with. */
    public static final java.util.Map<Integer, Object> seeded = new java.util.HashMap<>();
    public CarPropertyManager properties = new CarPropertyManager();
    public final Audio audio = new Audio();
    public boolean connected;
    public boolean disconnected;
    private final ServiceConnection connection;

    private Car(ServiceConnection connection) {
        this.connection = connection;
        properties.values.putAll(seeded);
    }

    public static void reset() { available = false; last = null; seeded.clear(); }

    public static Car createCar(Context context, ServiceConnection connection, Handler handler) {
        if (!available) return null;
        last = new Car(connection);
        return last;
    }

    public void connect() {
        connected = true;
        connection.onServiceConnected(new ComponentName("android.car", "CarService"), null);
    }

    public boolean isConnected() { return connected; }

    public void disconnect() { connected = false; disconnected = true; }

    /** A service loss invalidates the old manager; connect() delivers the recovery callback. */
    public void disconnectService() {
        connected = false;
        properties = new CarPropertyManager();
        connection.onServiceDisconnected(new ComponentName("android.car", "CarService"));
    }

    public Object getCarManager(String name) {
        if ("property".equals(name)) return properties;
        if ("audio".equals(name)) return audio;
        return null;
    }

    public static final class Audio {
        public final java.util.Map<Integer, Integer> volumes = new java.util.HashMap<>();

        public int getGroupVolume(int groupId) { return volumes.get(groupId); }

        public void setGroupVolume(int groupId, int value, int flags) { volumes.put(groupId, value); }
    }
}
