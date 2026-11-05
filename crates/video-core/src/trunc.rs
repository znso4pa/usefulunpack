//! 「源文件短了一截」的判定。
//!
//! 截断的 MP4（下载中断、拷贝没拷完）**不会报错**：`moov` 完好、`av_read_frame`
//! 走到数据尽头就正常返回 EOF，解码器照常吐完剩下的帧。于是整条链路一路成功，
//! 交出一个时长只有源声明三分之一的小片子，用户看到的是「转换完成」。
//!
//! 能拿到的两个数：容器**声明**的时长，和实际**解出来**的时长。差得多就是截断。
//! 这里只有算术，所以放在主机上测；读时长那部分是 FFI，留在各自的转换器里。

/// 判定阈值：差得少于这么多就当正常收尾的零头。
///
/// 容器时长和最后一帧之间天然差半个到一个帧长，AAC 那边还会补到整帧；
/// 几百毫秒的差是正常现象，不该报警。
const TOLERANCE_US: i64 = 500_000;

/// 差得比例小于这个数也当正常 —— 有些工具会把时长向上取整到秒，
/// 5% 的差很常见。真正截断的文件动辄差一半以上。
const TOLERANCE_PCT: i32 = 10;

/// 实际解出的时长占声明时长的百分比；**0 表示「正常，或判断不了」**。
///
/// 返回 `0` 的四种情况：声明时长无效、一帧都没解出来、差得在容差内、差得比例太小。
/// 其余返回 `1..=99`（只报「少了」，从不说「多了」—— 解出来比声明长是容器不准，
/// 不是截断）。
pub fn decoded_percent(declared_us: i64, actual_us: i64) -> i32 {
    // 声明时长无效（有些容器就是不给）→ 无从比较，别猜。
    if declared_us <= 0 || actual_us <= 0 {
        return 0;
    }
    let short = declared_us - actual_us;
    if short <= TOLERANCE_US {
        return 0;
    }
    let pct = (actual_us.saturating_mul(100) / declared_us) as i32;
    if pct >= 100 - TOLERANCE_PCT {
        return 0;
    }
    // 少得再多也至少报 1%：报 0 会被当成「正常」。
    pct.max(1)
}

#[cfg(test)]
mod tests {
    use super::decoded_percent;

    const SEC: i64 = 1_000_000;

    #[test]
    fn an_intact_file_is_not_flagged() {
        // 差几十毫秒是容器取整的正常零头。
        assert_eq!(decoded_percent(3 * SEC, 3 * SEC - 40_000), 0);
        assert_eq!(decoded_percent(3 * SEC, 3 * SEC), 0);
        // 解出来比声明还长：容器不准，不是截断。
        assert_eq!(decoded_percent(3 * SEC, 3 * SEC + 200_000), 0);
    }

    #[test]
    fn a_file_missing_more_than_a_tenth_is_flagged() {
        // 3 秒的源只解出 0.78 秒（真机上量到的那个截断样本）。
        assert_eq!(decoded_percent(3 * SEC, 780_000), 26);
        // 刚好少 20%。
        assert_eq!(decoded_percent(10 * SEC, 8 * SEC), 80);
    }

    #[test]
    fn a_small_shortfall_is_tolerated() {
        // 少 5%：工具向上取整到秒的常见结果。
        assert_eq!(decoded_percent(10 * SEC, 9_500_000), 0);
        // 少 9.9%：仍在容差里。
        assert_eq!(decoded_percent(10 * SEC, 9_010_000), 0);
        // 少 10% 以上：开始报。
        assert_eq!(decoded_percent(10 * SEC, 8_900_000), 89);
    }

    #[test]
    fn a_long_file_needs_a_proportional_shortfall() {
        // 一小时的文件少 10 秒 = 0.28%，不该报（这种差多半是编辑列表或取整）。
        assert_eq!(decoded_percent(3600 * SEC, 3590 * SEC), 0);
        // 但少 20 分钟就要报。
        assert_eq!(decoded_percent(3600 * SEC, 2400 * SEC), 66);
    }

    #[test]
    fn a_tiny_file_is_never_flagged() {
        // 0.4 秒的源少了 0.3 秒：绝对值在容差内，太短的文件不值得下结论。
        assert_eq!(decoded_percent(400_000, 100_000), 0);
    }

    #[test]
    fn unknown_durations_are_not_guessed() {
        assert_eq!(decoded_percent(0, 1_000_000), 0);
        assert_eq!(decoded_percent(-1, 1_000_000), 0);
        assert_eq!(decoded_percent(3 * SEC, 0), 0);
        assert_eq!(decoded_percent(3 * SEC, -1), 0);
    }

    #[test]
    fn a_nearly_empty_output_still_reports_at_least_one_percent() {
        // 报 0 会被上层当成「正常」，所以再少也要给一个非零值。
        assert_eq!(decoded_percent(100 * SEC, 1000), 1);
    }
}
