//! Controller generations, permission/re-enumeration budgets, transport adoption and teardown plans.
//! The JVM retains SDK objects; this value contains the only active lifecycle/decision state.
mod jni;
mod dashboard;

/// Number of leading words in the opaque token that carry scalar state.
const HEADER: usize = 14;
const CLOSED: i64 = 1;
const PERMISSION: i64 = 2;
const VPN_BOUND: i64 = 4;
const RADIO_RETURN: i64 = 8;
const PREREQUISITE_FAILED: i64 = 16;
const WIRED_ACTIVE: i64 = 32;

/// Lifecycle stage the controller is in. Replaces the raw `header[2]` code.
///
/// The codes match `ControllerLifecycleCore.query(1)` (phase) and the stage values
/// passed to `decide` decisions 4/6, so they stay stable across the JNI boundary.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Phase {
    /// Newly created controller: no handshake started yet.
    Initial,
    /// MFi authentication over the wired link (`beginMfi`).
    Mfi,
    /// Wireless handshake (`beginWireless`).
    Wireless,
    /// Wired iPhone identification (`beginIphone`).
    Iphone,
    /// Wired re-enumeration after a recoverable failure (`beginReenumeration`).
    Reenumeration,
    /// Data paths opened (`beginDataPaths`).
    DataPaths,
    /// Wired control channel established (`beginWiredControl`).
    WiredControl,
}

impl Phase {
    fn code(self) -> i64 {
        match self {
            Phase::Initial => 0,
            Phase::Mfi => 1,
            Phase::Wireless => 2,
            Phase::Iphone => 3,
            Phase::Reenumeration => 4,
            Phase::DataPaths => 5,
            Phase::WiredControl => 6,
        }
    }

    fn from_code(code: i64) -> Option<Self> {
        Some(match code {
            0 => Phase::Initial,
            1 => Phase::Mfi,
            2 => Phase::Wireless,
            3 => Phase::Iphone,
            4 => Phase::Reenumeration,
            5 => Phase::DataPaths,
            6 => Phase::WiredControl,
            _ => return None,
        })
    }
}

/// Event codes crossing the JNI boundary (`NativeCarPlayController_event`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum EventCode {
    BeginMfi,
    BeginIphone,
    BeginWireless,
    BeginClose,
    BeginReenumeration,
    BeginDataPaths,
    BeginWiredControl,
    ResetIphoneDiscovery,
    ResetPermissionClaim,
    ClaimPermission,
    PermissionDenied,
    InvalidateAvailability,
    InvalidatePermission,
    InvalidateWireless,
    AwaitRadioReturn,
    ConsumeRadioReturn,
    ReportPrerequisiteFailure,
    SessionActive,
    StartSent,
    BeginVpnBind,
    VpnBindFailed,
    BeginVpnUnbind,
    IphoneNotListed,
}

impl EventCode {
    const ALL: [EventCode; 23] = [
        EventCode::BeginMfi,
        EventCode::BeginIphone,
        EventCode::BeginWireless,
        EventCode::BeginClose,
        EventCode::BeginReenumeration,
        EventCode::BeginDataPaths,
        EventCode::BeginWiredControl,
        EventCode::ResetIphoneDiscovery,
        EventCode::ResetPermissionClaim,
        EventCode::ClaimPermission,
        EventCode::PermissionDenied,
        EventCode::InvalidateAvailability,
        EventCode::InvalidatePermission,
        EventCode::InvalidateWireless,
        EventCode::AwaitRadioReturn,
        EventCode::ConsumeRadioReturn,
        EventCode::ReportPrerequisiteFailure,
        EventCode::SessionActive,
        EventCode::StartSent,
        EventCode::BeginVpnBind,
        EventCode::VpnBindFailed,
        EventCode::BeginVpnUnbind,
        EventCode::IphoneNotListed,
    ];

    fn code(self) -> i64 {
        match self {
            EventCode::BeginMfi => 1,
            EventCode::BeginIphone => 2,
            EventCode::BeginWireless => 3,
            EventCode::BeginClose => 4,
            EventCode::BeginReenumeration => 5,
            EventCode::BeginDataPaths => 6,
            EventCode::BeginWiredControl => 7,
            EventCode::ResetIphoneDiscovery => 8,
            EventCode::ResetPermissionClaim => 9,
            EventCode::ClaimPermission => 10,
            EventCode::PermissionDenied => 11,
            EventCode::InvalidateAvailability => 12,
            EventCode::InvalidatePermission => 13,
            EventCode::InvalidateWireless => 14,
            EventCode::AwaitRadioReturn => 15,
            EventCode::ConsumeRadioReturn => 16,
            EventCode::ReportPrerequisiteFailure => 17,
            EventCode::SessionActive => 18,
            EventCode::StartSent => 19,
            EventCode::BeginVpnBind => 20,
            EventCode::VpnBindFailed => 21,
            EventCode::BeginVpnUnbind => 22,
            EventCode::IphoneNotListed => 23,
        }
    }

