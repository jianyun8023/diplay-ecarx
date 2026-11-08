package android.car.hardware;

public final class CarPropertyValue<T> {
    private final T value;

    public CarPropertyValue(T value) { this.value = value; }

    public T getValue() { return value; }
}
