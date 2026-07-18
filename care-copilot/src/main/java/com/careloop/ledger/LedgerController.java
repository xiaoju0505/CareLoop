package com.careloop.ledger;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class LedgerController {

    private final PatientLedgerService patientLedgerService;

    public LedgerController(PatientLedgerService patientLedgerService) {
        this.patientLedgerService = patientLedgerService;
    }

    @GetMapping("/ledger/dashboard")
    public Map<String, Object> dashboard() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", patientLedgerService.renderDashboard());
        m.put("rows", patientLedgerService.snapshotRows());
        return m;
    }

    @PostMapping("/ledger/sync-bitable")
    public Map<String, Object> syncBitable() {
        return patientLedgerService.syncToBitable();
    }
}
