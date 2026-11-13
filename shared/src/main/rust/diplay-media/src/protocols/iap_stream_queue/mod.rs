//! AirPlay iAP stream pending/split/termination owner. Payload slots are JVM GC arrays,
//! addressed only by this module; no heap handle survives close while pending data drains.
//! LIVI 6ac88bab7351a39b0cffac20c5dcf92ca848319b iap_tunnel.rs hands bytes to a Tokio
//! channel instead of this repository's 1MiB blocking stream policy; no implementation copied.
mod jni;

const WORDS: usize = 4;
const MAX_PENDING: usize = 1_048_576;
const CLOSED: i32 = 1;
const FAILED: i32 = 2;
#[derive(Default, Debug)]
struct Queue {
    head: usize,
    count: usize,
    bytes: usize,
    flags: i32,
}
#[derive(Debug, PartialEq)]
enum Offer {
    Ignored,
    Limit,
    Store { grow: bool, slot: usize },
}
impl Queue {
    fn decode(w: [i32; WORDS]) -> Self {
        Self {
            head: w[0] as usize,
            count: w[1] as usize,
            bytes: w[2] as usize,
            flags: w[3],
        }
    }
    fn encode(&self) -> [i32; WORDS] {
        [
            self.head as i32,
            self.count as i32,
            self.bytes as i32,
            self.flags,
        ]
    }
    fn offer(&mut self, size: usize, capacity: usize) -> Offer {
        if size == 0 || self.flags != 0 {
            return Offer::Ignored;
        }
        if size > MAX_PENDING - self.bytes {
            self.flags |= FAILED;
            return Offer::Limit;
        }
        let grow = self.count == capacity;
        Offer::Store {
            grow,
            slot: if grow {
                self.count
            } else {
                (self.head + self.count) % capacity
            },
        }
    }
    fn pushed(&mut self, size: usize, grown: bool) {
        if grown {
            self.head = 0
        }
        self.count += 1;
        self.bytes += size;
    }
    fn popped(&mut self, size: usize, capacity: usize) {
        self.head = (self.head + 1) % capacity;
        self.count -= 1;
        self.bytes -= size;
    }
    fn prepend_tail(&mut self, size: usize, capacity: usize) {
        self.head = (self.head + capacity - 1) % capacity;
        self.count += 1;
        self.bytes += size;
    }
    fn fail(&mut self) -> bool {
        if self.flags != 0 {
            return false;
        }
        self.flags |= FAILED;
        true
    }
    fn close(&mut self) -> bool {
        if self.flags & CLOSED != 0 {
            return false;
        }
        self.flags |= CLOSED;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_limit_is_accepted_and_failure_retains_pending() {
        let mut q = Queue::default();
        assert_eq!(
            q.offer(MAX_PENDING, 8),
            Offer::Store {
                grow: false,
                slot: 0
            }
        );
        q.pushed(MAX_PENDING, false);
        assert_eq!(q.offer(1, 8), Offer::Limit);
        assert_eq!(q.bytes, MAX_PENDING);
        assert!(!q.fail());
        q.popped(MAX_PENDING, 8);
        assert_eq!(q.bytes, 0);
        assert_eq!(q.flags, FAILED);
        assert!(q.close());
        assert_eq!(q.flags, CLOSED | FAILED);
    }
    #[test]
    fn split_reinserts_tail_ahead_of_later_chunks() {
        let mut q = Queue::default();
        q.pushed(5, false);
        q.pushed(7, false);
        let slot = q.head;
        q.popped(5, 8);
        q.prepend_tail(3, 8);
        assert_eq!(q.head, slot);
        assert_eq!((q.count, q.bytes), (2, 10));
        let restored = Queue::decode(q.encode());
        assert_eq!(restored.encode(), q.encode());
    }
    #[test]
    fn ring_wrap_then_growth_preserves_order_metadata() {
        let mut q = Queue::default();
        for _ in 0..8 {
            q.pushed(1, false)
        }
        for _ in 0..3 {
            q.popped(1, 8)
        }
        for slot in 0..3 {
            assert_eq!(q.offer(1, 8), Offer::Store { grow: false, slot });
            q.pushed(1, false)
        }
        assert_eq!(
            q.offer(1, 8),
            Offer::Store {
                grow: true,
                slot: 8
            }
        );
        q.pushed(1, true);
        assert_eq!((q.head, q.count, q.bytes), (0, 9, 9));
    }
    #[test]
    fn empty_late_and_repeated_termination_are_noops() {
        let mut q = Queue::default();
        assert_eq!(q.offer(0, 8), Offer::Ignored);
        assert!(q.close());
        assert!(!q.close());
        assert!(!q.fail());
        assert_eq!(q.offer(1, 8), Offer::Ignored);
        let mut q = Queue::default();
        assert!(q.fail());
        assert!(!q.fail());
        assert_eq!(q.offer(0, 8), Offer::Ignored);
        assert!(q.close());
    }
}