    fn from_code(code: i64) -> Option<Self> {
        Self::ALL.into_iter().find(|&event| event.code() == code)
    }
}

/// Decision codes crossing the JNI boundary (`NativeCarPlayController_decide`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum DecisionCode {
    WiredCurrent,
    CanStartWireless,
    CanRestartWireless,
    PermissionPollCurrent,
    AvailabilityPollCurrent,
    ReenumerationCurrent,
    PlatformGrantCurrent,
    FailureCurrent,
    StatusCurrent,
    ControlLoopTimeout,
}

impl DecisionCode {
    const ALL: [DecisionCode; 10] = [
        DecisionCode::WiredCurrent,
        DecisionCode::CanStartWireless,
        DecisionCode::CanRestartWireless,
        DecisionCode::PermissionPollCurrent,
        DecisionCode::AvailabilityPollCurrent,
        DecisionCode::ReenumerationCurrent,
        DecisionCode::PlatformGrantCurrent,
        DecisionCode::FailureCurrent,
        DecisionCode::StatusCurrent,
        DecisionCode::ControlLoopTimeout,
    ];

    fn code(self) -> i64 {
        match self {
            DecisionCode::WiredCurrent => 0,
            DecisionCode::CanStartWireless => 1,
            DecisionCode::CanRestartWireless => 2,
            DecisionCode::PermissionPollCurrent => 3,
            DecisionCode::AvailabilityPollCurrent => 4,
            DecisionCode::ReenumerationCurrent => 5,
            DecisionCode::PlatformGrantCurrent => 6,
            DecisionCode::FailureCurrent => 7,
            DecisionCode::StatusCurrent => 8,
            DecisionCode::ControlLoopTimeout => 9,
        }
    }

    fn from_code(code: i64) -> Option<Self> {
        Self::ALL.into_iter().find(|&decision| decision.code() == code)
    }
}

/// Key-event codes crossing the JNI boundary (`NativeCarPlayController_key`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum KeyEventCode {
    GrantPending,
    ClaimPlatformGrant,
    PlatformGrantStarted,
    PlatformGrantFinished,
}

impl KeyEventCode {
    const ALL: [KeyEventCode; 4] = [
        KeyEventCode::GrantPending,
        KeyEventCode::ClaimPlatformGrant,
        KeyEventCode::PlatformGrantStarted,
        KeyEventCode::PlatformGrantFinished,
    ];

    fn code(self) -> i64 {
        match self {
            KeyEventCode::GrantPending => 0,
            KeyEventCode::ClaimPlatformGrant => 1,
            KeyEventCode::PlatformGrantStarted => 2,
            KeyEventCode::PlatformGrantFinished => 3,
        }
    }

    fn from_code(code: i64) -> Option<Self> {
        Self::ALL.into_iter().find(|&event| event.code() == code)
    }
}

/// Named replacement for the opaque 14-word `header` array plus the two grant vectors.
///
/// Field order mirrors the wire layout: booleans map to single bits, generation
/// counters are `i32` values that wrap (matching the old `bump` behaviour), and
/// the two trailing `usize` counts are derived from the grant vectors on encode.
struct ControllerState {
    /// `header[0]` bit 0: wireless handshake instead of wired.
    wireless: bool,
    /// `header[0]` bit 1: vehicle location reporting is available.
    location: bool,
    /// `header[1]` bit 0: session has begun closing.
    closed: bool,
    /// `header[1]` bit 1: a permission claim is outstanding.
    permission: bool,
    /// `header[1]` bit 2: the VPN transport is attached.
    vpn_bound: bool,
    /// `header[1]` bit 3: a radio-return wait is pending.
    radio_return: bool,
    /// `header[1]` bit 4: a prerequisite failure has already been reported.
    prerequisite_failed: bool,
    /// `header[1]` bit 5: wired session is currently active.
    wired_active: bool,
    /// `header[2]`: lifecycle stage.
    phase: Phase,
    /// `header[3]`: diagnostic run generation.
    diagnostic_run: i32,
    /// `header[4]`: wireless generation.
    wireless_generation: i32,
    /// `header[5]`: availability generation.
    availability_generation: i32,
    /// `header[6]`: permission generation.
    permission_generation: i32,
    /// `header[7]`: wired re-enumeration attempts.
    reenumeration_attempts: i32,
    /// `header[8]`: whether a wired StartSession was sent.
    wired_start_sent: bool,
    /// `header[9]`: monotonic time (ns) of the wired StartSession send.
    wired_start_sent_at_nanos: i64,
    /// `header[10]`: whether a USB-discovery wait is in progress.
    reenumeration_waiting: bool,
    /// `header[11]`: monotonic time (ms) the USB-discovery wait started.
    reenumeration_started_at: i64,
    /// `header[12]` count and payload: keys already claimed from the platform.
    grant_attempts: Vec<Vec<u16>>,
    /// `header[13]` count and payload: keys whose platform grant is pending.
    pending_grants: Vec<Vec<u16>>,
}

