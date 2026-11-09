package vendor.cevt.hardware.automotive.vehicle.geely.V2_0;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal independent fixture for PublicAttrValue's availability=1 and double_data=5 fields. */
public final class GeelyProto {
    public static final class Availability {
        private final int number;
        private Availability(int number) { this.number = number; }
        public int getNumber() { return number; }
    }

    public static final class PublicAttrValue {
        private final Availability availability;
        private final Double value;
        private PublicAttrValue(int availability, Double value) {
            this.availability = new Availability(availability);
            this.value = value;
        }
        public static PublicAttrValue parseFrom(byte[] bytes) {
            if ((bytes.length != 2 && bytes.length != 11) || bytes[0] != 8)
                throw new IllegalArgumentException("invalid fixture message");
            if (bytes.length == 2) return new PublicAttrValue(bytes[1], null);
            if (bytes[2] != 41) throw new IllegalArgumentException("expected double_data");
            return new PublicAttrValue(bytes[1], ByteBuffer.wrap(bytes, 3, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble());
        }
        public Availability getAvailability() { return availability; }
        public boolean hasDoubleData() { return value != null; }
        public double getDoubleData() { return value == null ? 0 : value; }
    }
}
