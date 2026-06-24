package com.visualizer.api;

import com.visualizer.api.dto.ExecutionTrace;
import com.visualizer.api.dto.SimulateRequest;
import com.visualizer.interpreter.Interpreter;
import org.springframework.web.bind.annotation.*;

/**
 * Simulation endpoint. Parses, validates, and interprets the submitted source
 * via {@link Interpreter}, returning a real {@link ExecutionTrace} (or an
 * {@code ERROR} trace on validation failure). The Phase 0 mock is no longer in
 * the execution path.
 */
@RestController
@RequestMapping("/api")
public class SimulationController {

    private final Interpreter interpreter;

    public SimulationController(Interpreter interpreter) {
        this.interpreter = interpreter;
    }

    @PostMapping("/simulate")
    public ExecutionTrace simulate(@RequestBody SimulateRequest request) {
        return interpreter.simulate(request.sourceCode());
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