impl ControllerState {
    fn new(wireless: bool, location: bool) -> Self {
        Self {
            wireless,
            location,
            closed: false,
            permission: false,
            vpn_bound: false,
            radio_return: false,
            prerequisite_failed: false,
            wired_active: false,
            phase: Phase::Initial,
            diagnostic_run: 0,
            wireless_generation: 0,
            availability_generation: 0,
            permission_generation: 0,
            reenumeration_attempts: 0,
            wired_start_sent: false,
            wired_start_sent_at_nanos: 0,
            reenumeration_waiting: false,
            reenumeration_started_at: 0,
            grant_attempts: Vec::new(),
            pending_grants: Vec::new(),
        }
    }

    /// Validation ranges are unchanged from the previous raw-header contract.
    fn valid_header(h: &[i64; HEADER]) -> bool {
        (0..4).contains(&h[0])
            && (0..64).contains(&h[1])
            && (0..=6).contains(&h[2])
            && h[3..8].iter().all(|&v| v == v as i32 as i64)
            && (0..=1).contains(&h[8])
            && (0..=1).contains(&h[10])
            && h[12] >= 0
            && h[13] >= 0
    }

    fn from_header(
        header: &[i64; HEADER],
        grant_attempts: Vec<Vec<u16>>,
        pending_grants: Vec<Vec<u16>>,
    ) -> Option<Self> {
        Some(Self {
            wireless: header[0] & 1 != 0,
            location: header[0] & 2 != 0,
            closed: header[1] & CLOSED != 0,
            permission: header[1] & PERMISSION != 0,
            vpn_bound: header[1] & VPN_BOUND != 0,
            radio_return: header[1] & RADIO_RETURN != 0,
            prerequisite_failed: header[1] & PREREQUISITE_FAILED != 0,
            wired_active: header[1] & WIRED_ACTIVE != 0,
            phase: Phase::from_code(header[2])?,
            diagnostic_run: header[3] as i32,
            wireless_generation: header[4] as i32,
            availability_generation: header[5] as i32,
            permission_generation: header[6] as i32,
            reenumeration_attempts: header[7] as i32,
            wired_start_sent: header[8] != 0,
            wired_start_sent_at_nanos: header[9],
            reenumeration_waiting: header[10] != 0,
            reenumeration_started_at: header[11],
            grant_attempts,
            pending_grants,
        })
    }

    fn decode(words: &[i64]) -> Option<Self> {
        let header: [i64; HEADER] = words.get(..HEADER)?.try_into().ok()?;
        if !Self::valid_header(&header) {
            return None;
        }
        let mut cursor = HEADER;
        let mut sets = [Vec::new(), Vec::new()];
        for (set, &count) in sets.iter_mut().zip(&header[12..14]) {
            if count as u64 > words.len().saturating_sub(cursor) as u64 {
                return None;
            }
            for _ in 0..count {
                let length = *words.get(cursor)?;
                cursor += 1;
                if length < 0 || length as u64 > words.len().saturating_sub(cursor) as u64 {
                    return None;
                }
                let end = cursor + length as usize;
                let mut key = Vec::new();
                for &unit in &words[cursor..end] {
                    key.push(u16::try_from(unit).ok()?);
                }
                if set.contains(&key) {
                    return None;
                }
                set.push(key);
                cursor = end;
            }
        }
        if cursor != words.len() {
            return None;
        }
        let [grant_attempts, pending_grants] = sets;
        Self::from_header(&header, grant_attempts, pending_grants)
    }

    /// Fast path for read-only gates: validates and decodes only the scalar header.
    fn decode_header(words: &[i64]) -> Option<Self> {
        let header: [i64; HEADER] = words.get(..HEADER)?.try_into().ok()?;
        if !Self::valid_header(&header) {
            return None;
        }
        Self::from_header(&header, Vec::new(), Vec::new())
    }

    fn encode(&self) -> Vec<i64> {
        let mut header = [0; HEADER];
        header[0] = self.wireless as i64 | ((self.location as i64) << 1);
        let mut flags = 0;
        if self.closed {
            flags |= CLOSED;
        }
        if self.permission {
            flags |= PERMISSION;
        }
        if self.vpn_bound {
            flags |= VPN_BOUND;
        }
        if self.radio_return {
            flags |= RADIO_RETURN;
        }
        if self.prerequisite_failed {
            flags |= PREREQUISITE_FAILED;
        }
        if self.wired_active {
            flags |= WIRED_ACTIVE;
        }
        header[1] = flags;
        header[2] = self.phase.code();
        header[3] = i64::from(self.diagnostic_run);
        header[4] = i64::from(self.wireless_generation);
        header[5] = i64::from(self.availability_generation);
        header[6] = i64::from(self.permission_generation);
        header[7] = i64::from(self.reenumeration_attempts);
        header[8] = self.wired_start_sent as i64;
        header[9] = self.wired_start_sent_at_nanos;
        header[10] = self.reenumeration_waiting as i64;
        header[11] = self.reenumeration_started_at;
        header[12] = self.grant_attempts.len() as i64;
        header[13] = self.pending_grants.len() as i64;
        let mut words = header.to_vec();
        for key in self.grant_attempts.iter().chain(&self.pending_grants) {
            words.push(key.len() as i64);
            words.extend(key.iter().map(|&unit| unit as i64));
        }
        words
    }

