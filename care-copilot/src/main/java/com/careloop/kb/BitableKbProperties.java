package com.careloop.kb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 飞书多维表格知识库配置。
 * 医生在多维表格改规则后，调用同步接口即可更新随访助手。
 */
@Component
@ConfigurationProperties(prefix = "careloop.kb.bitable")
public class BitableKbProperties {

    private boolean enabled = false;
    /** 多维表格 URL 中 /base/ 后面的 token */
    private String appToken = "";
    private String tableNodes = "";
    private String tableRules = "";
    private String tableDomains = "";
    private String tableReplies = "";
    private String tableBriefing = "";
    private boolean syncOnStartup = false;
    /** 定时同步间隔毫秒，0 表示关闭 */
    private long syncIntervalMs = 600000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppToken() {
        return appToken;
    }

    public void setAppToken(String appToken) {
        this.appToken = appToken;
    }

    public String getTableNodes() {
        return tableNodes;
    }

    public void setTableNodes(String tableNodes) {
        this.tableNodes = tableNodes;
    }

    public String getTableRules() {
        return tableRules;
    }

    public void setTableRules(String tableRules) {
        this.tableRules = tableRules;
    }

    public String getTableDomains() {
        return tableDomains;
    }

    public void setTableDomains(String tableDomains) {
        this.tableDomains = tableDomains;
    }

    public String getTableReplies() {
        return tableReplies;
    }

    public void setTableReplies(String tableReplies) {
        this.tableReplies = tableReplies;
    }

    public String getTableBriefing() {
        return tableBriefing;
    }

    public void setTableBriefing(String tableBriefing) {
        this.tableBriefing = tableBriefing;
    }

    public boolean isSyncOnStartup() {
        return syncOnStartup;
    }

    public void setSyncOnStartup(boolean syncOnStartup) {
        this.syncOnStartup = syncOnStartup;
    }

    public long getSyncIntervalMs() {
        return syncIntervalMs;
    }

    public void setSyncIntervalMs(long syncIntervalMs) {
        this.syncIntervalMs = syncIntervalMs;
    }

    public boolean isReady() {
        return enabled
                && appToken != null && !appToken.isBlank()
                && tableNodes != null && !tableNodes.isBlank()
                && tableRules != null && !tableRules.isBlank();
    }
}
