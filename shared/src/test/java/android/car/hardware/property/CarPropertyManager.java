package android.car.hardware.property;

import android.car.hardware.CarPropertyValue;

public final class CarPropertyManager {
    public interface CarPropertyEventListener {
        void onChangeEvent(CarPropertyValue value);
        void onErrorEvent(int propertyId, int zone);
    }

    public int current = 1;
    public int readProperty = -1;
    public CarPropertyEventListener listener;
    public int listenedProperty = -1;

    public int getIntProperty(int propertyId, int area) {
        readProperty = propertyId;
        return current;
    }

    public boolean registerListener(CarPropertyEventListener listener, int propertyId, float rate) {
        this.listener = listener;
        listenedProperty = propertyId;
        return true;
    }

    public void unregisterListener(CarPropertyEventListener listener) {
        if (this.listener == listener) this.listener = null;
    }
}