    /// Bump an `i32` generation field with wrapping arithmetic, returning the new value.
    fn bump(field: &mut i32) -> i64 {
        *field = field.wrapping_add(1);
        i64::from(*field)
    }

    fn wired_current(&self, run: i64) -> bool {
        !self.closed
            && i64::from(self.diagnostic_run) == run
            && matches!(self.phase, Phase::DataPaths | Phase::WiredControl)
    }

    fn query(&self, field: i32) -> i64 {
        match field {
            0 => self.closed as i64,
            1 => self.phase.code(),
            2 => i64::from(self.diagnostic_run),
            3 => i64::from(self.wireless_generation),
            4 => i64::from(self.availability_generation),
            5 => i64::from(self.permission_generation),
            6 => i64::from(self.reenumeration_attempts),
            7 => self.wired_active as i64,
            8 => self.wired_start_sent as i64,
            9 => self.wired_start_sent_at_nanos,
            10 => self.vpn_bound as i64,
            _ => 0,
        }
    }

    fn event(&mut self, event: EventCode, value: i64, present: bool) -> i64 {
        match event {
            EventCode::BeginMfi => {
                if self.closed {
                    0
                } else {
                    self.phase = Phase::Mfi;
                    1
                }
            }
            EventCode::BeginIphone => {
                if self.closed {
                    0
                } else {
                    Self::bump(&mut self.diagnostic_run);
                    self.phase = Phase::Iphone;
                    1
                }
            }
            EventCode::BeginWireless => {
                Self::bump(&mut self.diagnostic_run);
                Self::bump(&mut self.availability_generation);
                self.phase = Phase::Wireless;
                Self::bump(&mut self.wireless_generation)
            }
            EventCode::BeginClose => {
                if self.closed {
                    0
                } else {
                    self.closed = true;
                    1
                }
            }
            EventCode::BeginReenumeration => {
                self.phase = Phase::Reenumeration;
                Self::bump(&mut self.reenumeration_attempts)
            }
            EventCode::BeginDataPaths => {
                if self.closed {
                    0
                } else {
                    self.phase = Phase::DataPaths;
                    1
                }
            }
            EventCode::BeginWiredControl => {
                if self.wired_current(value) {
                    self.phase = Phase::WiredControl;
                    1
                } else {
                    0
                }
            }
            EventCode::ResetIphoneDiscovery => {
                // Discovery reset follows the phase/run commit, matching platform callback order.
                self.grant_attempts.clear();
                self.pending_grants.clear();
                self.reenumeration_attempts = 0;
                self.reenumeration_waiting = false;
                0
            }
            EventCode::ResetPermissionClaim => {
                self.permission = false;
                0
            }
            EventCode::ClaimPermission => {
                let claim = !self.permission;
                self.permission = true;
                claim as i64
            }
            EventCode::PermissionDenied => {
                self.permission = true;
                0
            }
            EventCode::InvalidateAvailability => Self::bump(&mut self.availability_generation),
            EventCode::InvalidatePermission => Self::bump(&mut self.permission_generation),
            EventCode::InvalidateWireless => Self::bump(&mut self.wireless_generation),
            EventCode::AwaitRadioReturn => {
                self.radio_return = true;
                0
            }
            EventCode::ConsumeRadioReturn => {
                let pending = self.radio_return;
                self.radio_return = false;
                pending as i64
            }
            EventCode::ReportPrerequisiteFailure => {
                let first = !self.prerequisite_failed;
                self.prerequisite_failed = true;
                first as i64
            }
            EventCode::SessionActive => {
                self.wired_active = present;
                0
            }
            EventCode::StartSent => {
                self.wired_start_sent = present;
                self.wired_start_sent_at_nanos = value;
                0
            }
            EventCode::BeginVpnBind => {
                let bind = !self.vpn_bound;
                self.vpn_bound = true;
                bind as i64
            }
            EventCode::VpnBindFailed => {
                self.vpn_bound = false;
                0
            }
            EventCode::BeginVpnUnbind => {
                let unbind = self.vpn_bound;
                self.vpn_bound = false;
                unbind as i64
            }
            EventCode::IphoneNotListed => {
                // First absence starts a monotonic USB-discovery wait.
                if !self.reenumeration_waiting {
                    self.reenumeration_waiting = true;
                    self.reenumeration_started_at = value;
                }
                (value.wrapping_sub(self.reenumeration_started_at) >= timing_policy(12)) as i64
            }
        }
    }

