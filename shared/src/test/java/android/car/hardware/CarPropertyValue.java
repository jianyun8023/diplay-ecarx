package android.car.hardware;

public final class CarPropertyValue<T> {
    private final int propertyId;
    private final T value;
    private final int status;

    public CarPropertyValue(T value) { this(0, value); }

    public CarPropertyValue(int propertyId, T value) {
        this(propertyId, value, 0);
    }

    public CarPropertyValue(int propertyId, T value, int status) {
        this.propertyId = propertyId;
        this.value = value;
        this.status = status;
    }

    public int getStatus() { return status; }

    public int getPropertyId() { return propertyId; }

    public T getValue() { return value; }
}
