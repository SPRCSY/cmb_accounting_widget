package com.csy.cmbspend;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析招行动账通知文本，判断动账类型并提取金额（单位：分）。
 *
 *  类型判定顺序（先特殊后一般）：
 *    1. 含「退款/退货/退费/冲账」→ 退款（从本月消费扣减）
 *    2. 含「收入/转入/到账/存入/转入/汇入」等收入类 → 收入（默认不计入，进「待决收入」由用户定性）
 *    3. 含「扣款/支出/消费」→ 支出（计入本月消费）
 *    4. 其余（无金额/无法识别）→ 无关
 *
 *  口径（用户 2026-09-14 决定）：
 *    - 转账/转出一律计入本月消费（招行把转账也写成「快捷支付扣款」，不再尝试区分转账 vs 消费）；
 *    - 自有资金划转（朝朝宝/基金/黄金/信用卡还款/数字人民币等）是账户内部操作、不是花销，
 *      由 INTERNAL_MARKERS 直接忽略，不进台账（否则同一笔钱会被数两遍，如信用卡还款）；
 *    - 收入侧交给待决表由用户逐笔定性。 */
public final class CmbNotificationParser {

    /** 金额格式 1（借记卡快捷支付）：人民币1.00 / 人民币 7.87 / 人民币1,234.56（数字在"人民币"后） */
    private static final Pattern AMOUNT_AFTER =
            Pattern.compile("人民币\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");
    /** 金额格式 2（信用卡消费/退款）：消费1.79人民币 / 金额1,234.56人民币（数字在"人民币"前） */
    private static final Pattern AMOUNT_BEFORE =
            Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*人民币");

    /** 退款类字样：出现即视为退款（从累计中扣减）。「退费」为常见变体。 */
    private static final String[] REFUND_MARKERS = {"退款", "退货", "退费", "冲账", "冲正"};
    /** 收入类字样（非退款）：出现任一即视为收入，进待决台账，默认不影响消费。
     *  覆盖：现金存入/柜面存款、他行转入/汇入、代发工资、到账、收款。
     *  注：取现（取款/支取）不在此——见 WITHDRAW_MARKERS，按"支出"保守计入消费。 */
    private static final String[] INCOME_MARKERS =
            {"收入", "转入", "到账", "存入", "存款", "汇入", "入账", "收款", "代发"};
    /** 取现类字样：按「支出」计入本月消费（保守估算——取出的现金迟早要花掉，
     *  用户口径 2026-09-12：宁可高估花销、不低估）。 */
    private static final String[] WITHDRAW_MARKERS =
            {"取款", "支取", "取现", "ATM取款", "现金支取"};
    /** 出账动词：招行**支出**通知一定会带「扣款」或「支出」（**入账**通知则带"入账/存入"）。
     *  必须在"收入词"之前判定——否则商户名里的字会劫持方向，例如
     *  「【财付通-微信支付-群收款】发生快捷支付扣款」会因"收款"被误判成收入。
     *  （凭毫不知情地把转账记成收入，是 2026-09-14 实测发现的问题。） */
    private static final String[] OUTFLOW_MARKERS = {"扣款", "支出"};
    /** 计入类字样：兜底。出现在"收入词"之后，故不会抢走带"入账"的进账。 */
    private static final String[] SPEND_MARKERS = {"消费", "转账"};

    /** 内部划转字样：自有资金在账户之间搬（赎回/申购/还款等），不是消费，命中即整条忽略。
     *  取的是**账单里真实出现过的文案**——注意信用卡还款的商户是「信用卡自扣」，不含「还款」二字，
     *  必须单独列出；基金申购/朝朝宝购买通知里若只有 6 位基金代码或流水号，则无法靠关键词命中。 */
    private static final String[] INTERNAL_MARKERS = {
            "朝朝宝", "朝朝盈", "信用卡自扣", "信用卡自动还款", "贵金属", "黄金账户",
            "数字人民币", "随用随充", "基金", "理财", "国债", "结构性存款",
            "申购", "赎回", "还款",
    };

    private CmbNotificationParser() {}

    /** 解析结果：类型 + 金额（分，绝对值）。 */
    public static final class Result {
        public static final String TYPE_SPEND = "s";
        public static final String TYPE_REFUND = "r";
        public static final String TYPE_INCOME = "i";
        public static final String TYPE_NONE = "";

        public final String type;
        public final long cents;   // 绝对值，>0 才有效
        /** 是否为现金取现（类型仍是支出，仅用于展示与对账时区分）。 */
        public final boolean withdrawal;