    fn decide(&self, decision: DecisionCode, value: i64, other: i64, facts: i64) -> i64 {
        match decision {
            DecisionCode::WiredCurrent => self.wired_current(value) as i64,
            DecisionCode::CanStartWireless => {
                (!self.closed
                    && (!matches!(facts & 1, 1) || value == i64::from(self.wireless_generation))
                    && facts & 2 == 0) as i64
            }
            DecisionCode::CanRestartWireless => (!self.closed && facts & 3 == 3) as i64,
            DecisionCode::PermissionPollCurrent => {
                (!self.closed
                    && value == i64::from(self.permission_generation)
                    && (facts & 1 == 0 || self.phase == Phase::Mfi)) as i64
            }
            DecisionCode::AvailabilityPollCurrent => {
                (!self.closed
                    && Phase::from_code(value) == Some(self.phase)
                    && other == i64::from(self.availability_generation)) as i64
            }
            DecisionCode::ReenumerationCurrent => {
                (!self.closed
                    && self.phase == Phase::Reenumeration
                    && value == i64::from(self.reenumeration_attempts)
                    && other == i64::from(self.availability_generation)) as i64
            }
            DecisionCode::PlatformGrantCurrent => {
                (!self.closed
                    && Phase::from_code(value) == Some(self.phase)
                    && other == i64::from(self.permission_generation)) as i64
            }
            DecisionCode::FailureCurrent => {
                (!self.closed
                    && (facts & 1 == 0 || value == i64::from(self.wireless_generation))) as i64
            }
            DecisionCode::StatusCurrent => {
                // Failure may still post after retirement; progress may only post for the live run.
                (!self.closed
                    && facts & 1 != 0
                    && (facts & 2 == 0
                        || value == i64::from(self.wireless_generation) && facts & 12 != 0)
                    && facts & 16 == 0) as i64
            }
            DecisionCode::ControlLoopTimeout => {
                if !self.wireless {
                    i64::MAX
                } else if self.location {
                    24 * 60 * 60 * 1000
                } else {
                    5 * 60 * 1000
                }
            }
        }
    }

    fn key(&mut self, event: KeyEventCode, key: Vec<u16>) -> bool {
        match event {
            KeyEventCode::GrantPending => self.pending_grants.contains(&key),
            KeyEventCode::ClaimPlatformGrant => {
                if self.grant_attempts.contains(&key) {
                    false
                } else {
                    self.grant_attempts.push(key);
                    true
                }
            }
            KeyEventCode::PlatformGrantStarted => {
                if !self.pending_grants.contains(&key) {
                    self.pending_grants.push(key);
                }
                true
            }
            KeyEventCode::PlatformGrantFinished => {
                if let Some(index) = self.pending_grants.iter().position(|k| k == &key) {
                    self.pending_grants.remove(index);
                }
                true
            }
        }
    }

    fn teardown_plan(&self) -> Vec<i32> {
        if self.wireless {
            vec![1, 2, 4, 6, 7]
        } else {
            vec![3, 4, 5, 6, 7]
        }
    }
}

fn wireless_close_plan(current: bool) -> Vec<i32> {
    let mut actions = vec![1, 2, 3];
    if current {
        actions.push(4);
    }
    actions.extend([5, 6, 7, 8, 9, 10]);
    actions
}

fn handoff_in_progress(requested: bool, tunnel: bool, session: bool) -> bool {
    requested || tunnel || session
}
fn expected_control_close(stage: i32, facts: i32) -> bool {
    stage == 3 && facts & 7 == 7 && facts & 56 == 0
}
fn configuration(found: bool, mode: bool, started: bool, tried: i32, maximum: i32) -> i32 {
    if found && mode && started && tried == 0 {
        2
    } else if found && mode {
        0
    } else if found && tried > 0 {
        1
    } else if tried < maximum {
        2
    } else {
        3
    }
}

fn timing_policy(field: i32) -> i64 {
    match field {
        0 => 120_000,
        1 => 500,
        2 => 2_000,
        3 => 45_000,
        4 => 60_000,
        5 => 20_000,
        6 => 300_000,
        7 => 10_000,
        8 => 60_000,
        9 => 35_000,
        10 => 2_000,
        11 => 10_000,
        12 => 20_000,
        13 => 2_000,
        _ => 0,
    }
}

