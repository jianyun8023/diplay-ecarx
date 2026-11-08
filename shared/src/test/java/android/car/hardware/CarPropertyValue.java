package android.car.hardware;

public final class CarPropertyValue<T> {
    private final int propertyId;
    private final T value;

    public CarPropertyValue(T value) { this(0, value); }

    public CarPropertyValue(int propertyId, T value) {
        this.propertyId = propertyId;
        this.value = value;
    }

    public int getPropertyId() { return propertyId; }

    public T getValue() { return value; }
}
