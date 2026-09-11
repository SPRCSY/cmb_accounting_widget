package com.csy.cmbspend;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 收入定性页，两种模式：
 *   - 待决策（默认）：**只列未决策的收入**；保存后该笔立即从列表消失。
 *   - 已决策历史：列出所有已定性的收入（退款/他人AA/忽略），可改主意重新保存。
 *  抵扣额从本月消费中扣减；总额由台账推导，改性质按新值重算。 */
public class PendingIncomeActivity extends Activity {

    private static final String[] NATURE_LABELS = {"待决策", "退款", "他人AA", "忽略"};
    private static final String[] NATURE_VALUES = {
            SpendStore.NATURE_UNDECIDED, SpendStore.NATURE_REFUND,
            SpendStore.NATURE_AA, SpendStore.NATURE_IGNORE};

    private LinearLayout listView;
    private TextView introView;
    private Button toggleBtn;
    private boolean showHistory = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pending);
        listView = findViewById(R.id.pending_list);
        introView = findViewById(R.id.pending_intro);
        toggleBtn = findViewById(R.id.pending_toggle_btn);
        toggleBtn.setOnClickListener(v -> {
            showHistory = !showHistory;
            render();
        });
        findViewById(R.id.pending_back_btn).setOnClickListener(v -> finish());
        applyWindowInsets();
    }

    /** targetSdk 35 强制 edge-to-edge：给内容加系统栏内边距，避免顶到状态栏。 */
    private void applyWindowInsets() {
        View root = findViewById(R.id.pending_container);
        if (root == null) return;
        final int baseL = root.getPaddingLeft(), baseT = root.getPaddingTop();
        final int baseR = root.getPaddingRight(), baseB = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(baseL + bars.left, baseT + bars.top, baseR + bars.right, baseB + bars.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        listView.removeAllViews();
        List<SpendStore.Income> all = SpendStore.getIncomes(this);
        List<SpendStore.Income> pending = new ArrayList<>();
        List<SpendStore.Income> decided = new ArrayList<>();
        for (SpendStore.Income i : all) {
            if (i.isUndecided()) pending.add(i);
            else decided.add(i);
        }

        toggleBtn.setText(showHistory ? "返回待决策（" + pending.size() + "）"
                : "查看已决策历史（" + decided.size() + "）");

        // 顶部说明 + 汇总
        if (all.isEmpty()) {
            introView.setText("本月暂无捕获到的收入。当招行通知里出现「收入/转入/到账」等字样时，"
                    + "会自动登记到这里等你定性。");
            return;
        }
        if (!showHistory) {
            if (pending.isEmpty()) {
                introView.setText("本月 " + all.size() + " 笔收入已全部定性完成 ✔\n"
                        + "点下方按钮可查看已决策历史或改主意。");
                return;
            }
            introView.setText("待你定性的收入 " + pending.size() + " 笔"
                    + "（本月共 " + all.size() + " 笔，已决策 " + decided.size() + " 笔）。\n"
                    + "判断性质并填写应从本月消费中抵扣的金额；保存后这笔会从列表消失。");
        } else {
            long deductSum = 0;
            for (SpendStore.Income i : decided) deductSum += i.deductCents;
            introView.setText("已决策 " + decided.size() + " 笔，合计抵扣 "
                    + SpendWidgetProvider.formatYuan(deductSum)
                    + "。可改性质或金额后重新保存（按新值重算，不会重复扣）。");
        }

        List<SpendStore.Income> shown = showHistory ? decided : pending;
        if (shown.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(showHistory ? "（暂无已决策记录）" : "（暂无待决策收入）");
            empty.setTextSize(13);
            empty.setTextColor(0xFF888888);
            empty.setPadding(0, dp(12), 0, dp(12));
            listView.addView(empty);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (SpendStore.Income inc : shown) {
            listView.addView(buildCard(inflater, inc));
        }
    }

    private View buildCard(LayoutInflater inflater, SpendStore.Income inc) {
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        View card = inflater.inflate(R.layout.pending_card, listView, false);
        TextView title = card.findViewById(R.id.income_title);
        TextView raw = card.findViewById(R.id.income_raw);
        Spinner nature = card.findViewById(R.id.income_nature);
        EditText deduct = card.findViewById(R.id.income_deduct);
        Button save = card.findViewById(R.id.income_save);

        title.setText(fmt.format(new Date(inc.timeMs)) + "　收入 "
                + SpendWidgetProvider.formatYuan(inc.cents)
                + "　当前：" + inc.natureLabel()
                + "　已抵扣 " + SpendWidgetProvider.formatYuan(inc.deductCents));
        raw.setText(inc.merchant.isEmpty() ? inc.raw : inc.merchant + "\n" + inc.raw);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, NATURE_LABELS);
        nature.setAdapter(adapter);
        nature.setSelection(indexOfNature(inc.nature));

        // 默认抵扣额：未决策时填全额，已决策时保留已保存的抵扣额
        long initDeduct = inc.isUndecided() ? inc.cents : inc.deductCents;
        deduct.setText(centsToYuanText(initDeduct));

        // 选「忽略」时把抵扣额清零；选其它且用户没手动改过则填回全额
        final boolean[] userEdited = {false};
        deduct.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                userEdited[0] = true;
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        nature.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (SpendStore.NATURE_IGNORE.equals(NATURE_VALUES[pos])) {
                    deduct.setText("0");
                } else if (!userEdited[0] && "0".equals(deduct.getText().toString().trim())) {
                    deduct.setText(centsToYuanText(inc.cents));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        save.setOnClickListener(v -> {
            String nv = NATURE_VALUES[nature.getSelectedItemPosition()];
            long cents = yuanTextToCents(deduct.getText().toString());
            if (SpendStore.NATURE_IGNORE.equals(nv)) cents = 0;
            if (cents < 0) cents = 0;
            if (cents > inc.cents) {
                Toast.makeText(this, "抵扣额不能超过收入金额（"
                        + SpendWidgetProvider.formatYuan(inc.cents) + "）", Toast.LENGTH_SHORT).show();
                return;
            }
            if (SpendStore.NATURE_UNDECIDED.equals(nv)) {
                Toast.makeText(this, "请先选择性质（退款 / 他人AA / 忽略）", Toast.LENGTH_SHORT).show();
                return;
            }
            SpendStore.setIncomeDecision(this, inc.id, nv, cents);
            SpendWidgetProvider.refresh(this);
            render();   // 保存后该笔离开待决策列表
            Toast.makeText(this, "已保存：" + natureLabelOf(nv) + "，抵扣 "
                    + SpendWidgetProvider.formatYuan(cents), Toast.LENGTH_SHORT).show();
        });
        return card;
    }

    private String natureLabelOf(String nature) {
        if (SpendStore.NATURE_REFUND.equals(nature)) return "退款";
        if (SpendStore.NATURE_AA.equals(nature)) return "他人AA";
        if (SpendStore.NATURE_IGNORE.equals(nature)) return "忽略";
        return "待决策";
    }

    private int indexOfNature(String nature) {
        if (nature == null) return 0;
        for (int i = 0; i < NATURE_VALUES.length; i++) {
            if (NATURE_VALUES[i].equals(nature)) return i;
        }
        return 0;
    }

    private int dp(int v) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private String centsToYuanText(long cents) {
        if (cents == 0) return "0";
        return cents % 100 == 0 ? String.valueOf(cents / 100)
                : String.format(Locale.US, "%.2f", cents / 100.0);
    }

    /** "12.34" → 1234 分；非法输入返回 0。 */
    private long yuanTextToCents(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.isEmpty()) return 0;
        try {
            return Math.round(Double.parseDouble(s) * 100.0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
