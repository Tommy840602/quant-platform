package com.quant.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 每日 09:00（台北）推摘要：告警計數 + 請 account-service 推淨值摘要。 */
@Service
public class DailyDigest {

    private static final Logger log = LoggerFactory.getLogger(DailyDigest.class);
    private final AlertGate gate;
    private final SlackNotifier slack;
    private final AccountClient account;

    public DailyDigest(AlertGate gate, SlackNotifier slack, AccountClient account) {
        this.gate = gate; this.slack = slack; this.account = account;
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Taipei")
    public void send() { sendNow(); }

    public String sendNow() {
        String text = gate.digestText();
        slack.send(text);                       // 摘要不走去重（一天一次）
        try { account.summaryNow(); } catch (Exception e) { log.warn("淨值摘要觸發失敗: {}", e.getMessage()); }
        return text;
    }
}
