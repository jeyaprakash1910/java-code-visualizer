package com.visualizer.interpreter;

import com.visualizer.api.dto.ErrorInfoDto;
import com.visualizer.api.dto.ExecutionTrace;
import com.visualizer.interpreter.engine.ExecutionLimitExceededException;
import com.visualizer.interpreter.engine.InterpreterException;
import com.visualizer.interpreter.engine.ProgramInterpreter;
import com.visualizer.parser.JavaCodeParser;
import com.visualizer.parser.ParseOutcome;
import com.visualizer.parser.ValidationError;
import org.springframework.stereotype.Component;

/**
 * Public entry point for simulation: source code in, {@link ExecutionTrace} out.
 *
 * <p>Pipeline: parse → validate → execute → record trace. Validation failures
 * never reach execution; they come back as an {@code ERROR} trace carrying an
 * {@link ErrorInfoDto} (line + message), exactly as the existing API contract
 * expects. On success it returns the real, step-by-step trace produced by the
 * engine — no mock anywhere in this path.</p>
 */
@Component
public class Interpreter {

    private final JavaCodeParser parser;
    private final ProgramInterpreter programInterpreter = new ProgramInterpreter();

    public Interpreter(JavaCodeParser parser) {
        this.parser = parser;
    }

    public ExecutionTrace simulate(String sourceCode) {
        ParseOutcome outcome = parser.parseAndValidate(sourceCode);
        if (!outcome.isValid()) {
            return ExecutionTrace.error(toErrorInfo(outcome));
        }
        try {
            return programInterpreter.run(outcome.compilationUnit()).trace();
        } catch (ExecutionLimitExceededException e) {
            return ExecutionTrace.error(new ErrorInfoDto("EXECUTION_LIMIT", e.getMessage(), null));
        } catch (StackOverflowError e) {
            // Unbounded recursion can exhaust the host stack before the per-statement
            // limit fires; surface it as the same bounded-execution failure.
            return ExecutionTrace.error(new ErrorInfoDto("EXECUTION_LIMIT",
                    "Execution limit exceeded. Possible infinite recursion detected.", null));
        } catch (InterpreterException e) {
            return ExecutionTrace.error(new ErrorInfoDto("RUNTIME_ERROR", e.getMessage(), null));
        }
    }

    /** Surface the first validation problem; mention how many more there are. */
    private ErrorInfoDto toErrorInfo(ParseOutcome outcome) {
        ValidationError first = outcome.errors().get(0);
        int remaining = outcome.errors().size() - 1;
        String message = remaining > 0
                ? first.message() + " (and " + remaining + " more issue" + (remaining == 1 ? "" : "s") + ")"
                : first.message();
        return new ErrorInfoDto("VALIDATION_ERROR", message, first.line());
    }
}
