use super::{dict, error_code, remaining, string, text, Text, Value};
pub(super) enum Effect {
    Check,
    Connect(i64),
    Request(Value, i64, bool),
    Generate(Vec<u8>, Text),
    LogSet,
    LogPrepared,
    LogAttempt(i64),
    Trust,
    Sleep(i64),
    Done(Option<Vec<u8>>),
    Error(i64, Text),
}
pub(super) struct Pair {
    // stage, absolute deadline, attempt, retry milliseconds, last requested sleep.
    pub words: [i64; 5],
    pub config: [Text; 3],
    pub key: Option<Vec<u8>>,
    pub wifi: Option<Text>,
    pub record: Option<Value>,
    pub response: Option<Value>,
}
impl Pair {
    fn budget(&self, now: i64, human: bool) -> Result<i64, Effect> {
        remaining(self.words[1], now)
            .map(|n| if human { n } else { n.min(5000) })
            .ok_or(Effect::Error(1, Vec::new()))
    }
    fn remote(code: Text) -> Effect {
        Effect::Error(
            if code == text("UserDeniedPairing") {
                2
            } else if code == text("PasswordProtected") {
                3
            } else {
                4
            },
            code,
        )
    }
    fn invalid(message: &str) -> Effect {
        Effect::Error(5, text(message))
    }
    fn error(&self) -> Result<Option<Text>, Effect> {
        error_code(self.response.as_ref().unwrap())
            .map_err(|_| Self::invalid("Error was not text or integer"))
    }
    fn request(&self, which: i64, now: i64) -> Effect {
        let human = which == 15;
        let budget = match self.budget(now, human) {
            Ok(n) => n,
            Err(e) => return e,
        };
        let label = Value::Text(self.config[0].clone());
        let value = match which {
            3 => dict(vec![
                ("Label", label),
                ("Request", string("SetValue")),
                ("Key", string("UntrustedHostBUID")),
                ("Value", Value::Text(self.config[2].clone())),
            ]),
            6 | 9 => dict(vec![
                ("Label", label),
                ("Request", string("GetValue")),
                (
                    "Key",
                    string(if which == 6 {
                        "DevicePublicKey"
                    } else {
                        "WiFiAddress"
                    }),
                ),
            ]),
            15 => dict(vec![
                ("Label", label),
                ("PairRecord", self.record.as_ref().unwrap().clone()),
                ("Request", string("Pair")),
                ("ProtocolVersion", string("2")),
                (
                    "PairingOptions",
                    dict(vec![("ExtendedPairingErrors", Value::Bool(true))]),
                ),
            ]),
            _ => unreachable!(),
        };
        Effect::Request(value, budget, human)
    }
    pub fn step(&mut self, event: i32, cancelled: bool, now: i64) -> Effect {
        // Events: cancellation result=1, effect completed=2, response=3, generated record=4,
        // interrupted sleep=5, local human-response deadline=6. Transport failures never enter 6.
        // Stages alternate the requested effect with its before/after cancellation checks:
        // connect 0..1; SetValue 2..4 (+log 20); DevicePublicKey 5..7; WiFiAddress 8..10;
        // generate/log 11..12; Pair check/log/read/reply 13..16; trust/check/sleep 17..19.
        let result: Result<Effect, Effect> = (|| {
            if event == 5 {
                return Ok(Effect::Error(0, Vec::new()));
            }
            if event == 6 {
                return Ok(Effect::Error(1, Vec::new()));
            }
            if event == 0 {
                return Ok(Effect::Check);
            }
            if event == 3 {
                self.words[0] = match self.words[0] {
                    3 => 4,
                    6 => 7,
                    9 => 10,
                    15 => 16,
                    _ => unreachable!(),
                };
                return Ok(Effect::Check);
            }
            if event == 4 {
                self.words[0] = 12;
                return Ok(Effect::LogPrepared);
            }
            if event == 1 {
                if cancelled {
                    return Ok(Effect::Error(0, Vec::new()));
                }
                let stage = self.words[0];
                return Ok(match stage {
                    0 => {
                        self.words[0] = 1;
                        Effect::Connect(self.budget(now, false)?)
                    }
                    2 | 5 | 8 => {
                        let next = match stage {
                            2 => 3,
                            5 => 6,
                            _ => 9,
                        };
                        self.words[0] = next;
                        self.request(next, now)
                    }
                    4 => {
                        if let Some(code) = self.error()? {
                            return Ok(Self::remote(code));
                        }
                        self.words[0] = 20;
                        Effect::LogSet
                    }
                    7 | 10 => {
                        if let Some(code) = self.error()? {
                            return Ok(Self::remote(code));
                        }
                        let value = self
                            .response
                            .as_ref()
                            .unwrap()
                            .get("Value")
                            .ok_or_else(|| Self::invalid("GetValue response omitted Value"))?;
                        if stage == 7 {
                            self.key = Some(match value {
                                Value::Data(b) => b.clone(),
                                _ => return Ok(Self::invalid("DevicePublicKey was not data")),
                            });
                            self.words[0] = 8;
                            Effect::Check
                        } else {
                            self.wifi = Some(match value {
                                Value::Text(t) => t.clone(),
                                _ => return Ok(Self::invalid("WiFiAddress was not text")),
                            });
                            self.words[0] = 11;
                            Effect::Generate(
                                self.key.as_ref().unwrap().clone(),
                                self.wifi.as_ref().unwrap().clone(),
                            )
                        }
                    }
                    13 => {
                        self.words[2] += 1;
                        self.words[0] = 14;
                        Effect::LogAttempt(self.words[2])
                    }
                    16 => match self.error()? {
                        None => {
                            Effect::Done(match self.response.as_ref().unwrap().get("EscrowBag") {
                                None => None,
                                Some(Value::Data(b)) => Some(b.clone()),
                                _ => return Ok(Self::invalid("EscrowBag was not data")),
                            })
                        }
                        Some(code) if code == text("PairingDialogResponsePending") => {
                            self.words[0] = 17;
                            Effect::Trust
                        }
                        Some(code) => Self::remote(code),
                    },
                    18 => {
                        self.words[4] = self.words[3].min(100).min(self.budget(now, true)?);
                        self.words[0] = 19;
                        Effect::Sleep(self.words[4])
                    }
                    _ => unreachable!("private Pair cancellation stage"),
                });
            }
            // The platform has completed the requested I/O, log, callback or sleep effect.
            Ok(match self.words[0] {
                1 => {
                    self.words[0] = 2;
                    Effect::Check
                }
                20 => {
                    self.words[0] = 5;
                    Effect::Check
                }
                12 => {
                    self.words[0] = 13;
                    Effect::Check
                }
                14 => {
                    self.words[0] = 15;
                    self.request(15, now)
                }
                17 => {
                    self.words[3] = 1000;
                    self.words[0] = 18;
                    Effect::Check
                }
                19 => {
                    self.words[3] -= self.words[4];
                    self.words[0] = if self.words[3] > 0 { 18 } else { 13 };
                    Effect::Check
                }
                _ => unreachable!("private Pair effect stage"),
            })
        })();
        result.unwrap_or_else(|e| e)
    }
}

pub(super) fn service_response(response: &Value, session: bool) -> Result<(i64, bool), Text> {
    let request = if session {
        "StartSession"
    } else {
        "StartService"
    };
    match error_code(response) {
        Err(_) => {
            return Err(text(&format!(
                "{request} response Error was not text or integer"
            )))
        }
        Ok(Some(code)) => {
            let mut t = text(&format!("Lockdown {request} failed: "));
            t.extend(code);
            return Err(t);
        }
        _ => {}
    }
    if session {
        return match response.get("EnableSessionSSL") {
            Some(Value::Bool(true)) => Ok((0, true)),
            Some(Value::Bool(false)) => Err(text("StartSession did not enable TLS")),
            _ => Err(text(
                "StartSession response omitted boolean EnableSessionSSL",
            )),
        };
    }
    let port = match response.get("Port") {
        Some(Value::Int(n)) => *n,
        _ => return Err(text("StartService response omitted integer Port")),
    };
    if !(1..=65535).contains(&port) {
        return Err(text("StartService returned an invalid Port"));
    }
    Ok((
        port,
        matches!(response.get("EnableServiceSSL"), Some(Value::Bool(true))),
    ))
}