        Result(String type, long cents) { this(type, cents, false); }
        Result(String type, long cents, boolean withdrawal) {
            this.type = type; this.cents = cents; this.withdrawal = withdrawal;
        }
        public boolean isValid() { return cents > 0 && !TYPE_NONE.equals(type); }
        /** 明细里展示用的商户/来源标签。 */
        public String label() { return withdrawal ? "现金取款" : ""; }
        /** 对本月消费的影响：支出为正、退款为负、收入/无关为 0（收入需用户决策后才抵扣）。 */
        public long signedCents() {
            if (TYPE_SPEND.equals(type)) return cents;
            if (TYPE_REFUND.equals(type)) return -cents;
            return 0L;
        }
    }

    /** 提取商户名（方括号【】内），如「财付通-微信支付-扫码付款」。无则返回空串。 */
    public static String extractMerchant(String text) {
        if (text == null) return "";
        Matcher m = Pattern.compile("【(.+?)】").matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** 未来收紧规则入口：命中可疑商户（如转账类「扫二维码付款」）返回 true。当前恒为 false。 */
    public static boolean isExcludedMerchant(String merchant) {
        return false;
    }

    /** 解析一条通知全文（标题+正文），返回带符号金额（分）：支出正、退款负、收入/无关 0。
     *  保留旧语义，便于既有调用方。需要区分收入时用 parse()。 */
    public static long parseSignedCents(String text) {
        return parse(text, null).signedCents();
    }

    /** 解析一条通知全文，并应用用户自定义排除关键词（命中任一排除词则忽略）。 */
    public static long parseSignedCents(String text, java.util.Set<String> excludeWords) {
        return parse(text, excludeWords).signedCents();
    }

    /** 完整解析：返回类型与金额，供监听器分流到「支出累加」或「收入待决台账」。 */
    public static Result parse(String text, java.util.Set<String> excludeWords) {
        if (text == null) return new Result(Result.TYPE_NONE, 0L);
        String t = text.trim();
        if (t.isEmpty()) return new Result(Result.TYPE_NONE, 0L);

        // 用户自定义排除：命中任一排除词（正文或商户名）→ 忽略
        if (Rules.isExcluded(excludeWords, t, extractMerchant(t))) {
            return new Result(Result.TYPE_NONE, 0L);
        }

        // 内部划转（赎回/申购/还款/理财等自有资金搬移）→ 整条忽略，不计入消费也不进收入台账
        for (String s : INTERNAL_MARKERS) {
            if (t.contains(s)) return new Result(Result.TYPE_NONE, 0L);
        }

        // 1. 退款优先：含「退款/退货/退费/冲账」→ 退款
        for (String s : REFUND_MARKERS) {
            if (t.contains(s)) {
                long amt = extractAmount(t);
                return new Result(amt > 0 ? Result.TYPE_REFUND : Result.TYPE_NONE, amt);
            }
        }
        // 2. 出账动词（扣款/支出）优先于收入词：招行支出通知必带其一，进账通知则带"入账"。
        //    放这里可避免商户名里的"收款"（群收款）把一笔支出劫持成收入。
        for (String s : OUTFLOW_MARKERS) {
            if (t.contains(s)) {
                long amt = extractAmount(t);
                return new Result(amt > 0 ? Result.TYPE_SPEND : Result.TYPE_NONE, amt);
            }
        }
        // 3. 收入类（收入/转入/到账…）→ 进待决台账
        for (String s : INCOME_MARKERS) {
            if (t.contains(s)) {
                long amt = extractAmount(t);
                return new Result(amt > 0 ? Result.TYPE_INCOME : Result.TYPE_NONE, amt);
            }
        }
        // 4. 取现类（取款/支取/取现）→ 按支出保守计入消费
        for (String s : WITHDRAW_MARKERS) {
            if (t.contains(s)) {
                long amt = extractAmount(t);
                return new Result(amt > 0 ? Result.TYPE_SPEND : Result.TYPE_NONE, amt, amt > 0);
            }
        }
        // 5. 兜底计入类：含「消费/转账」才计入（含"入账"的进账已在上一步截走）
        boolean spend = false;
        for (String s : SPEND_MARKERS) {
            if (t.contains(s)) { spend = true; break; }
        }
        if (!spend) return new Result(Result.TYPE_NONE, 0L);
        if (isExcludedMerchant(extractMerchant(t))) return new Result(Result.TYPE_NONE, 0L);
        long amt = extractAmount(t);
        return new Result(amt > 0 ? Result.TYPE_SPEND : Result.TYPE_NONE, amt);
    }

    /** 提取金额（分）：先试「人民币X」格式，再试「X人民币」格式；无金额返回 0。 */
    public static long extractAmount(String t) {
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