/// Event codes crossing the JNI boundary (`NativeCarPlayController_vpnEvent`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum VpnEvent {
    /// Report whether an attach is already resolved (`0`, `1` or `2`).
    PrepareAttach,
    /// Commit a successful attach and advance the generation.
    BeginAttach,
    /// Commit a released attach and advance the generation.
    Release,
    /// Whether the stored attachment is active for this generation and owner.
    Current,
    /// Whether the stored generation matches.
    Matches,
}

impl VpnEvent {
    const ALL: [VpnEvent; 5] = [
        VpnEvent::PrepareAttach,
        VpnEvent::BeginAttach,
        VpnEvent::Release,
        VpnEvent::Current,
        VpnEvent::Matches,
    ];

    fn code(self) -> i64 {
        match self {
            VpnEvent::PrepareAttach => 0,
            VpnEvent::BeginAttach => 1,
            VpnEvent::Release => 2,
            VpnEvent::Current => 3,
            VpnEvent::Matches => 4,
        }
    }

    fn from_code(code: i64) -> Option<Self> {
        Self::ALL.into_iter().find(|&event| event.code() == code)
    }
}

/// The service's attachment generation is independent of the controller run generation.
struct Vpn {
    active: bool,
    generation: i32,
}

impl Vpn {
    fn new() -> Self {
        Self {
            active: false,
            generation: 0,
        }
    }

    /// Wire token `[active, generation]`.
    fn decode(words: &[i64]) -> Option<Self> {
        let [active, generation] = words.get(..2)?.try_into().ok()?;
        if !(0..=1).contains(&active) || generation != generation as i32 as i64 {
            return None;
        }
        Some(Self {
            active: active != 0,
            generation: generation as i32,
        })
    }

    fn encode(&self) -> [i64; 2] {
        [self.active as i64, i64::from(self.generation)]
    }

    fn event(&mut self, event: VpnEvent, generation: i32, current: bool) -> i32 {
        match event {
            VpnEvent::PrepareAttach => {
                if !current {
                    0
                } else if self.active {
                    2
                } else {
                    1
                }
            }
            VpnEvent::BeginAttach => {
                self.active = true;
                self.generation = self.generation.wrapping_add(1);
                self.generation
            }
            VpnEvent::Release => {
                self.active = false;
                self.generation = self.generation.wrapping_add(1);
                self.generation
            }
            VpnEvent::Current => (self.active && self.generation == generation && current) as i32,
            VpnEvent::Matches => (self.generation == generation) as i32,
        }
    }
}

/// Event codes crossing the JNI boundary (`NativeCarPlayController_proofEvent`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ProofEvent {
    /// Open a handshake epoch and reset the confirmation factors.
    Begin,
    /// Bind a session; clears stale confirmation factors but keeps the epoch open.
    Activate,
    /// Record the authenticated factor and confirm if both factors are present.
    Authenticated,
    /// Record the rendered factor and confirm if both factors are present.
    Rendered,
    /// End the session for a matching generation; keeps the epoch open.
    End,
    /// Forget the epoch and every factor.
    Clear,
    /// Whether a session is bound for this generation.
    HasActiveSession,
    /// Whether a frame has rendered for a bound session of this generation.
    HasRenderedFrame,
}

impl ProofEvent {
    const ALL: [ProofEvent; 8] = [
        ProofEvent::Begin,
        ProofEvent::Activate,
        ProofEvent::Authenticated,
        ProofEvent::Rendered,
        ProofEvent::End,
        ProofEvent::Clear,
        ProofEvent::HasActiveSession,
        ProofEvent::HasRenderedFrame,
    ];

    fn code(self) -> i64 {
        match self {
            ProofEvent::Begin => 0,
            ProofEvent::Activate => 1,
            ProofEvent::Authenticated => 2,
            ProofEvent::Rendered => 3,
            ProofEvent::End => 4,
            ProofEvent::Clear => 5,
            ProofEvent::HasActiveSession => 6,
            ProofEvent::HasRenderedFrame => 7,
        }
    }

    fn from_code(code: i64) -> Option<Self> {
        Self::ALL.into_iter().find(|&event| event.code() == code)
    }
}

/// Generation-scoped connection proof with two confirmation factors.
///
/// The wire token `[generation, flags]` packs four bits; they are named fields
/// here: `opened` (bit 3), `active_session` (bit 0), `authenticated` (bit 1) and
/// `rendered` (bit 2). Confirmation requires both factors plus an open epoch.
struct Proof {
    generation: i32,
    /// Handshake epoch opened by `Begin`; cleared when confirmation succeeds.
    opened: bool,
    /// A session was bound by `Activate`; gates `Authenticated` and `HasActiveSession`.
    active_session: bool,
    /// Authenticated report observed (one confirmation factor).
    authenticated: bool,
    /// Rendered-frame report observed (the other confirmation factor).
    rendered: bool,
}

impl Proof {
    fn new() -> Self {
        Self {
            generation: -1,
            opened: false,
            active_session: false,
            authenticated: false,
            rendered: false,
        }
    }

