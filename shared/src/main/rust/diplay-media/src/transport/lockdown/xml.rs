use super::{base64, text, unbase64, Text, Value};
use std::collections::HashSet;
pub(super) enum Fault {
    Pending,
    Protocol(Text),
}
type Result<T> = std::result::Result<T, Fault>;
fn fail<T>(s: &str) -> Result<T> {
    Err(Fault::Protocol(text(s)))
}
pub(super) struct Token {
    pub event: i32,
    pub name: Text,
    pub value: Option<Text>,
    pub blank: bool,
}
pub(super) trait Lexer {
    fn next(&mut self, tag: bool) -> Result<Token>;
    fn integer(&mut self, text: &[u16]) -> Result<Option<i64>>;
    fn data(&mut self, text: &[u16]) -> Result<Text>;
}
fn simple(lexer: &mut impl Lexer, name: &[u16]) -> Result<Text> {
    let mut out = Vec::new();
    loop {
        let token = lexer.next(false)?;
        match token.event {
            4 | 5 => out.extend(token.value.unwrap_or_default()),
            6 => match token.value {
                Some(t) => out.extend(t),
                None => {
                    let mut e = text("Unknown entity in Lockdown plist <");
                    e.extend(name);
                    e.push(62);
                    return Err(Fault::Protocol(e));
                }
            },
            3 => {
                if token.name == name {
                    return Ok(out);
                } else {
                    let mut e = text("Invalid Lockdown plist <");
                    e.extend(name);
                    e.push(62);
                    return Err(Fault::Protocol(e));
                }
            }
            2 | 1 => {
                let mut e = text(if token.event == 2 {
                    "Lockdown plist <"
                } else {
                    "Lockdown plist ended inside <"
                });
                e.extend(name);
                e.extend(text(if token.event == 2 {
                    "> must not contain elements"
                } else {
                    ">"
                }));
                return Err(Fault::Protocol(e));
            }
            _ => {}
        }
    }
}
fn value(lexer: &mut impl Lexer, start: Token, depth: usize) -> Result<Value> {
    if depth > 64 {
        return fail("Lockdown plist nesting exceeds 64");
    }
    match start.name.as_slice() {
        n if n == text("dict") => {
            let mut items = Vec::<(Text, Value)>::new();
            let mut keys = HashSet::new();
            loop {
                let next = lexer.next(true)?;
                if next.event != 2 {
                    if next.name != text("dict") {
                        return fail("Invalid Lockdown plist dictionary");
                    }
                    return Ok(Value::Dict(items));
                }
                if next.name != text("key") || items.len() == 1024 {
                    return fail("Invalid Lockdown plist dictionary");
                }
                let key = simple(lexer, &next.name)?;
                if !keys.insert(key.clone()) {
                    let mut e = text("Duplicate Lockdown plist key '");
                    e.extend(key);
                    e.push(39);
                    return Err(Fault::Protocol(e));
                }
                let next = lexer.next(true)?;
                if next.event != 2 {
                    return fail("Lockdown plist dictionary value is missing");
                }
                items.push((key, value(lexer, next, depth + 1)?));
            }
        }
        n if n == text("string") => Ok(Value::Text(simple(lexer, &start.name)?)),
        n if n == text("integer") => {
            let t = simple(lexer, &start.name)?;
            match lexer.integer(&t)? {
                Some(n) => Ok(Value::Int(n)),
                None => fail("Invalid Lockdown plist integer"),
            }
        }
        n if n == text("data") => {
            let t = simple(lexer, &start.name)?;
            let t = lexer.data(&t)?;
            unbase64(&t)
                .map(Value::Data)
                .map_err(|_| Fault::Protocol(text("Invalid base64 Lockdown plist data")))
        }
        n if n == text("true") || n == text("false") => {
            let next = lexer.next(true)?;
            if next.event != 3 || next.name != start.name {
                let mut e = text("Lockdown plist <");
                e.extend(start.name);
                e.extend(text("> must be empty"));
                return Err(Fault::Protocol(e));
            }
            Ok(Value::Bool(start.name == text("true")))
        }
        _ => {
            let mut e = text("Unsupported Lockdown plist value <");
            e.extend(start.name);
            e.push(62);
            Err(Fault::Protocol(e))
        }
    }
}
pub(super) fn parse(lexer: &mut impl Lexer) -> Result<Value> {
    let first = lexer.next(true)?;
    if first.event != 2 || first.name != text("plist") {
        return fail("Lockdown plist root must be <plist>");
    }
    let first = lexer.next(true)?;
    if first.event != 2 {
        return fail("Lockdown plist must contain exactly one root value");
    }
    let result = value(lexer, first, 0)?;
    let end = lexer.next(true)?;
    if end.event != 3 || end.name != text("plist") {
        return fail("Lockdown plist must contain exactly one root value");
    }
    loop {
        let t = lexer.next(false)?;
        if t.event == 1 {
            break;
        }
        if !(matches!(t.event, 4 | 7) && t.blank) {
            return fail("Unexpected content after Lockdown plist");
        }
    }
    if !matches!(result, Value::Dict(_)) {
        return fail("Lockdown plist root value must be <dict>");
    }
    Ok(result)
}
pub(super) fn encode(value: &Value) -> Result<Text> {
    let mut out=text("<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\"><plist version=\"1.0\">");
    append(&mut out, value, 0)?;
    out.extend(text("</plist>"));
    Ok(out)
}
fn escape(out: &mut Text, value: &[u16]) {
    for &c in value {
        out.extend(match c {
            38 => text("&amp;"),
            60 => text("&lt;"),
            62 => text("&gt;"),
            34 => text("&quot;"),
            39 => text("&apos;"),
            _ => vec![c],
        })
    }
}
fn append(out: &mut Text, value: &Value, depth: usize) -> Result<()> {
    if depth > 64 {
        return fail("Lockdown plist nesting exceeds 64");
    }
    match value {
        Value::Dict(items) => {
            if items.len() > 1024 {
                return fail("Lockdown plist dictionary exceeds 1024 entries");
            };
            out.extend(text("<dict>"));
            for (k, v) in items {
                out.extend(text("<key>"));
                escape(out, k);
                out.extend(text("</key>"));
                append(out, v, depth + 1)?;
            }
            out.extend(text("</dict>"));
        }
        Value::Text(t) => {
            out.extend(text("<string>"));
            escape(out, t);
            out.extend(text("</string>"));
        }
        Value::Int(v) => out.extend(text(&format!("<integer>{v}</integer>"))),
        Value::Bool(v) => out.extend(text(if *v { "<true/>" } else { "<false/>" })),
        Value::Data(v) => {
            out.extend(text("<data>"));
            out.extend(base64(v));
            out.extend(text("</data>"));
        }
    }
    Ok(())
}
