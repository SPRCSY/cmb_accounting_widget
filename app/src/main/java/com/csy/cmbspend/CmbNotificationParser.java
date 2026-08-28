package com.csy.cmbspend;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析招行动账通知文本，判断是否计入消费并提取金额（单位：分）。
 *
 *  v1 口径（已确认）：正文含「扣款/支出/消费」且不含收入类字样（收入/退款/转入/到账）的，
 *  金额一律计入。已知局限：招行把「微信转账/扫码转出」也写成「快捷支付扣款」，光靠文案无法
 *  区分，v1 可能把转账也计入——已预留 merchant 提取与 isExcludedMerchant() 规则接口，
 *  拿到更多真实样本后在这里收紧。 */
public final class CmbNotificationParser {

    /** 金额格式 1（借记卡快捷支付）：人民币1.00 / 人民币 7.87 / 人民币1,234.56（数字在"人民币"后）
     *  数字部分允许千分位逗号，解析时再去除。 */
    private static final Pattern AMOUNT_AFTER =
            Pattern.compile("人民币\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");
    /** 金额格式 2（信用卡消费/退款）：消费1.79人民币 / 金额1,234.56人民币（数字在"人民币"前） */
    private static final Pattern AMOUNT_BEFORE =
            Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*人民币");

    /** 退款类字样：出现则本笔为退款（从累计中扣减），而非消费 */
    private static final String[] REFUND_MARKERS = {"退款", "退货"};
    /** 收入类字样（非退款）：出现任一即忽略（不计入也不扣减） */
    private static final String[] INCOME_MARKERS = {"收入", "转入", "到账"};
    /** 计入类字样：至少出现一个才考虑计入 */
    private static final String[] SPEND_MARKERS = {"扣款", "支出", "消费"};

    private CmbNotificationParser() {}

    /** 提取商户名（方括号【】内），如「财付通-微信支付-扫码付款」。v1 仅记录用，不参与排除。 */
    public static String extractMerchant(String text) {
        if (text == null) return "";
        Matcher m = Pattern.compile("【(.+?)】").matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** 未来收紧规则入口：命中可疑商户（如转账类「扫二维码付款」）返回 true。v1 恒为 false。 */
    public static boolean isExcludedMerchant(String merchant) {
        return false;
    }

    /** 解析一条通知全文（标题+正文）。
     *  返回带符号金额（分）：消费为正数、退款为负数；与消费无关（收入/转入/到账/无金额）返回 0。 */
    public static long parseSignedCents(String text) {
        return parseSignedCents(text, null);
    }

    /** 解析一条通知全文，并应用用户自定义排除关键词（命中任一排除词则忽略，返回 0）。 */
    public static long parseSignedCents(String text, java.util.Set<String> excludeWords) {
        if (text == null) return 0L;
        String t = text.trim();
        if (t.isEmpty()) return 0L;

        // 用户自定义排除：命中任一排除词（正文或商户名）→ 忽略
        if (Rules.isExcluded(excludeWords, t, extractMerchant(t))) return 0L;

        // 退款优先判断：含「退款/退货」→ 提取金额，返回负数（扣减）
        boolean refund = false;
        for (String s : REFUND_MARKERS) {
            if (t.contains(s)) { refund = true; break; }
        }
        if (refund) {
            long amt = extractAmount(t);
            return amt > 0 ? -amt : 0L;
        }

        // 非退款的收入类（收入/转入/到账）：忽略
        for (String s : INCOME_MARKERS) {
            if (t.contains(s)) return 0L;
        }
        // 消费类：含「扣款/支出/消费」才计入
        boolean spend = false;
        for (String s : SPEND_MARKERS) {
            if (t.contains(s)) { spend = true; break; }
        }
        if (!spend) return 0L;
        if (isExcludedMerchant(extractMerchant(t))) return 0L;
        return extractAmount(t);
    }

    /** 提取金额（分）：先试「人民币X」格式，再试「X人民币」格式；无金额返回 0。 */
    private static long extractAmount(String t) {
        Matcher m = AMOUNT_AFTER.matcher(t);
        if (!m.find()) {
            m = AMOUNT_BEFORE.matcher(t);
            if (!m.find()) return 0L;
        }
        try {
            // 去除千分位逗号后再解析，避免 "1,234.56" 被当成 "1"
            double yuan = Double.parseDouble(m.group(1).replace(",", ""));
            return Math.round(yuan * 100.0);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
