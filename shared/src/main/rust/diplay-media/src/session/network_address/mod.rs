//! Hotspot address selection, preferred-family deadline and evidence required to persist a flip.
//! Android supplies InetAddress capability facts; addresses, clocks, waits and preferences stay there.
mod jni;
const IPV4_USABLE: i32 = 1;
const IPV6_LINK_LOCAL: i32 = 2;
const IPV6_SCOPED: i32 = 4;
const IPV6: i32 = 8;
const IPV4: i32 = 16;

fn permitted(facts: i32, strict: bool) -> bool {
    facts & IPV6 == 0 || facts & IPV6_SCOPED != 0 || !strict && facts & IPV6_LINK_LOCAL == 0
}
fn select(facts: &[i32], operation: i32, strict: bool) -> i32 {
    if operation == 4 {
        return facts
            .iter()
            .position(|value| value & IPV6_LINK_LOCAL != 0)
            .or_else(|| facts.iter().position(|value| value & IPV4 != 0))
            .or_else(|| facts.iter().position(|value| value & IPV6 != 0))
            .or_else(|| (!facts.is_empty()).then_some(0))
            .map_or(-1, |index| index as i32);
    }
    let mut link_local = -1;
    // Prefer IPv6 only for an explicit family preference; default and alternate retain input order.
    if operation == 1 {
        if let Some(index) = facts
            .iter()
            .position(|&value| (!strict || permitted(value, true)) && value & IPV6_LINK_LOCAL != 0)
        {
            return index as i32;
        }
    }
    for (index, &value) in facts.iter().enumerate() {
        if strict && !permitted(value, true) {
            continue;
        }
        if value & IPV4_USABLE != 0 && operation != 2 {
            return index as i32;
        }
        if value & IPV6_LINK_LOCAL != 0 && operation != 3 && link_local < 0 {
            link_local = index as i32;
        }
    }
    link_local
}
fn readiness(prefer_ipv6: bool, facts: &[i32]) -> i32 {
    if !prefer_ipv6 {
        0
    } else if facts.iter().any(|value| value & IPV6_SCOPED != 0) {
        1
    } else {
        2
    }
}
fn flip_gate(phone_on_hotspot: bool, family_reachable: bool) -> i32 {
    if !phone_on_hotspot {
        1
    } else if !family_reachable {
        2
    } else {
        0
    }
}
fn time(token: [i64; 2], elapsed: bool, now: i64) -> i64 {
    if elapsed {
        now.wrapping_sub(token[0])
    } else {
        token[1].wrapping_sub(now).clamp(0, 250)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn selection_keeps_preference_fallback_order_and_scoped_recovery_filter() {
        let facts = [
            IPV6 | IPV6_LINK_LOCAL,
            IPV4_USABLE,
            IPV6 | IPV6_LINK_LOCAL | IPV6_SCOPED,
        ];
        assert_eq!(select(&facts, 0, false), 1);
        assert_eq!(select(&facts, 1, false), 0);
        assert_eq!(select(&facts, 2, true), 2);
        assert_eq!(select(&facts, 3, true), 1);
        assert_eq!(readiness(true, &facts[..2]), 2);
        assert_eq!(readiness(true, &facts), 1);
        assert_eq!(readiness(false, &facts), 0);
    }
    #[test]
    fn bonjour_selection_keeps_all_ipv4_and_global_ipv6_fallbacks() {
        assert_eq!(select(&[], 4, false), -1);
        assert_eq!(
            select(&[0, IPV6, IPV4, IPV6 | IPV6_LINK_LOCAL], 4, false),
            3
        );
        assert_eq!(select(&[IPV6, IPV4], 4, false), 1);
        assert_eq!(select(&[0, IPV6], 4, false), 1);
        assert_eq!(select(&[0], 4, false), 0);
    }
    #[test]
    fn deadlines_wrap_like_kotlin_and_flip_requires_both_generation_facts() {
        let start = i64::MAX - 2;
        let token = [start, start.wrapping_add(1000)];
        assert_eq!(time(token, false, start), 250);
        assert_eq!(time(token, false, token[1] - 1), 1);
        assert_eq!(time(token, false, token[1]), 0);
        assert_eq!(time(token, true, start.wrapping_add(1000)), 1000);
        assert_eq!(flip_gate(false, true), 1);
        assert_eq!(flip_gate(true, false), 2);
        assert_eq!(flip_gate(true, true), 0);
    }
}
