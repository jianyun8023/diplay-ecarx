package android.car.hardware.property;

import android.car.hardware.CarPropertyValue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CarPropertyManager {
    public interface CarPropertyEventListener {
        void onChangeEvent(CarPropertyValue value);
        void onErrorEvent(int propertyId, int zone);
    }

    public int current = 1;
    public int readProperty = -1;
    public CarPropertyEventListener listener;
    public int listenedProperty = -1;
    public final List<Integer> listenedProperties = new ArrayList<>();
    public final Map<Integer, Object> values = new HashMap<>();

    public int getIntProperty(int propertyId, int area) {
        readProperty = propertyId;
        return current;
    }

    @SuppressWarnings("unchecked")
    public <E> CarPropertyValue<E> getProperty(Class<E> type, int propertyId, int area) {
        Object value = values.get(propertyId);
        if (value == null) throw new IllegalStateException("unavailable " + propertyId);
        if (value.getClass() != type) throw new IllegalArgumentException("type " + value.getClass());
        return new CarPropertyValue<>(propertyId, (E) value);
    }

    public boolean registerListener(CarPropertyEventListener listener, int propertyId, float rate) {
        this.listener = listener;
        listenedProperty = propertyId;
        listenedProperties.add(propertyId);
        return true;
    }

    public void unregisterListener(CarPropertyEventListener listener) {
        if (this.listener == listener) this.listener = null;
    }
}
