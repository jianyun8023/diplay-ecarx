package android.car;

import android.car.hardware.property.CarPropertyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;

/** Independent test contract for the vehicle property path; no platform implementation. */
public final class Car {
    public static boolean available;
    public static Car last;
    /** Property values the next car's manager starts with. */
    public static final java.util.Map<Integer, Object> seeded = new java.util.HashMap<>();
    public final CarPropertyManager properties = new CarPropertyManager();
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

    public Object getCarManager(String name) { return "property".equals(name) ? properties : null; }
}
