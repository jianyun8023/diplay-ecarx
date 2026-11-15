//! Projection publication decisions; successful PUBACK is the only applied-state commit.
pub(super) struct Projection(pub [i64; 5]);
pub(super) const NAVIGATION: &str = "carPlayNavigation";
pub(super) const NONE: &str = "noProjection";

impl Projection {
    // wanted, published (-1 before success), touched, queued, packet id
    pub fn new() -> Self {
        Self([0, -1, 0, 0, 0])
    }
    fn dirty(&self) -> bool {
        (self.0[0] != 0 || self.0[2] != 0) && self.0[0] != self.0[1]
    }
    pub fn update(&mut self, wanted: bool) -> i32 {
        self.0[0] = wanted as i64;
        if !self.dirty() {
            return 0;
        }
        if self.0[3] != 0 {
            return 1;
        }
        self.0[3] = 1;
        2
    }
    pub fn plan(&mut self) -> Option<&'static str> {
        if self.0[3] == 0 {
            return None;
        }
        self.0[3] = 0;
        self.dirty()
            .then_some(if self.0[0] != 0 { NAVIGATION } else { NONE })
    }
    pub fn commit(&mut self, navigation: bool) {
        self.0[1] = navigation as i64;
        self.0[2] = 1;
    }
    pub fn packet_id(&mut self) -> i32 {
        self.0[4] = self.0[4] % 65535 + 1;
        self.0[4] as i32
    }
    pub fn wanted(&self) -> &'static str {
        if self.0[0] != 0 {
            "navi"
        } else {
            "-"
        }
    }
    pub fn description(&self) -> String {
        format!(
            " projection={} wanted={}",
            match self.0[1] {
                0 => NONE,
                1 => NAVIGATION,
                _ => "unset",
            },
            self.wanted()
        )
    }
}

pub(super) fn ongoing(on: bool, destination: &[u16]) -> Vec<u16> {
    let mut out: Vec<u16> = format!(
        "{{\"naviEvent\":\"{}\",\"naviIcon\":\"hubsNavi\",\"destination\":\"",
        if on { "ongoingNavi" } else { "idle" }
    )
    .encode_utf16()
    .collect();
    out.extend(super::escape(destination));
    out.extend("\",\"iconLeft\":\"naviNewIcon\",\"iconCenter\":\"cancelNaviIcon\",\"iconRight\":\"unmuteIcon\",\"destinationFlagIcon\":\"destinationFlagIcon\"}".encode_utf16());
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn never_clear_another_source_coalesce_before_send_and_commit_only_success() {
        let mut p = Projection::new();
        assert_eq!(p.update(false), 0);
        assert_eq!(p.update(true), 2);
        assert_eq!(p.update(false), 0);
        assert_eq!(p.plan(), None);
        assert_eq!(p.update(true), 2);
        assert_eq!(p.plan(), Some(NAVIGATION));
        assert_eq!(p.update(true), 2); // failed PUBACK does not mark the state applied
        assert_eq!(p.plan(), Some(NAVIGATION));
        p.commit(true);
        assert_eq!(p.update(true), 0);
        assert_eq!(p.update(false), 2);
        assert_eq!(p.plan(), Some(NONE));
        p.commit(false);
        assert_eq!(p.update(false), 0);
        for id in 1..=65535 {
            assert_eq!(p.packet_id(), id);
        }
        assert_eq!(p.packet_id(), 1);
    }
}
