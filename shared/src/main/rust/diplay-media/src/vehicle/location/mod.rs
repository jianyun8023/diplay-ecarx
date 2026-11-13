//! Location numeric policy and GGA/RMC layout. Java supplies calendar and Formatter output.
mod jni;

fn coordinate(value: f64) -> (f64, f64) {
    let absolute = value.abs();
    let mut degrees = absolute as i32;
    let mut minutes = (absolute - f64::from(degrees)) * 60.0;
    if minutes >= 60.0 - 0.00005 {
        degrees += 1;
        minutes = 0.0;
    }
    (f64::from(degrees), minutes)
}
fn prepare(
    lat: f64,
    lon: f64,
    altitude: f64,
    bearing: f64,
    speed: f64,
    accuracy: f64,
    presence: i32,
) -> [f64; 9] {
    let (lat_deg, lat_min) = coordinate(lat);
    let (lon_deg, lon_min) = coordinate(lon);
    let hdop = if presence & 8 != 0 && accuracy.is_finite() && accuracy > 0.0 {
        (accuracy / 5.0).clamp(0.5, 50.0)
    } else {
        1.0
    };
    let altitude = if presence & 1 != 0 && altitude.is_finite() {
        altitude
    } else {
        0.0
    };
    // The finite input may overflow during conversion. Preserve Formatter's Infinity result.
    let knots = if presence & 4 != 0 && speed.is_finite() && speed >= 0.0 {
        speed * 1.94384449
    } else {
        0.0
    };
    let course = presence & 2 != 0 && bearing.is_finite();
    let flags = (lat >= 0.0) as u8 | (((lon >= 0.0) as u8) << 1) | ((course as u8) << 2);
    [
        lat_deg,
        lat_min,
        lon_deg,
        lon_min,
        hdop,
        altitude,
        knots,
        if course { bearing } else { 0.0 },
        f64::from(flags),
    ]
}
fn frame(parts: &[String], flags: i32) -> Option<String> {
    if parts.len() != 10 || !(0..8).contains(&flags) {
        return None;
    }
    let [time, date, lat_deg, lat_min, lon_deg, lon_min, hdop, altitude, knots, course] = parts
    else {
        return None;
    };
    let lat = if flags & 1 != 0 { 'N' } else { 'S' };
    let lon = if flags & 2 != 0 { 'E' } else { 'W' };
    let gga = format!("GPGGA,{time},{lat_deg}{lat_min},{lat},{lon_deg}{lon_min},{lon},1,08,{hdop},{altitude},M,0.0,M,,");
    let rmc = format!("GPRMC,{time},A,{lat_deg}{lat_min},{lat},{lon_deg}{lon_min},{lon},{knots},{course},{date},,");
    // Formatter output is ASCII, as are all fixed fields.
    let checksum = |body: &str| body.bytes().fold(0u8, |value, byte| value ^ byte);
    Some(format!(
        "${gga}*{:02X}\r\n${rmc}*{:02X}\r\n",
        checksum(&gga),
        checksum(&rmc)
    ))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn missing_non_finite_signed_zero_and_overflow_preserve_platform_plan() {
        let plan = prepare(-0.0, -0.0, -0.0, -0.0, -0.0, 0.1, 15);
        assert_eq!(plan[8], 7.0);
        assert_eq!(plan[4], 0.5);
        assert!(plan[5].is_sign_negative());
        assert!(plan[6].is_sign_negative());
        assert!(plan[7].is_sign_negative());
        let plan = prepare(
            90.0,
            -180.0,
            f64::NAN,
            f64::INFINITY,
            -1.0,
            f64::INFINITY,
            15,
        );
        assert_eq!(&plan[4..], &[1.0, 0.0, 0.0, 0.0, 1.0]);
        assert!(prepare(1.0, 1.0, 0.0, 0.0, f64::MAX, 1.0, 4)[6].is_infinite());
        assert_eq!(coordinate(89.99999999), (90.0, 0.0));
        assert_eq!(coordinate(-179.99999999), (180.0, 0.0));
    }
    #[test]
    fn head_unit_layout_and_empty_course_have_exact_checksums() {
        let parts = [
            "120405.00",
            "051025",
            "52",
            "31.2005",
            "013",
            "24.2972",
            "2.4",
            "34.6",
            "27.02",
            "271.50",
        ]
        .map(String::from);
        assert_eq!(frame(&parts, 7).unwrap(), "$GPGGA,120405.00,5231.2005,N,01324.2972,E,1,08,2.4,34.6,M,0.0,M,,*69\r\n$GPRMC,120405.00,A,5231.2005,N,01324.2972,E,27.02,271.50,051025,,*0C\r\n");
        let mut no_course = parts;
        no_course[9].clear();
        assert!(frame(&no_course, 3).unwrap().contains(",27.02,,051025,,*"));
        assert!(frame(&no_course[..9], 3).is_none());
    }
}
