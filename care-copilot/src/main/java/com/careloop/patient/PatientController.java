package com.careloop.patient;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "50") int limit) {
        return patientService.list(limit);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return patientService.get(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, String> body) {
        return patientService.create(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, String> body) {
        return patientService.update(id, body);
    }
}