    /// Wire token `[generation, flags]` with `flags` in `0..16`.
    fn decode(words: &[i64]) -> Option<Self> {
        let [generation, flags] = words.get(..2)?.try_into().ok()?;
        if generation != generation as i32 as i64 || !(0..16).contains(&flags) {
            return None;
        }
        Some(Self {
            generation: generation as i32,
            opened: flags & 8 != 0,
            active_session: flags & 1 != 0,
            authenticated: flags & 2 != 0,
            rendered: flags & 4 != 0,
        })
    }

    fn encode(&self) -> [i64; 2] {
        let mut flags = 0;
        if self.opened {
            flags |= 8;
        }
        if self.active_session {
            flags |= 1;
        }
        if self.authenticated {
            flags |= 2;
        }
        if self.rendered {
            flags |= 4;
        }
        [i64::from(self.generation), flags]
    }

    fn event(&mut self, event: ProofEvent, generation: i32, same_session: bool) -> bool {
        match event {
            ProofEvent::Begin => {
                self.generation = generation;
                self.opened = true;
                self.active_session = false;
                self.authenticated = false;
                self.rendered = false;
                true
            }
            ProofEvent::Activate => {
                if self.generation != generation || same_session {
                    return false;
                }
                self.active_session = true;
                self.authenticated = false;
                self.rendered = false;
                true
            }
            ProofEvent::Authenticated => {
                if self.generation != generation || !self.active_session {
                    return false;
                }
                self.authenticated = true;
                self.confirm()
            }
            ProofEvent::Rendered => {
                if self.generation != generation || !same_session {
                    return false;
                }
                self.rendered = true;
                self.confirm()
            }
            ProofEvent::End => {
                if self.generation != generation || !same_session {
                    return false;
                }
                self.active_session = false;
                self.authenticated = false;
                self.rendered = false;
                true
            }
            ProofEvent::Clear => {
                self.generation = -1;
                self.opened = false;
                self.active_session = false;
                self.authenticated = false;
                self.rendered = false;
                true
            }
            ProofEvent::HasActiveSession => self.generation == generation && self.active_session,
            ProofEvent::HasRenderedFrame => {
                self.generation == generation && self.active_session && self.rendered
            }
        }
    }

