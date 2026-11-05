//! 显示矩阵的角度换算 —— ffmpeg 的「逆时针」→ 容器的「顺时针」。
//!
//! 手机竖拍的片子，帧本身是横的，方向靠容器里的显示矩阵表达。ffmpeg 把那个矩阵
//! 放在 `codecpar->coded_side_data` 里（`AV_PKT_DATA_DISPLAYMATRIX`，9 个 `i32`），
//! 而 `av_display_rotation_get` 返回的是**逆时针**角度；Android 的
//! `AMediaMuxer_setOrientationHint` 要的是**顺时针**角度。两者差一个负号，
//! 搞反了画面就是倒的（还很难一眼看出来 —— 90° 与 270° 都是「竖的」）。
//!
//! 只有这层换算是纯算术，所以单独拎出来在主机上测；读 `coded_side_data` 那部分
//! 是 FFI，跟着 [`crate::convert`] 一起 cfg 掉。

/// ffmpeg 的逆时针角度（度）→ 容器要的顺时针角度，规整到 0 / 90 / 180 / 270。
///
/// 不是 90 的整数倍时返回 `None` —— 那种矩阵（带斜切或缩放的）转不过去，
/// 与其猜一个角度把画面转歪，不如**不写旋转提示**（保持躺倒，至少不是错的）。
///
/// `MPEG4Writer::setOrientationHint` 收到 0/90/180/270 以外的值会打一条日志
/// 然后**忽略**，所以非 90 倍数的值传下去等于白传。
pub fn cw_from_ccw(ccw: f64) -> Option<i32> {
    if !ccw.is_finite() {
        return None;
    }
    // 取负 → 顺时针；`rem_euclid` 把 [-180, 180] 映射到 [0, 360)。
    let cw = (-ccw).rem_euclid(360.0);
    let k = (cw / 90.0).round();
    // 容差 1°：`av_display_rotation_get` 走的是 `atan2`，整数矩阵算出来是精确值，
    // 但 float 走一圈回来会有 1e-14 量级的毛刺。
    if (cw - k * 90.0).abs() > 1.0 {
        return None;
    }
    Some(((k as i32) * 90).rem_euclid(360))
}

#[cfg(test)]
mod tests {
    use super::cw_from_ccw;

    #[test]
    fn quarter_turns_map_to_the_clockwise_equivalent() {
        // 0 → 0；90° 逆时针 = 270° 顺时针；180 自反；270° 逆时针 = 90° 顺时针。
        assert_eq!(cw_from_ccw(0.0), Some(0));
        assert_eq!(cw_from_ccw(90.0), Some(270));
        assert_eq!(cw_from_ccw(180.0), Some(180));
        assert_eq!(cw_from_ccw(270.0), Some(90));
    }

    #[test]
    fn negative_angles_are_the_clockwise_ones() {
        // ffmpeg 对「手机竖拍」这类最常见的源给的是 -90（= 90° 顺时针）。
        assert_eq!(cw_from_ccw(-90.0), Some(90));
        assert_eq!(cw_from_ccw(-180.0), Some(180));
        assert_eq!(cw_from_ccw(-270.0), Some(270));
    }

    #[test]
    fn angles_off_the_quarter_turn_are_refused() {
        assert_eq!(cw_from_ccw(45.0), None);
        assert_eq!(cw_from_ccw(1.5), None);
        assert_eq!(cw_from_ccw(-45.0), None);
    }

    #[test]
    fn float_noise_around_a_quarter_turn_still_snaps() {
        assert_eq!(cw_from_ccw(90.000_000_1), Some(270));
        assert_eq!(cw_from_ccw(-89.999_999_9), Some(90));
        // 绕回 360 的那一侧也要收得住。
        assert_eq!(cw_from_ccw(-359.999_9), Some(0));
    }

    #[test]
    fn non_finite_input_is_refused() {
        assert_eq!(cw_from_ccw(f64::NAN), None);
        assert_eq!(cw_from_ccw(f64::INFINITY), None);
    }
}