    fn confirm(&mut self) -> bool {
        if !(self.opened && self.authenticated && self.rendered) {
            return false;
        }
        self.opened = false;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enum_codes_map_both_ways_and_reject_unknown_codes() {
        for code in 1..=23 {
            let event = EventCode::from_code(code).expect("known event code");
            assert_eq!(event.code(), code);
        }
        assert_eq!(EventCode::from_code(0), None);
        assert_eq!(EventCode::from_code(24), None);
        assert_eq!(EventCode::from_code(-1), None);

        for code in 0..=9 {
            let decision = DecisionCode::from_code(code).expect("known decision code");
            assert_eq!(decision.code(), code);
        }
        assert_eq!(DecisionCode::from_code(10), None);
        assert_eq!(DecisionCode::from_code(-1), None);

        for code in 0..=3 {
            let event = KeyEventCode::from_code(code).expect("known key-event code");
            assert_eq!(event.code(), code);
        }
        assert_eq!(KeyEventCode::from_code(4), None);
        assert_eq!(KeyEventCode::from_code(-1), None);

        for code in 0..=6 {
            let phase = Phase::from_code(code).expect("known phase code");
            assert_eq!(phase.code(), code);
        }
        assert_eq!(Phase::from_code(7), None);
    }

    #[test]
    fn encode_decode_roundtrip_preserves_every_field() {
        let mut state = ControllerState::new(true, true);
        state.event(EventCode::BeginMfi, 0, false);
        state.event(EventCode::BeginWireless, 0, false);
        state.event(EventCode::BeginDataPaths, 0, false);
        state.event(EventCode::SessionActive, 0, true);
        state.event(EventCode::StartSent, 12345, true);
        state.event(EventCode::AwaitRadioReturn, 0, false);
        state.event(EventCode::ReportPrerequisiteFailure, 0, false);
        state.event(EventCode::BeginVpnBind, 0, false);
        state.event(EventCode::InvalidateAvailability, 0, false);
        state.event(EventCode::InvalidatePermission, 0, false);
        state.key(
            KeyEventCode::ClaimPlatformGrant,
            "grant".encode_utf16().collect(),
        );
        state.key(
            KeyEventCode::PlatformGrantStarted,
            "pending".encode_utf16().collect(),
        );

        let words = state.encode();
        let decoded = ControllerState::decode(&words).expect("valid token");
        assert_eq!(decoded.encode(), words, "encode(decode(x)) must equal x");
        assert!(decoded.wireless);
        assert!(decoded.location);
        assert!(decoded.wired_active);
        assert!(decoded.wired_start_sent);
        assert_eq!(decoded.wired_start_sent_at_nanos, 12345);
        assert!(decoded.radio_return);
        assert!(decoded.prerequisite_failed);
        assert!(decoded.vpn_bound);
        assert_eq!(decoded.phase, Phase::DataPaths);
        assert_eq!(decoded.grant_attempts, vec!["grant".encode_utf16().collect::<Vec<_>>()]);
        assert_eq!(
            decoded.pending_grants,
            vec!["pending".encode_utf16().collect::<Vec<_>>()]
        );

        // Header-only decode must agree with the full decode for scalar gates.
        let header_only = ControllerState::decode_header(&words).expect("valid header");
        assert_eq!(header_only.phase.code(), decoded.phase.code());
        assert_eq!(header_only.query(1), decoded.query(1));
        assert_eq!(header_only.query(2), decoded.query(2));
    }

    #[test]
    fn generation_bumps_wrap_as_i32_and_unknown_phase_is_rejected() {
        let mut state = ControllerState::new(false, false);
        state.wireless_generation = i32::MAX;
        assert_eq!(
            state.event(EventCode::InvalidateWireless, 0, false),
            i64::from(i32::MIN)
        );
        assert_eq!(state.query(3), i64::from(i32::MIN));

        let mut words = ControllerState::new(false, false).encode();
        words[2] = 7; // out of the valid 0..=6 phase range
        assert!(ControllerState::decode(&words).is_none());
        assert!(ControllerState::decode_header(&words).is_none());
    }

    #[test]
    fn vpn_and_proof_enum_codes_map_both_ways() {
        for code in 0..=4 {
            let event = VpnEvent::from_code(code).expect("known vpn code");
            assert_eq!(event.code(), code);
        }
        assert_eq!(VpnEvent::from_code(5), None);
        assert_eq!(VpnEvent::from_code(-1), None);

        for code in 0..=7 {
            let event = ProofEvent::from_code(code).expect("known proof code");
            assert_eq!(event.code(), code);
        }
        assert_eq!(ProofEvent::from_code(8), None);
        assert_eq!(ProofEvent::from_code(-1), None);
    }

    #[test]
    fn proof_flag_encoding_matches_the_original_bitfield() {
        // Mirrors the previous raw flag arithmetic: bit3 opened, bit0 active,
        // bit1 authenticated, bit2 rendered.
        let mut proof = Proof::new();
        assert_eq!(proof.encode(), [-1, 0]);
        assert!(proof.event(ProofEvent::Begin, 5, false));
        assert_eq!(proof.encode(), [5, 8]);
        // Authenticated is rejected before a session is activated.
        assert!(!proof.event(ProofEvent::Authenticated, 5, false));
        assert_eq!(proof.encode(), [5, 8]);
        // Activate keeps only the epoch bit and sets active_session.
        assert!(proof.event(ProofEvent::Activate, 5, false));
        assert_eq!(proof.encode(), [5, 9]);
        // Authenticated records its factor but does not confirm until Rendered.
        assert!(!proof.event(ProofEvent::Authenticated, 5, false));
        assert_eq!(proof.encode(), [5, 11]);
        // Rendered completes both factors and clears the epoch bit.
        assert!(proof.event(ProofEvent::Rendered, 5, true));
        assert_eq!(proof.encode(), [5, 7]);
        assert!(proof.event(ProofEvent::HasActiveSession, 5, false));
        assert!(proof.event(ProofEvent::HasRenderedFrame, 5, false));
        // End clears the session/factor bits but keeps the epoch bit.
        assert!(proof.event(ProofEvent::End, 5, true));
        assert_eq!(proof.encode(), [5, 0]);
        assert!(proof.event(ProofEvent::Clear, 0, false));
        assert_eq!(proof.encode(), [-1, 0]);
        assert_eq!(Proof::decode(&proof.encode()).unwrap().encode(), proof.encode());
    }

    #[test]
    fn vpn_token_roundtrips_and_matches_original_generation_rules() {
        let mut vpn = Vpn::new();
        assert_eq!(vpn.encode(), [0, 0]);
        assert_eq!(vpn.event(VpnEvent::PrepareAttach, 0, false), 0);
        assert_eq!(vpn.event(VpnEvent::PrepareAttach, 0, true), 1);
        assert_eq!(vpn.event(VpnEvent::BeginAttach, 0, true), 1);
        assert_eq!(vpn.encode(), [1, 1]);
        assert_eq!(vpn.event(VpnEvent::PrepareAttach, 0, true), 2);
        assert_eq!(vpn.event(VpnEvent::Current, 1, true), 1);
        assert_eq!(vpn.event(VpnEvent::Current, 2, true), 0);
        assert_eq!(vpn.event(VpnEvent::Release, 0, true), 2);
        assert_eq!(vpn.encode(), [0, 2]);
        assert_eq!(Vpn::decode(&vpn.encode()).unwrap().encode(), vpn.encode());
    }
}
